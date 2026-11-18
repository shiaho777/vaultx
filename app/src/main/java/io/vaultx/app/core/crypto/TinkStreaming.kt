package io.vaultx.app.core.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingKey
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.util.SecretBytes

/**
 * Tink Streaming AEAD(AES-256-GCM-HKDF)的薄封装。
 *
 * - 分块加密:每段独立 AEAD 认证,绑定位置——防截断/重排/追加,可随机 seek。
 * - 密钥直接从 VMK 构造(非 Tink keyset 持久化),VMK 的生命周期由 [VaultCrypto] 管。
 */
object TinkStreaming {

    /** 旧库密文分块大小(Tink 约定含 16B tag,即每段明文 4080B)。读旧数据必须用此值。 */
    const val CIPHERTEXT_SEGMENT_SIZE = 4096

    /**
     * 新库分块 256KB:每段一次 GCM+HKDF 的固定开销摊薄 64 倍,
     * 顺序吞吐与 ExoPlayer 小读放大显著改善;seek 粒度变粗但媒体场景无感。
     * 分块大小记录在 meta.vault(chunkSize),旧库无字段 → 按 4096 读,永不强制迁移。
     */
    const val NEW_VAULT_SEGMENT_SIZE = 256 * 1024

    @Volatile
    private var registered = false

    /** 幂等注册 StreamingAead 原语;应用启动与单测都会调。 */
    fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            StreamingAeadConfig.register()
            registered = true
        }
    }

    /** 从裸 32 字节密钥构造单密钥 keyset 的 StreamingAead(无前缀变体)。分块大小必须与写入时一致。 */
    fun fromRawKey(vmk: ByteArray, segmentSize: Int = CIPHERTEXT_SEGMENT_SIZE): StreamingAead {
        ensureRegistered()
        // Tink 约束:段大小 > 头长+tag+1;钳制防恶意 meta 构造非法参数
        val seg = segmentSize.coerceIn(CIPHERTEXT_SEGMENT_SIZE, 16 * 1024 * 1024)
        val params = AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(vmk.size)
            .setDerivedAesGcmKeySizeBytes(32)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(seg)
            .build()
        val key = AesGcmHkdfStreamingKey.create(params, SecretBytes.copyFrom(vmk, InsecureSecretKeyAccess.get()))
        val handle = KeysetHandle.newBuilder()
            .addEntry(
                KeysetHandle.importKey(key)
                    .withFixedId(0x56584c54) // "VXLT"——单密钥,固定 id 即可
                    .setStatus(com.google.crypto.tink.KeyStatus.ENABLED)
                    .makePrimary()
            )
            .build()
        return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }
}
