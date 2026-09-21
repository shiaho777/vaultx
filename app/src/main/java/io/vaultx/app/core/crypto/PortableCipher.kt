package io.vaultx.app.core.crypto

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.SecureRandom
import java.util.Arrays

/**
 * `.vlt` 便携加密格式——无锁模式的"加密转换"产物。
 *
 * 自描述文件头(明文,无秘密):
 * ```
 * magic "VXLT" (4B) | version (1B=1)
 * memoryKiB u32be | iterations u32be | parallelism u32be
 * saltLen u8 | salt
 * <Tink Streaming AEAD 密文(AD="vaultx-portable-v1")>
 * ```
 * 密钥链:一次性密码 → Argon2id(头内参数) → 直接作为流式密钥,不走 VMK 包裹。
 * 密码参数由本类接管——函数返回前尽力清零(与 VaultManager 同一约定)。
 */
object PortableCipher {

    private val MAGIC = byteArrayOf('V'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_LENGTH = 16
    private val AD = "vaultx-portable-v1".toByteArray(Charsets.UTF_8)
    private const val MAX_KDF_MEMORY_KIB = 512 * 1024
    private const val MAX_KDF_ITERATIONS = 64
    private const val MAX_KDF_PARALLELISM = 8

    private val random = SecureRandom()

    /** 写头并返回加密流;调用方写完后 close 即封段。密码用完清零。 */
    fun openEncryptingStream(
        password: CharArray,
        out: OutputStream,
        params: KdfParams = KdfParams.DEFAULT,
    ): OutputStream {
        try {
            val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
            val kek = Argon2idKdf.derive(password, salt, params)
            val aead = try {
                TinkStreaming.fromRawKey(kek)
            } finally {
                Arrays.fill(kek, 0.toByte())
            }
            val header = DataOutputStream(out)
            header.write(MAGIC)
            header.writeByte(VERSION.toInt())
            header.writeInt(params.memoryKiB)
            header.writeInt(params.iterations)
            header.writeInt(params.parallelism)
            header.writeByte(salt.size)
            header.write(salt)
            header.flush()
            return aead.newEncryptingStream(out, AD)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * 读头 + 派生 + 打开解密流,并**探测首段**——密码错误立即抛 [WrongPasswordException],
     * 而不是等到读到一半才炸。
     */
    @Throws(WrongPasswordException::class, PortableFormatException::class)
    fun openDecryptingStream(password: CharArray, input: InputStream): InputStream {
        try {
            val header = DataInputStream(input)
            val (params, salt) = readHeader(header)
            val kek = Argon2idKdf.derive(password, salt, params)
            val aead = try {
                TinkStreaming.fromRawKey(kek)
            } finally {
                Arrays.fill(kek, 0.toByte())
            }
            val raw = try {
                aead.newDecryptingStream(header, AD)
            } catch (e: Exception) {
                throw WrongPasswordException()
            }
            // 探测:读一字节触发首段认证(空明文的终段也会被认证)
            val first = try {
                raw.read()
            } catch (e: IOException) {
                throw WrongPasswordException()
            } catch (e: Exception) {
                throw WrongPasswordException()
            }
            val pushback = PushbackInputStream(raw, 1)
            if (first >= 0) pushback.unread(first)
            return pushback
        } finally {
            password.fill('\u0000')
        }
    }

    private fun readHeader(header: DataInputStream): Pair<KdfParams, ByteArray> {
        val magic = ByteArray(4)
        try {
            header.readFully(magic)
        } catch (e: EOFException) {
            throw PortableFormatException("truncated header")
        }
        if (!magic.contentEquals(MAGIC)) throw PortableFormatException("bad magic")
        val version = header.readByte()
        if (version != VERSION) throw PortableFormatException("unsupported version $version")
        val params = KdfParams(
            memoryKiB = header.readInt(),
            iterations = header.readInt(),
            parallelism = header.readInt(),
        )
        // 头部参数是文件自带的——恶意 .vlt 可写巨型 memoryKiB 让 Argon2 派生即 OOM。
        // 上限取 HIGH_SECURITY 的 8 倍(512MiB),远超任何正常导出
        if (params.memoryKiB <= 0 || params.memoryKiB > MAX_KDF_MEMORY_KIB ||
            params.iterations <= 0 || params.iterations > MAX_KDF_ITERATIONS ||
            params.parallelism <= 0 || params.parallelism > MAX_KDF_PARALLELISM
        ) {
            throw PortableFormatException("KDF 参数异常(文件可能被构造用来耗尽内存)")
        }
        val saltLen = header.readUnsignedByte()
        if (saltLen <= 0 || saltLen > 64) throw PortableFormatException("bad salt length")
        val salt = ByteArray(saltLen).also { header.readFully(it) }
        return params to salt
    }

    // ---------- 便捷一次性管线 ----------

    fun encryptTo(password: CharArray, input: InputStream, output: OutputStream, params: KdfParams = KdfParams.DEFAULT) {
        openEncryptingStream(password, output, params).use { enc -> input.copyTo(enc) }
    }

    @Throws(WrongPasswordException::class)
    fun decryptTo(password: CharArray, input: InputStream, output: OutputStream) {
        openDecryptingStream(password, input).use { dec -> dec.copyTo(output) }
    }

    /** 测试/小数据用:整段内存加解密。 */
    fun encryptBlock(password: CharArray, plain: ByteArray, params: KdfParams = KdfParams.DEFAULT): ByteArray {
        val out = ByteArrayOutputStream()
        encryptTo(password, plain.inputStream(), out, params)
        return out.toByteArray()
    }

    @Throws(WrongPasswordException::class)
    fun decryptBlock(password: CharArray, bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        decryptTo(password, bytes.inputStream(), out)
        return out.toByteArray()
    }
}

/** `.vlt` 头非法/版本不支持(与密码错误区分开:文件根本不是这种格式)。 */
class PortableFormatException(message: String) : Exception(message)
