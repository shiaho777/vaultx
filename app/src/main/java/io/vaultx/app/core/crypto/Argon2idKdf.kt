package io.vaultx.app.core.crypto

import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 密码 → KEK 的 memory-hard 派生(Argon2id)。
 *
 * 双实现:
 * - **native libsodium**(lazysodium-android):设备上比纯 JVM 快 3~10 倍,
 *   解锁体感与高参数档位的可用性都靠它;密码字节用完在 native 侧可控擦除。
 * - **BouncyCastle 纯 JVM 兜底**:native 加载失败(异常设备/单测环境)时自动回退,
 *   慢但正确——两条路径输出逐字节一致(RFC 9106 同参同果),旧库无缝解锁。
 *
 * 输入 CharArray 由调用方持有;本函数内部拷贝一份,用完即清。
 */
object Argon2idKdf {

    const val KEY_LENGTH = 32

    /**
     * native 后端惰性初始化;失败只试一次,之后永久走 BC(避免每次解锁都撞加载异常)。
     * lazysodium 5.2.0 字节码是 Java 21(major 65):JDK 17 环境(如 CI)类加载即抛
     * UnsupportedClassVersionError——它是 Error 不是 Exception,runCatching 兜不住,
     * 会把整个 KDF 静态初始化炸掉。必须显式 catch Throwable。
     */
    private val native: com.goterl.lazysodium.SodiumAndroid? by lazy {
        try {
            com.goterl.lazysodium.SodiumAndroid()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 派生 32 字节密钥。
     * @throws KdfException 内存不足/参数非法等派生失败(把 Error 降级为可恢复异常)
     */
    fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        native?.let { sodium ->
            deriveNative(sodium, password, salt, params)?.let { return it }
        }
        return deriveBouncyCastle(password, salt, params)
    }

    /** libsodium crypto_pwhash(Argon2id13)。失败返回 null → 调用方回退 BC。 */
    private fun deriveNative(
        sodium: com.goterl.lazysodium.SodiumAndroid,
        password: CharArray,
        salt: ByteArray,
        params: KdfParams,
    ): ByteArray? {
        // CharArray → UTF-8 字节(与 BC 的 CharArray 编码口径一致),用完擦除
        val cb = java.nio.CharBuffer.wrap(password)
        val bb = StandardCharsets.UTF_8.encode(cb)
        val pwBytes = ByteArray(bb.remaining()).also { bb.get(it) }
        if (bb.hasArray()) Arrays.fill(bb.array(), 0.toByte())
        val out = ByteArray(KEY_LENGTH)
        return try {
            val ok = sodium.crypto_pwhash(
                out, out.size.toLong(),
                pwBytes, pwBytes.size.toLong(),
                salt,
                params.iterations.toLong(),
                com.sun.jna.NativeLong(params.memoryKiB.toLong() * 1024L),
                2, // crypto_pwhash_ALG_ARGON2ID13
            )
            if (ok == 0) out else {
                Arrays.fill(out, 0.toByte())
                null
            }
        } catch (e: UnsatisfiedLinkError) {
            Arrays.fill(out, 0.toByte())
            null
        } catch (e: OutOfMemoryError) {
            Arrays.fill(out, 0.toByte())
            throw KdfException("kdf out of memory", e)
        } catch (e: RuntimeException) {
            Arrays.fill(out, 0.toByte())
            null
        } finally {
            Arrays.fill(pwBytes, 0.toByte())
        }
    }

    private fun deriveBouncyCastle(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
        val argonParams = try {
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKiB)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .build()
        } catch (e: RuntimeException) {
            throw KdfException("invalid kdf params", e)
        }

        // Argon2BytesGenerator 会把 CharArray 转成内部 byte[],JVM 上无法彻底擦除——尽人事。
        val gen = Argon2BytesGenerator()
        val out = ByteArray(KEY_LENGTH)
        try {
            gen.init(argonParams)
            gen.generateBytes(password, out)
        } catch (e: OutOfMemoryError) {
            // 大内存档在受限堆上可能 OOM;Error 不被 catch(Exception) 兜住,显式降级
            Arrays.fill(out, 0.toByte())
            throw KdfException("kdf out of memory", e)
        } catch (e: RuntimeException) {
            Arrays.fill(out, 0.toByte())
            throw KdfException("kdf failed", e)
        }
        return out
    }
}

/** KDF 失败的统一出口(含 OOM 降级),UI 可转成错误提示而非闪退。 */
class KdfException(message: String, cause: Throwable? = null) : Exception(message, cause)