package io.vaultx.app.core.crypto

import java.util.Arrays
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 密码 → KEK 的 memory-hard 派生(Argon2id / BouncyCastle,纯 JVM 可测)。
 *
 * 输入 CharArray 由调用方持有;本函数内部拷贝一份,用完即清。
 */
object Argon2idKdf {

    const val KEY_LENGTH = 32

    /**
     * 派生 32 字节密钥。
     * @throws KdfException 内存不足/参数非法等派生失败(把 Error 降级为可恢复异常)
     */
    fun derive(password: CharArray, salt: ByteArray, params: KdfParams): ByteArray {
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
