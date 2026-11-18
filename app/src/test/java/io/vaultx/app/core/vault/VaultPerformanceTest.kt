package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.TinkStreaming
import io.vaultx.app.core.crypto.VaultCrypto
import io.vaultx.app.core.transfer.TransferEngine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 阶段2回归:分块版本协商(新库 256KB / 旧库 4096 永久可读)、
 * 新 KDF 档位、导出路径回溯线性化、万级条目基准。
 */
class VaultPerformanceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: VaultManager
    private lateinit var engine: TransferEngine

    @Before
    fun setUp() {
        manager = VaultManager(tmp.root)
        engine = TransferEngine(manager)
    }

    private fun pw(s: String) = s.toCharArray()

    // ---------------- 分块版本协商 ----------------

    @Test
    fun newVaultUsesLargeSegmentsAndRoundtrips() {
        val meta = manager.createVault("big", pw("pw123456"), masterGate = true, kdfParams = KdfParams.TEST)
        assertEquals(TinkStreaming.NEW_VAULT_SEGMENT_SIZE, manager.metaOf(meta.vaultId).chunkSize)
        val u = manager.unlock(meta.vaultId, pw("pw123456"))
        // 跨多个 256KB 段的数据往返
        val data = ByteArray(600 * 1024) { (it % 251).toByte() }
        val blobId = "seg1"
        val sink = manager.prepareBlobSink(meta.vaultId, blobId)
        sink.outputStream().use { raw ->
            u.crypto.encryptingStream(raw, VaultCrypto.blobAd(meta.vaultId, blobId)).use { it.write(data) }
        }
        val back = manager.openBlobStream(u, blobId).use { it.readBytes() }
        assertArrayEquals(data, back)
        // 索引块加密同样走新分块
        val idx = VaultIndex().apply { addEntry("f.bin", MediaKind.OTHER, blobId = blobId, sizeBytes = data.size.toLong()) }
        manager.saveIndex(u, idx)
        assertEquals(1, manager.loadIndex(u).entries.size)
    }

    @Test
    fun legacyVaultWithoutChunkFieldStaysReadable() {
        val meta = manager.createVault("legacy", pw("pw123456"), masterGate = true, kdfParams = KdfParams.TEST)
        val u = manager.unlock(meta.vaultId, pw("pw123456"))
        val blobId = "old1"
        val data = "legacy-content".toByteArray()
        val sink = manager.prepareBlobSink(meta.vaultId, blobId)
        sink.outputStream().use { raw ->
            u.crypto.encryptingStream(raw, VaultCrypto.blobAd(meta.vaultId, blobId)).use { it.write(data) }
        }
        val idx = VaultIndex().apply { addEntry("old.txt", MediaKind.OTHER, blobId = blobId) }
        manager.saveIndex(u, idx)
        u.crypto.zeroize()
        manager.onVaultLocked(meta.vaultId)

        // 模拟 0.1.0 落盘:meta 无 chunkSize 字段(数据按 4096 分块写入)
        val metaFile = File(manager.vaultDir(meta.vaultId), VaultMeta.FILE_NAME)
        val legacyJson = metaFile.readText().replace(Regex(",?\"chunkSize\":\\d+"), "")
        metaFile.writeText(legacyJson)
        assertEquals(TinkStreaming.CIPHERTEXT_SEGMENT_SIZE, manager.metaOf(meta.vaultId).chunkSize)

        val u2 = manager.unlock(meta.vaultId, pw("pw123456"))
        assertArrayEquals(data, manager.openBlobStream(u2, blobId).use { it.readBytes() })
        assertEquals("old.txt", manager.loadIndex(u2).entries[0].name)
    }

    @Test
    fun maliciousChunkSizeClamped() {
        val meta = manager.createVault("m", pw("pw123456"), kdfParams = KdfParams.TEST)
        val evil = manager.metaOf(meta.vaultId).toJson().replace(Regex("\"chunkSize\":\\d+"), "\"chunkSize\":1")
        assertEquals(TinkStreaming.CIPHERTEXT_SEGMENT_SIZE, VaultMeta.fromJson(evil).chunkSize)
        val huge = manager.metaOf(meta.vaultId).toJson().replace(Regex("\"chunkSize\":\\d+"), "\"chunkSize\":${Int.MAX_VALUE}")
        assertTrue(VaultMeta.fromJson(huge).chunkSize <= 16 * 1024 * 1024)
    }

    // ---------------- KDF 档位 ----------------

    @Test
    fun newDefaultsStrongerThanOwaspFloor() {
        // OWASP 2024 下限 19MiB/t2/p1;新默认档必须全面高于它
        assertTrue(KdfParams.DEFAULT.memoryKiB >= 64 * 1024)
        assertTrue(KdfParams.DEFAULT.iterations >= 3)
        assertTrue(KdfParams.DEFAULT.parallelism >= 2)
        assertTrue(KdfParams.HIGH_SECURITY.isAtLeast(KdfParams.DEFAULT))
        assertTrue(KdfParams.HIGH_SECURITY.memoryKiB >= 128 * 1024)
    }

    // ---------------- 万级条目基准(冒烟+性能不回退) ----------------

    @Test
    fun tenThousandEntriesSearchAndExportPathsStayFast() {
        val meta = manager.createVault("perf", pw("pw123456"), masterGate = true, kdfParams = KdfParams.TEST)
        val u = manager.unlock(meta.vaultId, pw("pw123456"))
        val idx = VaultIndex()
        // 100 个文件夹 × 100 文件 = 10k 条目,两层目录链
        repeat(100) { f ->
            val folder = idx.addEntry("folder-$f", MediaKind.FOLDER)
            repeat(100) { i ->
                idx.addEntry("file-$i.bin", MediaKind.OTHER, blobId = "b$f-$i", parentId = folder.id)
            }
        }
        assertEquals(10_100, idx.entries.size)

        // 搜索 100 次:线性扫描在万级下仍须毫秒级(防抖后每次击键只跑一次)
        val t0 = System.nanoTime()
        repeat(100) { idx.search("file-42") }
        val searchMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("search too slow: ${searchMs}ms", searchMs < 3_000)

        // 导出路径回溯:全部 10k 文件走一遍 relPathOf(经 exportEntries 的失败路径,
        // blob 不存在 → 只走路径计算与失败记录,不解密)
        val sinks = object : TransferEngine.ExportSinkFactory {
            override fun create(relPath: String, mimeType: String?): TransferEngine.ExportSink =
                throw IllegalStateException("should not be reached: blobs absent")
        }
        val files = idx.entries.filter { !it.isFolder }
        val t1 = System.nanoTime()
        val res = engine.exportEntries(u, files, idx, sinks)
        val exportMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals(10_000, res.failed) // blob 缺失全部记失败,但路径计算必须完成
        assertTrue("export path resolution too slow: ${exportMs}ms", exportMs < 5_000)
    }

    // ---------------- 导入长度口径 ----------------

    @Test
    fun unknownSourceSizeRecordsPlaintextLength() {
        val meta = manager.createVault("len", pw("pw123456"), kdfParams = KdfParams.TEST)
        val u = manager.unlock(meta.vaultId, pw("pw123456"))
        val idx = VaultIndex()
        val payload = ByteArray(12345) { it.toByte() }
        val src = object : TransferEngine.ImportSource {
            override val name = "unknown-size.bin"
            override val sizeBytes = -1L // SAF 偶发报不出大小
            override val mimeType: String? = null
            override val isDirectory = false
            override fun open(): InputStream = ByteArrayInputStream(payload)
            override fun children(): List<TransferEngine.ImportSource> = emptyList()
        }
        engine.import(u, listOf(src), null, idx)
        val e = idx.entries.single()
        assertEquals(payload.size.toLong(), e.sizeBytes) // 明文长度,不是密文长度
        assertArrayEquals(payload, manager.openBlobStream(u, e.blobId!!).use { it.readBytes() })
    }
}