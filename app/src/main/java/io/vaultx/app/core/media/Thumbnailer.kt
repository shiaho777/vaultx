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

    companion object {
        /** 视频抽帧探测点(微秒):1 秒处——0 点常是黑场/片头渐隐帧。 */
        private const val FRAME_PROBE_US = 1_000_000L
    }

    /** 生成缩略图 bytes;不支持的类型返回 null。 */
    fun generate(unlocked: UnlockedVault, blobId: String, kind: MediaKind): ByteArray? = when (kind) {
        MediaKind.IMAGE -> imageThumb(unlocked, blobId)
        MediaKind.VIDEO, MediaKind.AUDIO -> mediaThumb(unlocked, blobId)
        else -> null
    }

    private fun imageThumb(unlocked: UnlockedVault, blobId: String): ByteArray? = runCatching {
        // 两遍流:先读 EXIF 方向 + bounds 定采样率,再按采样解码——
        // 直接 decodeStream 会把全尺寸位图读进内存,亿级像素照片必 OOM
        var orientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        vaultManager.openBlobStream(unlocked, blobId).use { dec ->
            val exif = runCatching { androidx.exifinterface.media.ExifInterface(dec) }.getOrNull()
            orientation = exif?.getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
            ) ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
        }
        vaultManager.openBlobStream(unlocked, blobId).use { dec ->
            BitmapFactory.decodeStream(dec, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val bmp = vaultManager.openBlobStream(unlocked, blobId).use { dec ->
            BitmapFactory.decodeStream(dec, null, opts)
        } ?: return null
        val rotated = rotateForExif(bmp, orientation)
        val scaled = scaleDown(rotated)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        if (scaled != rotated) scaled.recycle()
        if (rotated != bmp) rotated.recycle()
        bmp.recycle()
        out.toByteArray()
    }.getOrNull()

    /** 使最长边 ≤ maxEdge 所需的最小 2 的幂采样率。 */
    private fun sampleSize(w: Int, h: Int, maxEdge: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) > maxEdge) s *= 2
        return s
    }

    private fun rotateForExif(bmp: Bitmap, orientation: Int): Bitmap {
        val m = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return runCatching {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }.getOrDefault(bmp)
    }

    private fun mediaThumb(unlocked: UnlockedVault, blobId: String): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(VaultMediaDataSource(vaultManager, unlocked, blobId))
            // 音频:优先内嵌封面(embeddedPicture);视频:抽 1s 处帧
            // (首帧常是黑场),拿不到再退回首帧
            retriever.embeddedPicture?.let { return@runCatching it }
            val frame = retriever.getFrameAtTime(
                FRAME_PROBE_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: retriever.frameAtTime
            frame?.let {
                val scaled = scaleDown(it)
                if (scaled !== it) it.recycle()
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
    private val lock = Any()

    // MediaDataSource 契约允许并发 readAt:position+read 必须原子,否则定位串扰;
    // 单次 read 允许短读,循环填满或到 EOF
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        synchronized(lock) {
            channel.position(position)
            val buf = java.nio.ByteBuffer.wrap(buffer, offset, size)
            var total = 0
            while (buf.hasRemaining()) {
                val n = channel.read(buf)
                if (n < 0) break
                total += n
            }
            if (total == 0) -1 else total
        }

    override fun getSize(): Long = channel.size()

    override fun close() {
        channel.close()
    }
}
