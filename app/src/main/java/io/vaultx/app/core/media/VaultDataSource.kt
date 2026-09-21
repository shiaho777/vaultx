package io.vaultx.app.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultManager
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/**
 * ExoPlayer 的解密 DataSource:底层是 Tink 的可随机定位解密通道,
 * 拖进度条时按需 seek 拉密文段解密——不用整段落地。
 *
 * [lengthBytes] 为明文长度(索引里的 sizeBytes)。
 */
class VaultDataSource(
    private val unlocked: UnlockedVault,
    private val vaultManager: VaultManager,
    private val blobId: String,
    private val lengthBytes: Long,
) : DataSource {

    private var channel: SeekableByteChannel? = null
    private var bytesRemaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val ch = vaultManager.openBlobChannel(unlocked, blobId)
        channel = ch
        ch.position(dataSpec.position)
        bytesRemaining = (lengthBytes - dataSpec.position).coerceAtLeast(0)
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

    override fun getUri(): Uri = Uri.parse("vaultx://${unlocked.vaultId}/$blobId")

    override fun close() {
        channel?.close()
        channel = null
    }

    override fun addTransferListener(transferListener: TransferListener) {}
    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    /** 播放器工厂:一个媒体条目一个实例。 */
    class Factory(
        private val unlocked: UnlockedVault,
        private val vaultManager: VaultManager,
        private val blobId: String,
        private val lengthBytes: Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            VaultDataSource(unlocked, vaultManager, blobId, lengthBytes)
    }
}
