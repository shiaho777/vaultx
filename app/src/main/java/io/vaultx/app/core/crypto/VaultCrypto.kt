package io.vaultx.app.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.Arrays

/**
 * 一个已解锁库的加密上下文:持有 VMK,提供内容加解密。
 * 关联数据(AD)把密文绑定到具体的库与 blob,防止跨库/跨文件挪移密文。
 */
class VaultCrypto internal constructor(
    /** 主密钥,只在本类生命周期内存在于内存,锁定时 [zeroize]。 */
    val vmk: ByteArray,
) {

    private val streamingAead = TinkStreaming.fromRawKey(vmk)

    fun encryptingStream(out: OutputStream, associatedData: ByteArray): OutputStream =
        streamingAead.newEncryptingStream(out, associatedData)

    fun decryptingStream(input: InputStream, associatedData: ByteArray): InputStream =
        streamingAead.newDecryptingStream(input, associatedData)

    /** 可随机定位的解密通道,供 ExoPlayer 按需拉取。 */
    fun seekableDecryptingChannel(
        channel: SeekableByteChannel,
        associatedData: ByteArray,
    ): SeekableByteChannel = streamingAead.newSeekableDecryptingChannel(channel, associatedData)

    /** 小块数据(索引、缩略图)的一次性加解密,复用同一流式格式。 */
    fun encryptBlock(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        encryptingStream(out, associatedData).use { it.write(plain) }
        return out.toByteArray()
    }

    fun decryptBlock(bytes: ByteArray, associatedData: ByteArray): ByteArray =
        decryptingStream(ByteArrayInputStream(bytes), associatedData).use { it.readBytes() }

    /** 尽力擦除内存中的密钥材料(JVM 不做绝对保证,尽人事)。 */
    fun zeroize() {
        Arrays.fill(vmk, 0.toByte())
    }

    companion object {
        private val random = SecureRandom()

        fun generateVmk(): ByteArray = ByteArray(32).also(random::nextBytes)

        /** AD 约定:绑定库 + blob + 用途,任何挪移都会让 AEAD 认证失败。 */
        fun blobAd(vaultId: String, blobId: String): ByteArray =
            "vaultx-blob-v1|$vaultId|$blobId".toByteArray(Charsets.UTF_8)

        fun thumbAd(vaultId: String, blobId: String): ByteArray =
            "vaultx-thumb-v1|$vaultId|$blobId".toByteArray(Charsets.UTF_8)

        fun indexAd(vaultId: String): ByteArray =
            "vaultx-index-v1|$vaultId".toByteArray(Charsets.UTF_8)

        /** 诱骗 compartment 的索引 AD:与真索引同 vaultId 但用途不同,密钥也不同(独立 VMK)。 */
        fun decoyIndexAd(vaultId: String): ByteArray =
            "vaultx-index-decoy-v1|$vaultId".toByteArray(Charsets.UTF_8)
    }
}
