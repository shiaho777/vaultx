package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.KdfParams
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `meta.vault` —— 库的自描述头(明文落盘)。
 *
 * 明文是刻意的:root 持有者本就能看到文件存在;真正的秘密(VMK)被 KEK 包裹。
 * 配置诱骗库后,同一文件里多一组独立的盐+包裹体+KDF 参数(诱骗链与主链完全解耦)。
 */
@Serializable
data class VaultMeta(
    val vaultId: String,
    val name: String,
    val createdAt: Long,
    /** 主链 Argon2id 参数 */
    val kdfMemoryKiB: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    /** 主链盐与包裹体(base64) */
    val salt: String,
    val wrappedVmk: String,
    /** 总密码开关:开 = 进库要密码且索引加密 */
    val masterGate: Boolean = false,
    /** 索引当前落盘形态(与 masterGate 联动) */
    val indexEncrypted: Boolean = false,
    /** 诱骗链(可选):独立盐 + 独立包裹体 + 独立 KDF 参数 */
    val saltDecoy: String? = null,
    val wrappedVmkDecoy: String? = null,
    val kdfDecoyMemoryKiB: Int = 0,
    val kdfDecoyIterations: Int = 0,
    val kdfDecoyParallelism: Int = 0,
) {

    val hasDecoy: Boolean get() = saltDecoy != null && wrappedVmkDecoy != null

    /**
     * 主链参数。meta 明文可被构造(恶意 .fvault 归档):巨型 memoryKiB 会让
     * Argon2id 派生即 OOM——钳到上限内,派生出的错误密钥自然过不了 unwrap。
     */
    val kdfParams: KdfParams
        get() = KdfParams(
            kdfMemoryKiB.coerceIn(1, MAX_KDF_MEMORY_KIB),
            kdfIterations.coerceIn(1, MAX_KDF_ITERATIONS),
            kdfParallelism.coerceIn(1, MAX_KDF_PARALLELISM),
        )

    val kdfDecoyParams: KdfParams
        get() = if (kdfDecoyMemoryKiB > 0) {
            KdfParams(
                kdfDecoyMemoryKiB.coerceIn(1, MAX_KDF_MEMORY_KIB),
                kdfDecoyIterations.coerceIn(1, MAX_KDF_ITERATIONS),
                kdfDecoyParallelism.coerceIn(1, MAX_KDF_PARALLELISM),
            )
        } else {
            // 旧格式兼容:没记录诱骗参数时与主链同档
            kdfParams
        }

    fun saltBytes(): ByteArray = Base64.getDecoder().decode(salt)
    fun wrappedVmkBytes(): ByteArray = Base64.getDecoder().decode(wrappedVmk)
    fun saltDecoyBytes(): ByteArray? = saltDecoy?.let { Base64.getDecoder().decode(it) }
    fun wrappedVmkDecoyBytes(): ByteArray? = wrappedVmkDecoy?.let { Base64.getDecoder().decode(it) }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FILE_NAME = "meta.vault"

        // KDF 参数钳制上限(与 PortableCipher 同口径):512MiB/64 次/8 路
        private const val MAX_KDF_MEMORY_KIB = 512 * 1024
        private const val MAX_KDF_ITERATIONS = 64
        private const val MAX_KDF_PARALLELISM = 8

        private val json = Json { ignoreUnknownKeys = true }
        private val enc = Base64.getEncoder()

        fun newMeta(
            vaultId: String,
            name: String,
            createdAt: Long,
            kdfParams: KdfParams,
            salt: ByteArray,
            wrappedVmk: ByteArray,
            masterGate: Boolean,
        ): VaultMeta = VaultMeta(
            vaultId = vaultId,
            name = name,
            createdAt = createdAt,
            kdfMemoryKiB = kdfParams.memoryKiB,
            kdfIterations = kdfParams.iterations,
            kdfParallelism = kdfParams.parallelism,
            salt = enc.encodeToString(salt),
            wrappedVmk = enc.encodeToString(wrappedVmk),
            masterGate = masterGate,
            indexEncrypted = masterGate,
        )

        fun fromJson(text: String): VaultMeta = json.decodeFromString(serializer(), text)
    }
}
