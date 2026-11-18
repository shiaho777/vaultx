package io.vaultx.app.core.crypto

import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 密码 → KEK 的 memory-hard 派生(Argon2id)。
 *
 * 双实现:
 * - **native libsodium**(lazysodium-android):单 lane(p=1)时比纯 JVM 快数倍。
 * - **BouncyCastle 纯 JVM**:标准 RFC 9106 实现,任意并行度都正确。
 *
 * ⚠ 关键约束(实测裁决,见 CryptoTest.kdfNativeAndBouncyCastleByteIdentical):
 * lazysodium 5.2.0 内嵌的 libsodium 构建**未启用多线程**(无 pthread_create),
 * crypto_pwhash 在 p>1 时静默按 p=1 计算——输出与标准 Argon2id 不同!
 * 因此 **p>1 一律走 BouncyCastle**;native 只服务 p=1(两实现已验证逐字节一致)。
 * 绝不能让"快"破坏"同参同果"——那会让旧库在新版本上解不开。
 *
 * 输入 CharArray 由调用方持有;本函数内部拷贝一份,用完即清。
 */
object Argon2idKdf {

    const val KEY_LENGTH = 32

    /**
     * native 后端惰性初始化;失败只试一次,之后永久走 BC(避免每次解锁都撞加载异常)。
     * lazysodium 5.2.0 字节码是 Java 21(major 65):JDK 17 环境类加载即抛
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
        // p>1 时 native 构建静默退化为 p=1(输出错误),必须走标准实现
        if (params.parallelism == 1) {
            native?.let { sodium ->
                deriveNative(sodium, password, salt, params)?.let { return it }
            }
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