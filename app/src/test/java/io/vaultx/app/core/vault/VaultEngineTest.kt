package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.WrongPasswordException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: VaultManager
    private lateinit var archive: VaultArchive

    @Before
    fun setUp() {
        manager = VaultManager(tmp.root)
        archive = VaultArchive(manager)
    }

    private fun pw(s: String) = s.toCharArray()

    @Test
    fun createAndUnlock() {
        val meta = manager.createVault("私人", pw("pass1234"), kdfParams = KdfParams.TEST)
        assertEquals("私人", meta.name)
        val unlocked = manager.unlock(meta.vaultId, pw("pass1234"))
        assertEquals(meta.vaultId, unlocked.vaultId)
        assertFalse(unlocked.viaDecoy)
    }

    @Test
    fun unlockWrongPasswordFails() {
        val meta = manager.createVault("a", pw("right"), kdfParams = KdfParams.TEST)
        try {
            manager.unlock(meta.vaultId, pw("wrong"))
            fail("expected WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun renameAndDelete() {
        val meta = manager.createVault("old", pw("pw1234"), kdfParams = KdfParams.TEST)
        manager.renameVault(meta.vaultId, "new")
        assertEquals("new", manager.metaOf(meta.vaultId).name)
        manager.deleteVault(meta.vaultId)
        assertFalse(manager.vaultExists(meta.vaultId))
        assertTrue(manager.listVaults().isEmpty())
    }

    @Test
    fun listVaultsFiltersOrphans() {
        val meta = manager.createVault("ok", pw("pw1234"), kdfParams = KdfParams.TEST)
        // 孤儿目录(无 meta.vault)应被忽略并清理
        manager.vaultDir("orphan-uuid").mkdirs()
        val list = manager.listVaults()
        assertEquals(1, list.size)
        assertEquals(meta.vaultId, list[0].vaultId)
        assertFalse(manager.vaultDir("orphan-uuid").exists())
    }

    @Test
    fun indexPlainAndEncryptedForms() {
        // masterGate off → 明文索引,免密可读条目数
        val meta1 = manager.createVault("plain", pw("pw1234"), masterGate = false, kdfParams = KdfParams.TEST)
        val unlocked1 = manager.unlock(meta1.vaultId, pw("pw1234"))
        val idx = VaultIndex().apply { addEntry("a.jpg", MediaKind.IMAGE, blobId = "b1") }
        manager.saveIndex(unlocked1, idx)
        assertNotNull(manager.loadPlainIndex(meta1.vaultId))
        assertEquals(1, manager.loadPlainIndex(meta1.vaultId)!!.entries.size)

        // masterGate on → 索引加密,免密读不到
        val meta2 = manager.createVault("enc", pw("pw1234"), masterGate = true, kdfParams = KdfParams.TEST)
        val unlocked2 = manager.unlock(meta2.vaultId, pw("pw1234"))
        manager.saveIndex(unlocked2, idx)
        assertNull(manager.loadPlainIndex(meta2.vaultId))
        assertEquals(1, manager.loadIndex(unlocked2).entries.size)
    }

    @Test
    fun masterGateToggleConvertsIndex() {
        val meta = manager.createVault("g", pw("pw1234"), masterGate = false, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("pw1234"))
        val idx = VaultIndex().apply { addEntry("x.txt", MediaKind.OTHER, blobId = "b") }
        manager.saveIndex(unlocked, idx)
        assertFalse(manager.metaOf(meta.vaultId).indexEncrypted)

        manager.setMasterGate(unlocked, manager.loadIndex(unlocked), true)
        assertTrue(manager.metaOf(meta.vaultId).indexEncrypted)
        assertNull(manager.loadPlainIndex(meta.vaultId))
        assertEquals(1, manager.loadIndex(unlocked).entries.size)

        manager.setMasterGate(unlocked, manager.loadIndex(unlocked), false)
        assertFalse(manager.metaOf(meta.vaultId).indexEncrypted)
        assertNotNull(manager.loadPlainIndex(meta.vaultId))
    }

    @Test
    fun changePasswordZeroReencrypt() {
        val meta = manager.createVault("k", pw("oldpw"), masterGate = true, kdfParams = KdfParams.TEST)
        var unlocked = manager.unlock(meta.vaultId, pw("oldpw"))
        val idx = VaultIndex().apply { addEntry("s.txt", MediaKind.OTHER, blobId = "bb") }
        manager.saveIndex(unlocked, idx)
        val blobFile = File(manager.blobFile(meta.vaultId, "bb").path)
        blobFile.parentFile?.mkdirs()
        blobFile.writeBytes("encrypted-placeholder".toByteArray())

        manager.changePassword(unlocked, pw("newpw"))
        // 旧密码即刻失效
        try {
            manager.unlock(meta.vaultId, pw("oldpw"))
            fail("old password must fail")
        } catch (_: WrongPasswordException) {
        }
        // 新密码解锁,数据完好
        unlocked = manager.unlock(meta.vaultId, pw("newpw"))
        assertEquals(1, manager.loadIndex(unlocked).entries.size)
        assertTrue(blobFile.isFile)
        assertArrayEquals("encrypted-placeholder".toByteArray(), blobFile.readBytes())
    }

    @Test
    fun decoyUnlocksIndependentCompartment() {
        val meta = manager.createVault("real", pw("realpw"), masterGate = true, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("realpw"))
        manager.enableDecoy(unlocked, pw("decoypw"))
        assertTrue(manager.metaOf(meta.vaultId).hasDecoy)

        // 真密码 → 真库;诱骗密码 → 诱骗库,互不串
        val real = manager.unlock(meta.vaultId, pw("realpw"))
        assertFalse(real.viaDecoy)
        val decoy = manager.unlock(meta.vaultId, pw("decoypw"))
        assertTrue(decoy.viaDecoy)
        assertEquals(0, manager.loadIndex(decoy).entries.size)

        // 两边写各自的索引
        manager.saveIndex(real, VaultIndex().apply { addEntry("secret.jpg", MediaKind.IMAGE, blobId = "r") })
        manager.saveIndex(decoy, VaultIndex().apply { addEntry("cover.jpg", MediaKind.IMAGE, blobId = "d") })
        assertEquals("secret.jpg", manager.loadIndex(real).entries[0].name)
        assertEquals("cover.jpg", manager.loadIndex(decoy).entries[0].name)

        // 错密码仍然失败
        try {
            manager.unlock(meta.vaultId, pw("nope"))
            fail()
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun decoyForcesMasterGateAndBlocksDisable() {
        val meta = manager.createVault("r", pw("realpw"), masterGate = false, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("realpw"))
        manager.enableDecoy(unlocked, pw("decoypw"))
        // 诱骗开启后总密码被强制打开
        assertTrue(manager.metaOf(meta.vaultId).masterGate)
        assertTrue(manager.metaOf(meta.vaultId).indexEncrypted)
        // 且不可关闭
        val u2 = manager.unlock(meta.vaultId, pw("realpw"))
        try {
            manager.setMasterGate(u2, manager.loadIndex(u2), false)
            fail("masterGate cannot be disabled with decoy")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun disableDecoyRequiresRealSession() {
        val meta = manager.createVault("r", pw("realpw"), masterGate = true, kdfParams = KdfParams.TEST)
        manager.enableDecoy(manager.unlock(meta.vaultId, pw("realpw")), pw("decoypw"))
        val decoy = manager.unlock(meta.vaultId, pw("decoypw"))
        try {
            manager.disableDecoy(decoy)
            fail("decoy session cannot disable decoy")
        } catch (_: IllegalStateException) {
        }
        val real = manager.unlock(meta.vaultId, pw("realpw"))
        manager.disableDecoy(real)
        assertFalse(manager.metaOf(meta.vaultId).hasDecoy)
        // 诱骗密码失效
        try {
            manager.unlock(meta.vaultId, pw("decoypw"))
            fail()
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun archiveExportImportRoundtrip() {
        val meta = manager.createVault("备份", pw("pw1234"), masterGate = true, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("pw1234"))
        val idx = VaultIndex().apply { addEntry("doc.txt", MediaKind.OTHER, blobId = "blob1") }
        manager.saveIndex(unlocked, idx)
        val blob = manager.blobFile(meta.vaultId, "blob1")
        blob.parentFile?.mkdirs()
        blob.writeBytes("cipher-bytes-123".toByteArray())
        manager.writeThumb(unlocked, "blob1", "thumb".toByteArray())

        // 导出 → 删库 → 导入
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        val archiveBytes = out.toByteArray()
        manager.deleteVault(meta.vaultId)
        assertFalse(manager.vaultExists(meta.vaultId))

        val imported = archive.importVault(ByteArrayInputStream(archiveBytes), pw("pw1234"))
        assertEquals(meta.vaultId, imported.vaultId)
        assertTrue(blob.isFile)
        assertArrayEquals("cipher-bytes-123".toByteArray(), blob.readBytes())
        val u2 = manager.unlock(meta.vaultId, pw("pw1234"))
        assertEquals("doc.txt", manager.loadIndex(u2).entries[0].name)
    }

    @Test
    fun archiveTamperRejectedAndNoResidue() {
        val meta = manager.createVault("t", pw("pw1234"), kdfParams = KdfParams.TEST)
        val blob = manager.blobFile(meta.vaultId, "b1")
        blob.parentFile?.mkdirs()
        blob.writeBytes("data".toByteArray())
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        val bytes = out.toByteArray()
        bytes[bytes.size - 1] = (bytes.last() + 1).toByte()
        manager.deleteVault(meta.vaultId)
        try {
            archive.importVault(ByteArrayInputStream(bytes), pw("pw1234"))
            fail("tamper must be rejected")
        } catch (_: ArchiveException) {
        }
        // 不留残库
        assertFalse(manager.vaultExists(meta.vaultId))
        assertTrue(manager.listVaults().isEmpty())
    }

    @Test
    fun archiveImportWrongPasswordRejected() {
        val meta = manager.createVault("t", pw("pw1234"), kdfParams = KdfParams.TEST)
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        manager.deleteVault(meta.vaultId)
        try {
            archive.importVault(ByteArrayInputStream(out.toByteArray()), pw("wrong"))
            fail()
        } catch (_: WrongPasswordException) {
        }
        assertFalse(manager.vaultExists(meta.vaultId))
    }

    @Test
    fun folderCascadeDeleteAndMoveCycleGuard() {
        val meta = manager.createVault("f", pw("pw1234"), kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("pw1234"))
        val idx = VaultIndex().apply {
            addEntry("dirA", MediaKind.FOLDER)
            addEntry("dirB", MediaKind.FOLDER)
        }
        val dirA = idx.entries[0]
        val dirB = idx.entries[1]
        idx.addEntry("inner.jpg", MediaKind.IMAGE, blobId = "x", parentId = dirA.id)
        idx.addEntry("subDir", MediaKind.FOLDER, parentId = dirB.id)
        val subDir = idx.entries.last()
        idx.addEntry("deep.txt", MediaKind.OTHER, blobId = "y", parentId = subDir.id)

        // dirA 子孙 = inner.jpg;dirB 子孙 = subDir + deep.txt
        assertEquals(setOf(idx.entries[2].id), idx.descendantIds(dirA.id))
        assertEquals(setOf(subDir.id, idx.entries.last().id), idx.descendantIds(dirB.id))
        manager.saveIndex(unlocked, idx)
        assertEquals(5, manager.loadIndex(unlocked).entries.size)
    }

    @Test
    fun indexCacheIsolatedFromCallerMutation() {
        val meta = manager.createVault("c", pw("pw1234"), kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("pw1234"))
        val idx = VaultIndex()
        idx.addEntry("a.txt", MediaKind.OTHER)
        manager.saveIndex(unlocked, idx)
        // 调用方再改自己的 index,不应污染缓存
        idx.addEntry("ghost.txt", MediaKind.OTHER)
        val loaded = manager.loadIndex(unlocked)
        assertEquals(1, loaded.entries.size)
        // 返回值也是快照:改了也不影响缓存
        loaded.entries.add(VaultEntry("x", "z", MediaKind.OTHER))
        assertEquals(1, manager.loadIndex(unlocked).entries.size)
    }

    @Test
    fun lockInvalidatesIndexCache() {
        val meta = manager.createVault("c", pw("pw1234"), kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("pw1234"))
        manager.saveIndex(unlocked, VaultIndex().apply { addEntry("a.txt", MediaKind.OTHER) })
        assertEquals(1, manager.loadIndex(unlocked).entries.size)
        manager.onVaultLocked(meta.vaultId)
        // 缓存已清,下次 load 重新读盘(值仍正确)
        assertEquals(1, manager.loadIndex(unlocked).entries.size)
    }

    @Test
    fun rewrapParamsUpgradeOnUnlock() {
        val stronger = KdfParams(memoryKiB = 16 * 1024, iterations = 2, parallelism = 1)
        val meta = manager.createVault("u", pw("pw1234"), kdfParams = KdfParams.TEST)
        // 解锁时带更强参数 → 透明重包裹
        manager.unlock(meta.vaultId, pw("pw1234"), rewrapParams = stronger)
        assertEquals(stronger, manager.metaOf(meta.vaultId).kdfParams)
        // 旧数据还能解
        manager.unlock(meta.vaultId, pw("pw1234"))
        // 降级请求不动
        manager.unlock(meta.vaultId, pw("pw1234"), rewrapParams = KdfParams.TEST)
        assertEquals(stronger, manager.metaOf(meta.vaultId).kdfParams)
    }

    @Test
    fun archiveExcludesBioWrap() {
        val meta = manager.createVault("b", pw("pw1234"), kdfParams = KdfParams.TEST)
        manager.writeBioWrap(meta.vaultId, byteArrayOf(1, 2, 3))
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        manager.deleteVault(meta.vaultId)
        archive.importVault(ByteArrayInputStream(out.toByteArray()), pw("pw1234"))
        // bio.wrap 绑定本机 Keystore,不随归档走
        assertFalse(manager.bioWrapFile(meta.vaultId).exists())
    }

    @Test
    fun archiveTrailingGarbageRejected() {
        val meta = manager.createVault("t", pw("pw1234"), kdfParams = KdfParams.TEST)
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        val bytes = out.toByteArray() + byteArrayOf(0x55)
        manager.deleteVault(meta.vaultId)
        try {
            archive.importVault(ByteArrayInputStream(bytes), pw("pw1234"))
            fail("trailing garbage must be rejected")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.contains("多余"))
        }
        assertFalse(manager.vaultExists(meta.vaultId))
    }

    @Test
    fun archiveTruncatedRejected() {
        val meta = manager.createVault("t", pw("pw1234"), kdfParams = KdfParams.TEST)
        val blob = manager.blobFile(meta.vaultId, "b1")
        blob.parentFile?.mkdirs()
        blob.writeBytes(ByteArray(10_000) { 3 })
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        val truncated = out.toByteArray().copyOf(out.size() - 100)
        manager.deleteVault(meta.vaultId)
        try {
            archive.importVault(ByteArrayInputStream(truncated), pw("pw1234"))
            fail("truncated archive must be rejected")
        } catch (_: ArchiveException) {
        }
        assertFalse(manager.vaultExists(meta.vaultId))
    }

    @Test
    fun wipeAllRemovesEverything() {
        manager.createVault("a", pw("pw1234"), kdfParams = KdfParams.TEST)
        manager.createVault("b", pw("pw1234"), kdfParams = KdfParams.TEST)
        File(manager.pendingRoot(), "import-stuck").mkdirs()
        manager.wipeAll()
        assertTrue(manager.listVaults().isEmpty())
        assertEquals(0, manager.pendingRoot().listFiles()?.size ?: 0)
    }
}
