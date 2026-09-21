package io.vaultx.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.VaultIndex
import io.vaultx.app.core.vault.VaultManager
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 索引事务一致性:加密索引被篡改时必须抛错而不是静默返回垃圾;
 * 全新 VaultManager 实例(模拟进程重启)读盘结果一致。
 */
@RunWith(AndroidJUnit4::class)
class VaultIndexTransactionTest {

    private fun filesDir() = InstrumentationRegistry.getInstrumentation().targetContext
        .getDir("vaultx-it-${System.nanoTime()}", 0)

    @Test
    fun tamperedEncryptedIndexIsRejected() {
        val dir = filesDir()
        val manager = VaultManager(dir)
        val meta = manager.createVault("t", "pw1234".toCharArray(), masterGate = true, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, "pw1234".toCharArray())
        manager.saveIndex(unlocked, VaultIndex().apply { addEntry("x.txt", MediaKind.OTHER, blobId = "b") })

        // 破坏 index.enc 的一个字节(AEAD 认证必败)
        val enc = File(manager.vaultDir(meta.vaultId), VaultManager.INDEX_ENC)
        val bytes = enc.readBytes()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        enc.writeBytes(bytes)

        // 绕开缓存直读磁盘
        manager.onVaultLocked(meta.vaultId)
        assertThrows(Exception::class.java) {
            manager.loadIndex(unlocked)
        }
    }

    @Test
    fun indexConsistentAcrossFreshManagerInstance() {
        val dir = filesDir()
        val m1 = VaultManager(dir)
        val meta = m1.createVault("t", "pw1234".toCharArray(), masterGate = true, kdfParams = KdfParams.TEST)
        val u1 = m1.unlock(meta.vaultId, "pw1234".toCharArray())
        m1.saveIndex(u1, VaultIndex().apply {
            addEntry("a.jpg", MediaKind.IMAGE, blobId = "b1", sizeBytes = 42)
            addEntry("dir", MediaKind.FOLDER)
        })

        // 模拟进程重启:新实例读同一目录
        val m2 = VaultManager(dir)
        val u2 = m2.unlock(meta.vaultId, "pw1234".toCharArray())
        val loaded = m2.loadIndex(u2)
        assertEquals(2, loaded.entries.size)
        assertEquals("a.jpg", loaded.entries[0].name)
        assertEquals(42, loaded.entries[0].sizeBytes)
        assertTrue(loaded.entries[1].isFolder)
    }

    @Test
    fun saveIndexRollbackKeepsLastGood() {
        val dir = filesDir()
        val manager = VaultManager(dir)
        val meta = manager.createVault("t", "pw1234".toCharArray(), masterGate = false, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, "pw1234".toCharArray())
        val good = VaultIndex().apply { addEntry("good.txt", MediaKind.OTHER) }
        manager.saveIndex(unlocked, good)

        // 污染 index.dat 为非法 JSON → 缓存命中时仍拿到好数据
        val plain = File(manager.vaultDir(meta.vaultId), VaultManager.INDEX_PLAIN)
        plain.writeText("{corrupted")
        assertEquals(1, manager.loadIndex(unlocked).entries.size)
        // 清缓存后读磁盘 → 解析失败直接抛(调用方决定是否兜底)
        manager.onVaultLocked(meta.vaultId)
        assertThrows(Exception::class.java) {
            manager.loadIndex(unlocked)
        }
    }
}
