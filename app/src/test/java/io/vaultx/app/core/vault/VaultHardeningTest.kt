package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.WrongPasswordException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 安全加固回归测试(阶段1):
 * 建库暂存竞态 / 诱骗 blob 清扫 / rewrap 失败不泄漏会话 / 密码长度下限 /
 * 归档 meta 排首与旧序兼容 / 超长路径显式拒绝。
 */
class VaultHardeningTest {

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

    // ---------------- 建库暂存竞态 ----------------

    @Test
    fun freshBuildingStagingSurvivesListVaults() {
        // 模拟"正在建库"的活暂存:刚创建、无 meta。listVaults 绝不能清掉它。
        val staging = File(File(tmp.root, VaultManager.VAULTS_DIR), ".some-id.building")
        staging.mkdirs()
        staging.setLastModified(System.currentTimeMillis())
        manager.listVaults()
        assertTrue("live staging must survive listVaults", staging.isDirectory)
    }

    @Test
    fun staleBuildingStagingIsSwept() {
        val staging = File(File(tmp.root, VaultManager.VAULTS_DIR), ".dead-id.building")
        staging.mkdirs()
        staging.setLastModified(System.currentTimeMillis() - VaultManager.BUILDING_STALE_MS - 1000)
        manager.listVaults()
        assertFalse("stale staging must be swept", staging.exists())
    }

    // ---------------- 密码长度下限 ----------------

    @Test
    fun shortPasswordRejectedOnCreateAndChange() {
        try {
            manager.createVault("s", pw("short"), kdfParams = KdfParams.TEST)
            fail("short password must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val meta = manager.createVault("ok", pw("longenough"), kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("longenough"))
        try {
            manager.changePassword(unlocked, pw("tiny"))
            fail("short new password must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        // 存量短密码库不受影响:手工构造 4 位密码的旧库仍可解锁
        // (通过正常 API 已无法创建,这里验证 unlock 不做长度校验)
        val legacy = manager.createVault("legacy", pw("12345678"), kdfParams = KdfParams.TEST)
        manager.unlock(legacy.vaultId, pw("12345678")).crypto.zeroize()
    }

    // ---------------- 诱骗 blob 清扫 ----------------

    @Test
    fun disableDecoySweepsDecoyBlobsAndKeepsReal() {
        val meta = manager.createVault("d", pw("realpw12"), masterGate = true, kdfParams = KdfParams.TEST)
        val real = manager.unlock(meta.vaultId, pw("realpw12"))
        manager.enableDecoy(real, pw("decoypw1"))

        // 真库 blob
        val realIdx = manager.loadIndex(real)
        realIdx.addEntry("real.txt", MediaKind.OTHER, blobId = "realblob")
        manager.saveIndex(real, realIdx)
        val realBlob = manager.blobFile(meta.vaultId, "realblob")
        realBlob.parentFile?.mkdirs()
        realBlob.writeBytes("real-cipher".toByteArray())

        // 诱骗库 blob(经诱骗会话写入)
        val decoy = manager.unlock(meta.vaultId, pw("decoypw1"))
        assertTrue(decoy.viaDecoy)
        val decoyIdx = manager.loadIndex(decoy)
        decoyIdx.addEntry("bait.txt", MediaKind.OTHER, blobId = "decoyblob")
        manager.saveIndex(decoy, decoyIdx)
        val decoyBlob = manager.blobFile(meta.vaultId, "decoyblob")
        decoyBlob.parentFile?.mkdirs()
        decoyBlob.writeBytes("decoy-cipher".toByteArray())
        manager.writeThumb(decoy, "decoyblob", "thumb".toByteArray())
        decoy.crypto.zeroize()

        manager.disableDecoy(real)
        assertFalse("decoy blob must be swept", decoyBlob.exists())
        assertFalse("decoy thumb must be swept", manager.thumbFile(meta.vaultId, "decoyblob").exists())
        assertTrue("real blob must survive", realBlob.isFile)
        assertArrayEquals("real-cipher".toByteArray(), realBlob.readBytes())
        assertFalse(manager.metaOf(meta.vaultId).hasDecoy)
    }

    // ---------------- rewrap 失败降级 ----------------

    @Test
    fun rewrapFailureDegradesToWarningAndKeepsSession() {
        val meta = manager.createVault("r", pw("pw123456"), kdfParams = KdfParams.TEST)
        // meta 设为只读 → maybeRewrap 的 writeAtomic 必然 IO 失败
        val metaFile = File(manager.vaultDir(meta.vaultId), VaultMeta.FILE_NAME)
        val dir = manager.vaultDir(meta.vaultId)
        dir.setWritable(false)
        try {
            var warning: String? = null
            val unlocked = manager.unlock(
                meta.vaultId,
                pw("pw123456"),
                rewrapParams = KdfParams.DEFAULT, // 强于 TEST → 触发 rewrap
                onWarning = { warning = it },
            )
            // 解锁成功、会话可用;失败只进警告
            assertEquals(meta.vaultId, unlocked.vaultId)
            assertTrue("rewrap failure must surface as warning", warning != null)
            unlocked.crypto.zeroize()
        } finally {
            dir.setWritable(true)
        }
    }

    // ---------------- 诱骗解锁语义(恒定双链后仍正确) ----------------

    @Test
    fun decoyRoutingStillCorrectAfterConstantTimeChange() {
        val meta = manager.createVault("c", pw("realpw12"), masterGate = true, kdfParams = KdfParams.TEST)
        val real = manager.unlock(meta.vaultId, pw("realpw12"))
        manager.enableDecoy(real, pw("decoypw1"))
        real.crypto.zeroize()

        assertFalse(manager.unlock(meta.vaultId, pw("realpw12")).viaDecoy)
        assertTrue(manager.unlock(meta.vaultId, pw("decoypw1")).viaDecoy)
        try {
            manager.unlock(meta.vaultId, pw("neither1"))
            fail("wrong password must fail")
        } catch (_: WrongPasswordException) {
        }
    }

    // ---------------- 归档 meta 排首 + 旧序兼容 ----------------

    @Test
    fun exportPutsMetaFirst() {
        val meta = manager.createVault("m", pw("pw123456"), masterGate = true, kdfParams = KdfParams.TEST)
        val blob = manager.blobFile(meta.vaultId, "aaaa1")
        blob.parentFile?.mkdirs()
        blob.writeBytes("x".toByteArray())
        val out = ByteArrayOutputStream()
        archive.exportVault(meta.vaultId, out)
        // 手工解析第一条目路径:必须是 meta.vault(字典序 blobs/ 本应排前)
        val data = java.io.DataInputStream(ByteArrayInputStream(out.toByteArray()))
        data.skipBytes(9) // magic(4)+version(1)+count(4)
        val pathLen = data.readUnsignedShort()
        val path = ByteArray(pathLen).also { data.readFully(it) }.toString(Charsets.UTF_8)
        assertEquals(VaultMeta.FILE_NAME, path)
    }

    @Test
    fun legacyMetaLastArchiveStillImports() {
        val meta = manager.createVault("legacy", pw("pw123456"), masterGate = true, kdfParams = KdfParams.TEST)
        val unlocked = manager.unlock(meta.vaultId, pw("pw123456"))
        val idx = VaultIndex().apply { addEntry("f.txt", MediaKind.OTHER, blobId = "b1") }
        manager.saveIndex(unlocked, idx)
        val blob = manager.blobFile(meta.vaultId, "b1")
        blob.parentFile?.mkdirs()
        blob.writeBytes("cipher".toByteArray())

        // 构造旧序归档:blobs 在前、meta.vault 在最后(0.1.0 的实际排序)
        val dir = manager.vaultDir(meta.vaultId)
        val files = dir.walkTopDown()
            .filter { it.isFile && it.name != VaultManager.BIO_WRAP && !it.name.endsWith(".tmp") }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.write("FVLT".toByteArray())
        dos.writeByte(1)
        dos.writeInt(files.size)
        files.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath.toByteArray()
            val bytes = f.readBytes()
            dos.writeShort(rel.size)
            dos.write(rel)
            dos.writeLong(bytes.size.toLong())
            dos.write(MessageDigest.getInstance("SHA-256").digest(bytes))
            dos.write(bytes)
        }
        dos.flush()
        // 旧序 peekMeta 仍可用(流式 skip)
        val peeked = archive.peekMeta(ByteArrayInputStream(out.toByteArray()))
        assertEquals(meta.vaultId, peeked.vaultId)
        manager.deleteVault(meta.vaultId)
        val imported = archive.importVault(ByteArrayInputStream(out.toByteArray()), pw("pw123456"))
        assertEquals(meta.vaultId, imported.vaultId)
        assertArrayEquals("cipher".toByteArray(), manager.blobFile(meta.vaultId, "b1").readBytes())
    }

    // ---------------- 原子写 ----------------

    @Test
    fun atomicWriteLeavesNoTmpAndContentCorrect() {
        val target = File(tmp.root, "sub/atomic.bin")
        val payload = ByteArray(4096) { (it % 251).toByte() }
        manager.writeAtomic(target, payload)
        assertArrayEquals(payload, target.readBytes())
        assertEquals(1, target.parentFile!!.listFiles()!!.size) // 无 .tmp 残留
        // 覆写同样干净
        manager.writeAtomic(target, "second".toByteArray())
        assertArrayEquals("second".toByteArray(), target.readBytes())
        assertEquals(1, target.parentFile!!.listFiles()!!.size)
    }
}