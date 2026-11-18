package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.WrongPasswordException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
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
 * 条目即库目录下的相对路径文件(meta.vault、index.*、blobs/、thumbs/)。
 * `bio.wrap` 不随归档走:它绑定本机 Keystore 密钥,换设备必然解不开,
 * 且会向外泄露"该库配置过生物识别"这一事实。
 *
 * 导入安全序:全部条目先落**暂存目录**并逐文件校验 SHA-256 → 尾部垃圾检测 →
 * 解析 meta 验密码 → 整体 rename 就位。任何一步失败,暂存目录清除,不留残库。
 */
class VaultArchive(private val vaultManager: VaultManager) {

    /** 免密码导出:逐文件哈希 + 密文原样打包。meta.vault 强制排首,导入验密无需跳过数 GB blob。 */
    fun exportVault(vaultId: String, output: OutputStream, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val dir = vaultManager.vaultDir(vaultId)
        require(dir.isDirectory) { "vault not found: $vaultId" }
        val files = dir.walkTopDown()
            // bio.wrap 绑定本机 Keystore 不可携带;.tmp 是 writeAtomic 半途残迹
            .filter { it.isFile && it.name != VaultManager.BIO_WRAP && !it.name.endsWith(".tmp") }
            // meta.vault 必须排第一:纯字典序会把 blobs/ 排在 meta 前面,
            // peekMeta 验密就得流式 skip 整个库的密文——大库导入前验证要等数分钟
            .sortedWith(
                compareBy<File> { if (it.name == VaultMeta.FILE_NAME && it.parentFile == dir) 0 else 1 }
                    .thenBy { it.relativeTo(dir).invariantSeparatorsPath },
            )
            .toList()
        val out = DataOutputStream(output)
        out.write(MAGIC)
        out.writeByte(VERSION)
        out.writeInt(files.size)
        files.forEachIndexed { i, f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            val pathBytes = rel.toByteArray(Charsets.UTF_8)
            // u16 路径长度:超限必须显式报错,writeShort 静默截断会产出损坏归档
            if (pathBytes.size > 65535) throw ArchiveException("path too long for archive format: $rel")
            // 单遍"读-哈希-转发":size/digest/内容出自同一次读取,消除
            // "先哈希后 stat"之间文件被并发修改导致的不一致(TOCTOU)。
            // 内容先落本地临时文件(私有目录,密文原样),长度确定后再写头+转发。
            val tmp = File.createTempFile("fvault-", ".part", vaultManager.pendingRoot())
            try {
                val md = MessageDigest.getInstance("SHA-256")
                var size = 0L
                tmp.outputStream().use { sink ->
                    f.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            md.update(buf, 0, n)
                            size += n
                        }
                    }
                }
                out.writeShort(pathBytes.size)
                out.write(pathBytes)
                out.writeLong(size)
                out.write(md.digest())
                tmp.inputStream().use { it.copyTo(out, 64 * 1024) }
            } finally {
                tmp.delete()
            }
            onProgress(i + 1, files.size)
        }
        out.flush()
    }

    /** 只读清单里的 meta.vault(不必解出全部条目),供导入前展示/验密码。 */
    fun peekMeta(input: InputStream): VaultMeta {
        val data = DataInputStream(input)
        val count = readHeader(data)
        try {
            repeat(count) {
                val pathBytes = ByteArray(data.readUnsignedShort()).also { data.readFully(it) }
                val path = String(pathBytes, Charsets.UTF_8)
                val size = data.readLong()
                data.skipBytes(32) // sha256
                if (path == VaultMeta.FILE_NAME) {
                    if (size < 0 || size > 1024 * 1024) throw ArchiveException("invalid meta.vault size")
                    val bytes = ByteArray(size.toInt()).also { data.readFully(it) }
                    return try {
                        VaultMeta.fromJson(String(bytes, Charsets.UTF_8))
                    } catch (e: Exception) {
                        throw ArchiveException("failed to parse meta.vault")
                    }
                }
                skipFully(data, size)
            }
        } catch (e: EOFException) {
            throw ArchiveException("incomplete archive (truncated before meta.vault)")
        }
        throw ArchiveException("archive is missing ${VaultMeta.FILE_NAME}")
    }

    /**
     * 导入:暂存 → 逐文件哈希校验 → 尾垃圾检测 → 验密码 → 原子就位。
     * @throws ArchiveException 格式错/哈希不符/缺 meta/尾部多余数据/库已存在
     * @throws WrongPasswordException 密码不符(真链或诱骗链均通过才算对)
     */
    fun importVault(
        input: InputStream,
        password: CharArray,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): VaultMeta {
        val staging = File(vaultManager.pendingRoot(), "import-${UUID.randomUUID()}")
        try {
            staging.mkdirs()
            val data = DataInputStream(input)
            val count = readHeader(data)
            var meta: VaultMeta? = null
            var done = 0
            repeat(count) {
                val path: String
                val size: Long
                val expectHash: ByteArray
                try {
                    val pathBytes = ByteArray(data.readUnsignedShort()).also { data.readFully(it) }
                    path = sanitizePath(String(pathBytes, Charsets.UTF_8))
                    size = data.readLong()
                    expectHash = ByteArray(32).also { data.readFully(it) }
                } catch (e: EOFException) {
                    throw ArchiveException("incomplete archive data (entry header truncated)")
                }
                if (size < 0) throw ArchiveException("invalid entry size: $path")
                val target = File(staging, path)
                target.parentFile?.mkdirs()
                val actual = copyWithHash(data, target, size)
                if (!actual.contentEquals(expectHash)) {
                    throw ArchiveException("checksum mismatch: $path (data corrupted or tampered)")
                }
                if (path == VaultMeta.FILE_NAME) {
                    meta = try {
                        VaultMeta.fromJson(target.readText())
                    } catch (e: Exception) {
                        throw ArchiveException("failed to parse meta.vault")
                    }
                }
                done++
                onProgress(done, count)
            }
            // 有效归档在最后一个条目后必须正好 EOF;多余字节 = 截断伪造/追加篡改
            if (data.read() != -1) throw ArchiveException("trailing garbage after archive entries (possibly tampered)")
            val m = meta ?: throw ArchiveException("archive is missing ${VaultMeta.FILE_NAME}")
            if (!vaultManager.verifyPassword(m, password)) throw WrongPasswordException()
            val dest = vaultManager.vaultDir(m.vaultId)
            if (dest.exists()) throw ArchiveException("a vault with the same name already exists; delete or rename it first")
            if (!staging.renameTo(dest)) throw ArchiveException("failed to move imported archive into place")
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
            if (!magic.contentEquals(MAGIC)) throw ArchiveException("not a valid .fvault archive")
            val version = data.readByte().toInt()
            if (version != VERSION) throw ArchiveException("unsupported archive version $version")
            val count = data.readInt()
            if (count < 0 || count > 1_000_000) throw ArchiveException("invalid archive entry count")
            return count
        } catch (e: EOFException) {
            throw ArchiveException("archive truncated (incomplete header)")
        } catch (e: IOException) {
            throw ArchiveException("failed to read archive: ${e.message}")
        }
    }

    /** 防 zip-slip:只允许相对路径段,拒绝空/绝对/../ 反斜杠。 */
    private fun sanitizePath(path: String): String {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\')) {
            throw ArchiveException("invalid path: $path")
        }
        if (path.split('/').any { it.isEmpty() || it == ".." }) {
            throw ArchiveException("invalid path: $path")
        }
        return path
    }

    private fun copyWithHash(data: DataInputStream, target: File, size: Long): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                val n = data.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw ArchiveException("incomplete archive data (entry content truncated)")
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
                if (data.read() < 0) throw ArchiveException("incomplete archive data")
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
