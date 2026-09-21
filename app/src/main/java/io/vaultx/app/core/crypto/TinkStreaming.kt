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

    /** 密文分块大小(Tink 约定含 16B tag,即每段明文 4080B)。 */
    const val CIPHERTEXT_SEGMENT_SIZE = 4096

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

    /** 从裸 32 字节密钥构造单密钥 keyset 的 StreamingAead(无前缀变体)。 */
    fun fromRawKey(vmk: ByteArray): StreamingAead {
        ensureRegistered()
        val params = AesGcmHkdfStreamingParameters.builder()
            .setKeySizeBytes(vmk.size)
            .setDerivedAesGcmKeySizeBytes(32)
            .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
            .setCiphertextSegmentSizeBytes(CIPHERTEXT_SEGMENT_SIZE)
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
