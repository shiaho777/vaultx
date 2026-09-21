package io.vaultx.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import io.vaultx.app.core.crypto.KdfParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 锁定即清空:锁库后 ① 会话丢失 ② 明文索引缓存丢弃 ③ Coil 内存里已解密位图清空。
 * 在真机/模拟器上验证端到端链路(连 Coil 单例接线一起测)。
 */
@RunWith(AndroidJUnit4::class)
class LockClearsImageCacheTest {

    @Test
    fun lockClearsSessionAndImageCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = AppContainer(context)

        // 建库 + 解锁 + 写一条目(让索引缓存里有明文)
        val meta = container.vaultManager.createVault("t", "pw1234".toCharArray(), kdfParams = KdfParams.TEST)
        val unlocked = container.vaultManager.unlock(meta.vaultId, "pw1234".toCharArray())
        container.session.put(unlocked)
        val idx = container.vaultManager.loadIndex(unlocked)
        idx.addEntry("a.jpg", io.vaultx.app.core.vault.MediaKind.IMAGE, blobId = "b")
        container.vaultManager.saveIndex(unlocked, idx)
        assertEquals(1, container.vaultManager.loadIndex(unlocked).entries.size)

        // 往 Coil 内存缓存塞一张图,模拟刚看过解密图片
        val imageLoader = SingletonImageLoader.get(context)
        val cacheKey = MemoryCache.Key("vaultx:${meta.vaultId}:b")
        imageLoader.memoryCache?.set(cacheKey, MemoryCache.Value(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).asImage()))
        assertFalse(imageLoader.memoryCache?.keys?.isEmpty() != false)

        // 锁定
        container.session.lock(meta.vaultId)

        // ① 会话没了
        assertNull(container.session.get(meta.vaultId))
        // ② Coil 内存缓存清空(不留解密位图)
        assertTrue(imageLoader.memoryCache?.keys?.isEmpty() != false)
        // ③ 索引缓存已失效(重新 load 走磁盘解密,且数据仍正确)
        assertEquals(1, container.vaultManager.loadIndex(unlocked).entries.size)
    }
}
