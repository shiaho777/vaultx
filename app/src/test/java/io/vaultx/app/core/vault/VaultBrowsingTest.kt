package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.KdfParams
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 索引浏览语义:搜索/子目录/级联集/磁盘占用。 */
class VaultBrowsingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: VaultManager

    @Before
    fun setUp() {
        manager = VaultManager(tmp.root)
    }

    private fun pw(s: String) = s.toCharArray()

    @Test
    fun searchIsCaseInsensitiveAndIncludesFolders() {
        val idx = VaultIndex().apply {
            addEntry("Photos", MediaKind.FOLDER)
            addEntry("IMG_0001.JPG", MediaKind.IMAGE, blobId = "a")
            addEntry("img_0002.png", MediaKind.IMAGE, blobId = "b")
            addEntry("notes.txt", MediaKind.OTHER, blobId = "c")
        }
        val hits = idx.search("img")
        assertEquals(2, hits.size)
        // 文件夹也命中(点击可直接进入);大小写不敏感
        assertEquals(1, idx.search("PHOTOS").size)
        assertTrue(idx.search("PHOTOS")[0].isFolder)
        assertTrue(idx.search("  ").isEmpty())
        assertTrue(idx.search("").isEmpty())
    }

    @Test
    fun childrenOfPartitionsByParent() {
        val idx = VaultIndex()
        val folder = idx.addEntry("d", MediaKind.FOLDER)
        idx.addEntry("root.txt", MediaKind.OTHER)
        idx.addEntry("in.txt", MediaKind.OTHER, parentId = folder.id)
        assertEquals(2, idx.childrenOf(null).size)
        assertEquals(1, idx.childrenOf(folder.id).size)
        assertEquals("in.txt", idx.childrenOf(folder.id)[0].name)
    }

    @Test
    fun descendantIdsWalksNestedFolders() {
        val idx = VaultIndex()
        val a = idx.addEntry("a", MediaKind.FOLDER)
        val b = idx.addEntry("b", MediaKind.FOLDER, parentId = a.id)
        val f = idx.addEntry("f.txt", MediaKind.OTHER, parentId = b.id)
        assertEquals(setOf(b.id, f.id), idx.descendantIds(a.id))
        assertEquals(setOf(f.id), idx.descendantIds(b.id))
        assertTrue(idx.descendantIds(f.id).isEmpty())
    }

    @Test
    fun jsonRoundtripPreservesEntries() {
        val idx = VaultIndex().apply {
            addEntry("a.jpg", MediaKind.IMAGE, blobId = "blob-1", sizeBytes = 42, mimeType = "image/jpeg", hasThumb = true)
            addEntry("dir", MediaKind.FOLDER)
        }
        val back = VaultIndex.fromJson(idx.toJson())
        assertEquals(2, back.entries.size)
        assertEquals("blob-1", back.entries[0].blobId)
        assertEquals(42, back.entries[0].sizeBytes)
        assertTrue(back.entries[0].hasThumb)
        assertTrue(back.entries[1].isFolder)
    }

    @Test
    fun vaultDiskUsageSumsFiles() {
        val meta = manager.createVault("d", pw("pw1234"), kdfParams = KdfParams.TEST)
        val before = manager.vaultDiskUsage(meta.vaultId)
        assertTrue(before > 0)
        val blob = manager.blobFile(meta.vaultId, "b1")
        blob.parentFile?.mkdirs()
        blob.writeBytes(ByteArray(1000))
        assertEquals(before + 1000, manager.vaultDiskUsage(meta.vaultId))
    }

    @Test
    fun loadPlainIndexReturnsNullWhenEncrypted() {
        val meta = manager.createVault("e", pw("pw1234"), masterGate = true, kdfParams = KdfParams.TEST)
        assertNull(manager.loadPlainIndex(meta.vaultId))
        // 明文模式则有
        val meta2 = manager.createVault("p", pw("pw1234"), masterGate = false, kdfParams = KdfParams.TEST)
        assertEquals(0, manager.loadPlainIndex(meta2.vaultId)?.entries?.size)
    }
}
