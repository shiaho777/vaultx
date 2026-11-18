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
    fun trashedEntriesHiddenFromBrowsingAndSearch() {
        val idx = VaultIndex()
        val keep = idx.addEntry("keep.txt", MediaKind.OTHER, blobId = "k")
        val gone = idx.addEntry("gone.txt", MediaKind.OTHER, blobId = "g")
        idx.entries[1] = gone.copy(trashedAt = 1000L)
        assertEquals(listOf("keep.txt"), idx.childrenOf(null).map { it.name })
        assertTrue(idx.search("gone").isEmpty())
        assertEquals(1, idx.search("txt").size)
        // find 仍能找到(回收站界面/恢复需要),但浏览不可见
        assertEquals("gone.txt", idx.find(gone.id)?.name)
    }

    @Test
    fun trashedRootsExcludesTrashedDescendants() {
        val idx = VaultIndex()
        val folder = idx.addEntry("d", MediaKind.FOLDER)
        val child = idx.addEntry("in.txt", MediaKind.OTHER, parentId = folder.id)
        val lone = idx.addEntry("lone.txt", MediaKind.OTHER)
        val now = 5000L
        idx.entries.replaceAll {
            if (it.id in setOf(folder.id, child.id, lone.id)) it.copy(trashedAt = now) else it
        }
        // 文件夹+子文件同批标记 → 只展示文件夹顶层;散文件独立成顶层
        val roots = idx.trashedRoots()
        assertEquals(setOf("d", "lone.txt"), roots.map { it.name }.toSet())
        // 子孙级联集仍完整(恢复/彻底删除用)
        assertEquals(setOf(child.id), idx.descendantIds(folder.id))
    }

    @Test
    fun trashedRootsOrphanParentStillShown() {
        val idx = VaultIndex()
        val e = idx.addEntry("orphan.txt", MediaKind.OTHER, parentId = "missing-parent")
        idx.entries[0] = e.copy(trashedAt = 1L)
        // 父级不存在(损坏/历史数据)→ 仍作为顶层展示,不会凭空消失
        assertEquals(listOf("orphan.txt"), idx.trashedRoots().map { it.name })
    }

    @Test
    fun legacyIndexWithoutTrashFieldReadable() {
        val idx = VaultIndex().apply { addEntry("a.txt", MediaKind.OTHER) }
        val json = idx.toJson().replace(Regex(",?\"trashedAt\":null"), "")
        val back = VaultIndex.fromJson(json)
        assertNull(back.entries[0].trashedAt)
        assertEquals(1, back.childrenOf(null).size)
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
        val meta = manager.createVault("d", pw("pw123456"), kdfParams = KdfParams.TEST)
        val before = manager.vaultDiskUsage(meta.vaultId)
        assertTrue(before > 0)
        val blob = manager.blobFile(meta.vaultId, "b1")
        blob.parentFile?.mkdirs()
        blob.writeBytes(ByteArray(1000))
        assertEquals(before + 1000, manager.vaultDiskUsage(meta.vaultId))
    }

    @Test
    fun starredAndTagsQueriesExcludeTrashed() {
        val idx = VaultIndex()
        val a = idx.addEntry("a.jpg", MediaKind.IMAGE, blobId = "a")
        val b = idx.addEntry("b.jpg", MediaKind.IMAGE, blobId = "b")
        idx.entries[0] = a.copy(starred = true, tags = listOf("work", "urgent"))
        idx.entries[1] = b.copy(starred = true, tags = listOf("work"), trashedAt = 1L)
        // 收藏视图不含回收站条目
        assertEquals(listOf("a.jpg"), idx.starredEntries().map { it.name })
        assertEquals(listOf("a.jpg"), idx.entriesWithTag("work").map { it.name })
        assertEquals(listOf("urgent", "work"), idx.allTags())
        // 搜索命中标签(不只文件名)
        assertEquals(listOf("a.jpg"), idx.search("urgent").map { it.name })
        assertEquals(listOf("a.jpg"), idx.search("WORK").map { it.name })
    }

    @Test
    fun tagNormalizationAndLimits() {
        assertEquals("work stuff", VaultIndex.normalizeTag("  Work   Stuff  "))
        assertNull(VaultIndex.normalizeTag("   "))
        assertNull(VaultIndex.normalizeTag(""))
        assertEquals(VaultIndex.MAX_TAG_LENGTH, VaultIndex.normalizeTag("x".repeat(100))!!.length)
    }

    @Test
    fun starredAndTagsSurviveJsonRoundtripAndLegacyReadable() {
        val idx = VaultIndex()
        val e = idx.addEntry("a.jpg", MediaKind.IMAGE)
        idx.entries[0] = e.copy(starred = true, tags = listOf("work"))
        val back = VaultIndex.fromJson(idx.toJson())
        assertTrue(back.entries[0].starred)
        assertEquals(listOf("work"), back.entries[0].tags)
        // 旧格式(无 starred/tags 字段)仍可读,默认值兜底
        val legacy = idx.toJson()
            .replace(Regex(",?\"starred\":(true|false)"), "")
            .replace(Regex(",?\"tags\":\\[[^]]*]"), "")
        val legacyBack = VaultIndex.fromJson(legacy)
        assertTrue(!legacyBack.entries[0].starred)
        assertTrue(legacyBack.entries[0].tags.isEmpty())
    }

    @Test
    fun loadPlainIndexReturnsNullWhenEncrypted() {
        val meta = manager.createVault("e", pw("pw123456"), masterGate = true, kdfParams = KdfParams.TEST)
        assertNull(manager.loadPlainIndex(meta.vaultId))
        // 明文模式则有
        val meta2 = manager.createVault("p", pw("pw123456"), masterGate = false, kdfParams = KdfParams.TEST)
        assertEquals(0, manager.loadPlainIndex(meta2.vaultId)?.entries?.size)
    }
}
