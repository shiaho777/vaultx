package io.vaultx.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.vaultx.app.core.media.VaultImageFetcher
import io.vaultx.app.core.media.VaultImageKeyer

class VaultXApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.init()
    }

    /**
     * Coil 单例:注册加密图片源——解密发生在 Fetcher,UI 层无感知。
     * 磁盘缓存显式关闭:解密后的明文位图绝不能落盘;内存缓存限 20% 防 OOM。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(VaultImageKeyer())
                add(VaultImageFetcher.Factory(container))
            }
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache { null }
            .build()
}
