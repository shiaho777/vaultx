package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.WrongPasswordException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * `.fvault` 归档格式——库的**密文搬运**备份(导出免密码;blob 本来就加密)。
 *
 * ```
 * magic "FVLT" (4B) | version u8=1 | entryCount u32be
 * per entry: pathLen u16be | path utf8 | size u64be | sha256(32B) | bytes...
 * ```
 * 条目即库目录下的相对路径文件(meta.vault、index.*、blobs/、thumbs/、bio.wrap)。
 *
 * 导入安全序:全部条目先落**暂存目录**并逐文件校验 SHA-256 → 解析 meta 验密码 →
 * 整体 rename 就位。任何一步失败,暂存目录清除,不留残库。
 */
class VaultArchive(private val vaultManager: VaultManager) {

    /** 免密码导出:逐文件哈希 + 密文原样打包。meta.vault 排最前,便于快速读头。 */
    fun exportVault(vaultId: String, output: OutputStream) {
        val dir = vaultManager.vaultDir(vaultId)
        require(dir.isDirectory) { "vault not found: $vaultId" }
        val files = dir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
        val out = DataOutputStream(output)
        out.write(MAGIC)
        out.writeByte(VERSION)
        out.writeInt(files.size)
        for (f in files) {
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            val pathBytes = rel.toByteArray(Charsets.UTF_8)
            val digest = sha256Of(f)
            out.writeShort(pathBytes.size)
            out.write(pathBytes)
            out.writeLong(f.length())
            out.write(digest)
            f.inputStream().use { it.copyTo(out) }
        }
        out.flush()
    }

    /** 只读清单里的 meta.vault(不必解出全部条目),供导入前展示/验密码。 */
    fun peekMeta(input: InputStream): VaultMeta {
        val data = DataInputStream(input)
        val count = readHeader(data)
        repeat(count) {
            val pathBytes = ByteArray(data.readUnsignedShort()).also { data.readFully(it) }
            val path = String(pathBytes, Charsets.UTF_8)
            val size = data.readLong()
            data.skipBytes(32) // sha256
            if (path == VaultMeta.FILE_NAME) {
                val bytes = ByteArray(size.toInt()).also { data.readFully(it) }
                return VaultMeta.fromJson(String(bytes, Charsets.UTF_8))
            }
            skipFully(data, size)
        }
        throw ArchiveException("archive has no ${VaultMeta.FILE_NAME}")
    }

    /**
     * 导入:暂存 → 逐文件哈希校验 → 验密码 → 原子就位。
     * @throws ArchiveException 格式错/哈希不符/缺 meta/库已存在
     * @throws WrongPasswordException 密码不符(真链或诱骗链均通过才算对)
     */
    fun importVault(input: InputStream, password: CharArray): VaultMeta {
        val staging = File(vaultManager.pendingRoot(), "import-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            val data = DataInputStream(input)
            val count = readHeader(data)
            var meta: VaultMeta? = null
            repeat(count) {
                val pathBytes = ByteArray(data.readUnsignedShort()).also { data.readFully(it) }
                val path = sanitizePath(String(pathBytes, Charsets.UTF_8))
                val size = data.readLong()
                val expectHash = ByteArray(32).also { data.readFully(it) }
                val target = File(staging, path)
                target.parentFile?.mkdirs()
                val actual = copyWithHash(data, target, size)
                if (!actual.contentEquals(expectHash)) {
                    throw ArchiveException("hash mismatch: $path")
                }
                if (path == VaultMeta.FILE_NAME) {
                    meta = VaultMeta.fromJson(target.readText())
                }
            }
            val m = meta ?: throw ArchiveException("archive has no ${VaultMeta.FILE_NAME}")
            if (!vaultManager.verifyPassword(m, password)) throw WrongPasswordException()
            val dest = vaultManager.vaultDir(m.vaultId)
            if (dest.exists()) throw ArchiveException("vault already exists")
            if (!staging.renameTo(dest)) throw ArchiveException("failed to move vault into place")
            return m
        } finally {
            password.fill('\u0000')
            staging.deleteRecursively()
        }
    }

    private fun readHeader(data: DataInputStream): Int {
        val magic = ByteArray(4)
        try {
            data.readFully(magic)
        } catch (e: EOFException) {
            throw ArchiveException("truncated archive")
        }
        if (!magic.contentEquals(MAGIC)) throw ArchiveException("bad magic")
        val version = data.readByte().toInt()
        if (version != VERSION) throw ArchiveException("unsupported version $version")
        val count = data.readInt()
        if (count < 0 || count > 1_000_000) throw ArchiveException("bad entry count")
        return count
    }

    /** 防 zip-slip:只允许相对路径段,拒绝空/绝对/../ 反斜杠。 */
    private fun sanitizePath(path: String): String {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\')) {
            throw ArchiveException("bad path: $path")
        }
        if (path.split('/').any { it.isEmpty() || it == ".." }) {
            throw ArchiveException("bad path: $path")
        }
        return path
    }

    private fun sha256Of(f: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }

    private fun copyWithHash(data: DataInputStream, target: File, size: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val n = data.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw ArchiveException("unexpected EOF")
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        return md.digest()
    }

    private fun skipFully(data: DataInputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val n = data.skip(remaining)
            if (n <= 0) {
                if (data.read() < 0) throw ArchiveException("unexpected EOF")
                remaining -= 1
            } else {
                remaining -= n
            }
        }
    }

    companion object {
        private val MAGIC = byteArrayOf('F'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
        private const val VERSION = 1
    }
}

/** 归档格式错/被篡改(与密码错误区分)。 */
class ArchiveException(message: String) : Exception(message)
