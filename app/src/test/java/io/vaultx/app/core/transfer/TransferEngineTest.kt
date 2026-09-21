package io.vaultx.app.core.transfer

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultIndex
import io.vaultx.app.core.vault.VaultManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: VaultManager
    private lateinit var engine: TransferEngine
    private lateinit var unlocked: UnlockedVault

    @Before
    fun setUp() {
        manager = VaultManager(tmp.root)
        engine = TransferEngine(manager)
        val meta = manager.createVault("t", "pw1234".toCharArray(), kdfParams = KdfParams.TEST)
        unlocked = manager.unlock(meta.vaultId, "pw1234".toCharArray())
    }

    // ---------------- 内存源/汇 ----------------

    private class MemFile(
        override val name: String,
        val bytes: ByteArray,
        override val mimeType: String? = null,
    ) : TransferEngine.ImportSource {
        override val sizeBytes = bytes.size.toLong()
        override val isDirectory = false
        override fun open(): InputStream = ByteArrayInputStream(bytes)
        override fun children() = emptyList<TransferEngine.ImportSource>()
    }

    private class MemDir(
        override val name: String,
        private val kids: List<TransferEngine.ImportSource>,
    ) : TransferEngine.ImportSource {
        override val sizeBytes = 0L
        override val mimeType = null
        override val isDirectory = true
        override fun open(): InputStream = throw UnsupportedOperationException()
        override fun children() = kids
    }

    private class MemSink(val path: String, val files: MutableMap<String, ByteArray>) : TransferEngine.ExportSink {
        private val buf = ByteArrayOutputStream()
        var committed = false
        var aborted = false
        override val output: OutputStream = buf
        override fun commit() {
            committed = true
            files[path] = buf.toByteArray()
        }
        override fun abort() {
            aborted = true
        }
    }

    private class MemSinkFactory : TransferEngine.ExportSinkFactory {
        val files = LinkedHashMap<String, ByteArray>()
        val sinks = mutableListOf<MemSink>()
        val dirs = mutableListOf<String>()
        var failOn: String? = null
        override fun create(relPath: String, mimeType: String?): TransferEngine.ExportSink {
            if (relPath == failOn) throw java.io.IOException("injected create failure")
            return MemSink(relPath, files).also { sinks += it }
        }
        override fun ensureDir(relPath: String) {
            dirs += relPath
        }
    }

    // ---------------- 导入 ----------------

    @Test
    fun importFileEncryptsAndIndexes() {
        val index = VaultIndex()
        val data = "hello vault".toByteArray()
        val r = engine.import(unlocked, listOf(MemFile("doc.txt", data, "text/plain")), null, index)
        assertEquals(1, r.imported)
        assertEquals(1, index.entries.size)
        val entry = index.entries[0]
        assertEquals("doc.txt", entry.name)
        assertEquals(MediaKind.OTHER, entry.kind)
        // 落盘是密文,不是明文
        val blob = manager.blobFile(unlocked.vaultId, entry.blobId!!)
        assertFalse(String(blob.readBytes()).contains("hello vault"))
        // 解密回来字节一致
        manager.openBlobStream(unlocked, entry.blobId!!).use { dec ->
            assertArrayEquals(data, dec.readBytes())
        }
    }

    @Test
    fun importDirectoryPreservesStructure() {
        val index = VaultIndex()
        val dir = MemDir("photos", listOf(
            MemFile("a.jpg", byteArrayOf(1, 2, 3), "image/jpeg"),
            MemDir("inner", listOf(MemFile("b.mp4", byteArrayOf(9), "video/mp4"))),
        ))
        engine.import(unlocked, listOf(dir), null, index)
        val folder = index.entries.first { it.isFolder }
        assertEquals("photos", folder.name)
        val inner = index.entries.first { it.isFolder && it.name == "inner" }
        val innerFile = index.childrenOf(inner.id).single()
        assertEquals("b.mp4", innerFile.name)
        assertEquals(MediaKind.VIDEO, innerFile.kind)
        assertEquals(inner.id, innerFile.parentId)
    }

    @Test
    fun importNameConflictGetsSuffix() {
        val index = VaultIndex()
        engine.import(unlocked, listOf(MemFile("a.jpg", byteArrayOf(1))), null, index)
        engine.import(unlocked, listOf(MemFile("a.jpg", byteArrayOf(2))), null, index)
        engine.import(unlocked, listOf(MemFile("a.jpg", byteArrayOf(3))), null, index)
        assertEquals(listOf("a.jpg", "a (2).jpg", "a (3).jpg"), index.entries.map { it.name })
    }

    @Test
    fun importCancelCleansBlobsAndIndex() {
        val index = VaultIndex()
        var calls = 0
        try {
            engine.import(
                unlocked,
                listOf(MemFile("x.bin", ByteArray(200_000) { 7 })),
                null,
                index,
                isCancelled = { ++calls > 1 },
            )
            fail("expected cancel")
        } catch (_: TransferEngine.TransferCancelledException) {
        }
        // 索引不留条目,blobs 目录不留半截文件
        assertTrue(index.entries.isEmpty())
        val blobs = File(manager.vaultDir(unlocked.vaultId), VaultManager.BLOBS_DIR)
        assertEquals(0, blobs.walkTopDown().count { it.isFile })
    }

    @Test
    fun importCancelKeepsCompletedEntries() {
        val index = VaultIndex()
        var done = 0
        var checkpoints = 0
        try {
            engine.import(
                unlocked,
                listOf(MemFile("a.txt", "1".toByteArray()), MemFile("b.txt", "2".toByteArray())),
                null,
                index,
                isCancelled = { done >= 1 },
                onProgress = { d, _ -> done = d },
                onCheckpoint = { checkpoints++ },
            )
            fail("expected cancel")
        } catch (e: TransferEngine.TransferCancelledException) {
            assertEquals(1, e.completed)
        }
        // 已完成的条目不随取消回滚:blob 在、索引条目在,终存检查点已触发
        assertEquals(1, index.entries.size)
        assertTrue(manager.blobExists(unlocked.vaultId, index.entries[0].blobId!!))
        assertTrue(checkpoints >= 1)
    }

    @Test
    fun importRejectsConcurrentStart() {
        val index = VaultIndex()
        // 手动占住 active
        val engine2 = TransferEngine(manager)
        val src = MemFile("a.bin", byteArrayOf(1))
        // 在 import 内部再 import —— 用 isCancelled 回调里重入
        var innerError: Throwable? = null
        engine2.import(unlocked, listOf(src), null, index, isCancelled = {
            innerError = runCatching {
                engine2.import(unlocked, listOf(MemFile("b.bin", byteArrayOf(2))), null, index)
            }.exceptionOrNull()
            false
        })
        assertTrue(innerError is IllegalStateException)
    }

    // ---------------- 导出 ----------------

    @Test
    fun exportRoundtripByteExact() {
        val index = VaultIndex()
        val data = ByteArray(150_000) { (it % 251).toByte() }
        engine.import(unlocked, listOf(MemFile("big.bin", data)), null, index)
        val factory = MemSinkFactory()
        val res = engine.exportEntries(unlocked, index.entries.toList(), index, factory)
        assertEquals(1, res.exported)
        assertEquals(0, res.failed)
        assertArrayEquals(data, factory.files["big.bin"])
    }

    @Test
    fun exportFolderReconstructsPaths() {
        val index = VaultIndex()
        val dir = MemDir("docs", listOf(MemFile("readme.md", "hi".toByteArray())))
        engine.import(unlocked, listOf(dir), null, index)
        val factory = MemSinkFactory()
        engine.exportEntries(unlocked, index.entries.toList(), index, factory)
        assertArrayEquals("hi".toByteArray(), factory.files["docs/readme.md"])
    }

    @Test
    fun exportCollectsFailuresAndKeepsGoing() {
        val index = VaultIndex()
        engine.import(unlocked, listOf(
            MemFile("ok.txt", "ok".toByteArray()),
            MemFile("bad.txt", "bad".toByteArray()),
        ), null, index)
        // 删掉第二个的 blob → 该条目导出失败但不打断
        manager.deleteBlob(unlocked.vaultId, index.entries[1].blobId!!)
        val factory = MemSinkFactory()
        val res = engine.exportEntries(unlocked, index.entries.toList(), index, factory)
        assertEquals(1, res.exported)
        assertEquals(listOf("bad.txt"), res.failedNames)
        assertTrue(factory.files.containsKey("ok.txt"))
    }

    @Test
    fun exportAbortCleansHalfWrittenSink() {
        val index = VaultIndex()
        engine.import(unlocked, listOf(MemFile("x.txt", ByteArray(300_000) { 1 })), null, index)
        val factory = MemSinkFactory()
        var calls = 0
        try {
            engine.exportEntries(unlocked, index.entries.toList(), index, factory, isCancelled = { ++calls > 2 })
            fail()
        } catch (_: TransferEngine.TransferCancelledException) {
        }
        // 半成品 sink 被 abort 且未进 files
        assertTrue(factory.sinks.isEmpty() || factory.sinks.all { it.aborted || it.committed })
        assertFalse(factory.files.values.any { it.size == 300_000 })
    }

    @Test
    fun exportSkipsFolderEntries() {
        val index = VaultIndex()
        index.addEntry("emptydir", MediaKind.FOLDER)
        val factory = MemSinkFactory()
        val res = engine.exportEntries(unlocked, index.entries.toList(), index, factory)
        assertEquals(0, res.exported)
        assertEquals(0, res.failed)
    }

    @Test
    fun exportEmptyFolderCreatesDirectory() {
        val index = VaultIndex()
        index.addEntry("emptydir", MediaKind.FOLDER)
        index.addEntry("nested", MediaKind.FOLDER, parentId = index.entries[0].id)
        val factory = MemSinkFactory()
        engine.exportEntries(unlocked, index.entries.toList(), index, factory)
        // 空文件夹也落地为目录,目录结构不丢
        assertEquals(listOf("emptydir", "emptydir/nested"), factory.dirs)
    }

    @Test
    fun exportReportsProgress() {
        val index = VaultIndex()
        engine.import(unlocked, listOf(MemFile("a.txt", "1".toByteArray())), null, index)
        val seen = mutableListOf<Pair<Int, Int>>()
        engine.exportEntries(
            unlocked, index.entries.toList(), index, MemSinkFactory(),
            onProgress = { d, t -> seen += d to t },
        )
        assertTrue(seen.isNotEmpty())
        assertEquals(1, seen.last().second)
        assertEquals(1, seen.last().first)
    }
}
