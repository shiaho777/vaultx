package io.vaultx.app.core.media

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.vaultx.app.AppContainer
import okio.FileSystem
import okio.buffer
import okio.source

/** Coil 的 model:指向库里一个加密 blob。 */
data class VaultImageRef(
    val vaultId: String,
    val blobId: String,
    /** 列表网格优先用加密缩略图,查看器用原图。 */
    val preferThumb: Boolean = false,
)

/** 缓存键:同 blob 同档位命中同一缓存项;锁定后缓存整体清(AppContainer)。 */
class VaultImageKeyer : Keyer<VaultImageRef> {
    override fun key(data: VaultImageRef, options: Options): String =
        "vaultx:${data.vaultId}:${data.blobId}:${data.preferThumb}"
}

/**
 * 解密图片源:UI 层完全不感知加密——解密发生在 Fetcher 内部。
 * 库未解锁时返回 null(Coil 落到 error 占位)。
 */
class VaultImageFetcher(
    private val container: AppContainer,
    private val ref: VaultImageRef,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val unlocked = container.session.get(ref.vaultId) ?: return null
        if (ref.preferThumb) {
            container.vaultManager.readThumb(unlocked, ref.blobId)?.let { bytes ->
                return SourceFetchResult(
                    source = ImageSource(bytes.inputStream().source().buffer(), FileSystem.SYSTEM),
                    mimeType = "image/jpeg",
                    dataSource = DataSource.DISK,
                )
            }
        }
        val stream = container.vaultManager.openBlobStream(unlocked, ref.blobId)
        return SourceFetchResult(
            source = ImageSource(stream.source().buffer(), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val container: AppContainer) : Fetcher.Factory<VaultImageRef> {
        override fun create(data: VaultImageRef, options: Options, imageLoader: ImageLoader): Fetcher =
            VaultImageFetcher(container, data)
    }
}
