package io.vaultx.app.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultManager
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * ExoPlayer 的解密 DataSource:底层是 Tink 的可随机定位解密通道,
 * 拖进度条时按需 seek 拉密文段解密——不用整段落地。
 *
 * blobId 在 [open] 时从 `dataSpec.uri` 解析("vaultx://<vaultId>/<blobId>"),
 * 因此一个实例即可服务播放列表(连播),不必每条媒体建一个工厂。
 * [lengthOf] 给明文长度(索引里的 sizeBytes)。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VaultDataSource(
    private val unlocked: UnlockedVault,
    private val vaultManager: VaultManager,
    private val lengthOf: (String) -> Long,
) : DataSource {

    private var channel: SeekableByteChannel? = null
    private var blobId: String? = null
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        // 复用实例连播/重试时可能不经 close 直接再 open:先释放旧通道,防句柄泄漏
        close()
        val id = dataSpec.uri.lastPathSegment
            ?: throw IOException("vaultx uri missing blob id: ${dataSpec.uri}")
        blobId = id
        val len = lengthOf(id)
        val ch = vaultManager.openBlobChannel(unlocked, id)
        channel = ch
        try {
            ch.position(dataSpec.position)
        } catch (e: Throwable) {
            close()
            throw e
        }
        bytesRemaining = (len - dataSpec.position).coerceAtLeast(0)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = minOf(readLength.toLong(), bytesRemaining).toInt()
        val n = channel?.read(ByteBuffer.wrap(buffer, offset, want)) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesRemaining -= n
        return n
    }

    override fun getUri(): Uri = Uri.parse("vaultx://${unlocked.vaultId}/${blobId ?: ""}")

    override fun close() {
        try {
            channel?.close()
        } finally {
            channel = null
            blobId = null
            bytesRemaining = 0
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {}
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    /**
     * 播放器工厂:[sizes] 是 blobId → 明文长度的映射(通常来自索引同目录媒体)。
     * MediaItem 的 uri 携带 blobId,DataSource 在 open 时解析。
     */
    class Factory(
        private val unlocked: UnlockedVault,
        private val vaultManager: VaultManager,
        private val sizes: Map<String, Long>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            VaultDataSource(unlocked, vaultManager) { id ->
                // 缺映射必须显式报错:回落 0 会让 ExoPlayer 拿到"空文件",
                // 表现为无提示的静默播放失败,极难排查
                sizes[id] ?: throw IOException("no plaintext length mapping for blob: $id")
            }
    }
}
