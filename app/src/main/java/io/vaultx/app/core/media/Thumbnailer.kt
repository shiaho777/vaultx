package io.vaultx.app.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultManager
import java.io.ByteArrayOutputStream

/**
 * 加密缩略图:从解密流/通道取一帧(图)或抽帧(音视频),JPEG 后由
 * [VaultManager.writeThumb] 加密落到 thumbs/<blobId>.vlt。
 */
class Thumbnailer(private val vaultManager: VaultManager) {

    private val maxEdge = 512

    /** 生成缩略图 bytes;不支持的类型返回 null。 */
    fun generate(unlocked: UnlockedVault, blobId: String, kind: MediaKind): ByteArray? = when (kind) {
        MediaKind.IMAGE -> imageThumb(unlocked, blobId)
        MediaKind.VIDEO, MediaKind.AUDIO -> mediaThumb(unlocked, blobId)
        else -> null
    }

    private fun imageThumb(unlocked: UnlockedVault, blobId: String): ByteArray? = runCatching {
        vaultManager.openBlobStream(unlocked, blobId).use { dec ->
            val bmp = BitmapFactory.decodeStream(dec) ?: return null
            val scaled = scaleDown(bmp)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            if (scaled != bmp) bmp.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    private fun mediaThumb(unlocked: UnlockedVault, blobId: String): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(VaultMediaDataSource(vaultManager, unlocked, blobId))
            retriever.frameAtTime?.let { frame ->
                val scaled = scaleDown(frame)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
                out.toByteArray()
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun scaleDown(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val long = maxOf(w, h)
        if (long <= maxEdge) return bmp
        val scale = maxEdge.toFloat() / long
        return Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
    }
}

/** 把可随机定位的解密通道适配给 MediaMetadataRetriever。 */
private class VaultMediaDataSource(
    private val vaultManager: VaultManager,
    private val unlocked: UnlockedVault,
    private val blobId: String,
) : MediaDataSource() {

    private val channel by lazy { vaultManager.openBlobChannel(unlocked, blobId) }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        channel.position(position)
        val buf = java.nio.ByteBuffer.wrap(buffer, offset, size)
        return channel.read(buf)
    }

    override fun getSize(): Long = channel.size()

    override fun close() {
        channel.close()
    }
}
