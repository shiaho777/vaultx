package io.vaultx.app.core.transfer

import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultIndex
import io.vaultx.app.core.vault.VaultManager
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导入/导出核心——纯 JVM,不碰 SAF(那是 [SafTransfer] 的事)。
 *
 * 导入管线:源 → 加密写入 blobs/<blobId> → 追加索引条目。
 * 取消是协作式:每个分块边界查 [isCancelled];命中或出错即清掉半截 blob 与本次已建 blob。
 * 传输未结束前拒绝重复启动([active] 标志)。
 */
class TransferEngine(private val vaultManager: VaultManager) {

    private val activeFlag = AtomicBoolean(false)
    val active: Boolean get() = activeFlag.get()

    // ---------------- 抽象端口 ----------------

    /** 一个导入源(文件或目录;目录经 [children] 递归)。 */
    interface ImportSource {
        val name: String
        val sizeBytes: Long
        val mimeType: String?
        val isDirectory: Boolean
        /** 文件源:打开字节流。 */
        fun open(): InputStream
        /** 目录源:列子项。 */
        fun children(): List<ImportSource>
    }

    /** 导出目的端工厂:按相对路径+mime 建 sink。 */
    interface ExportSinkFactory {
        fun create(relPath: String, mimeType: String?): ExportSink
    }

    interface ExportSink {
        val output: OutputStream
        /** 正常写完提交。 */
        fun commit()
        /** 失败/取消时清理半成品(只清本次创建的目标)。 */
        fun abort()
    }

    data class ImportResult(val imported: Int, val skipped: Int = 0)

    /** 导出结果:单文件失败只记录不打断整批。 */
    data class ExportResult(
        val exported: Int,
        val failedNames: List<String>,
    ) {
        val failed: Int get() = failedNames.size
    }

    class TransferCancelledException : Exception()

    // ---------------- 导入 ----------------

    /**
     * 把 sources 导入 index 的 [parentId] 之下(原地改 index;持久化由调用方做)。
     * 重名自动 "(2)";目录源递归;[isCancelled] 命中即回滚本次已建 blob 并抛
     * [TransferCancelledException]。
     */
    fun import(
        unlocked: UnlockedVault,
        sources: List<ImportSource>,
        parentId: String?,
        index: VaultIndex,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult {
        check(activeFlag.compareAndSet(false, true)) { "transfer already active" }
        val createdBlobs = mutableListOf<String>()
        try {
            var done = 0
            val total = sources.sumOf { countFiles(it) }
            var imported = 0
            for (src in sources) {
                imported += importOne(unlocked, src, parentId, index, createdBlobs, isCancelled, { d -> onProgress(done + d, total) }).also { done += it }
            }
            return ImportResult(imported = imported)
        } catch (e: TransferCancelledException) {
            createdBlobs.forEach { vaultManager.deleteBlob(unlocked.vaultId, it) }
            throw e
        } catch (e: Throwable) {
            createdBlobs.forEach { vaultManager.deleteBlob(unlocked.vaultId, it) }
            throw e
        } finally {
            activeFlag.set(false)
        }
    }

    private fun countFiles(src: ImportSource): Int =
        if (src.isDirectory) src.children().sumOf { countFiles(it) }.coerceAtLeast(1)
        else 1

    private fun importOne(
        unlocked: UnlockedVault,
        src: ImportSource,
        parentId: String?,
        index: VaultIndex,
        createdBlobs: MutableList<String>,
        isCancelled: () -> Boolean,
        onProgress: (doneDelta: Int) -> Unit,
    ): Int {
        if (isCancelled()) throw TransferCancelledException()
        if (src.isDirectory) {
            val folder = index.addEntry(
                name = uniqueName(src.name, index, parentId),
                kind = MediaKind.FOLDER,
                parentId = parentId,
            )
            var n = 0
            for (child in src.children()) {
                n += importOne(unlocked, child, folder.id, index, createdBlobs, isCancelled, onProgress)
            }
            return n
        }
        val blobId = vaultManager.newBlobId()
        val sink = vaultManager.prepareBlobSink(unlocked.vaultId, blobId)
        try {
            sink.outputStream().use { raw ->
                unlocked.crypto.encryptingStream(raw, io.vaultx.app.core.crypto.VaultCrypto.blobAd(unlocked.vaultId, blobId)).use { enc ->
                    src.open().use { ins -> copyChecked(ins, enc, isCancelled) }
                }
            }
        } catch (e: Throwable) {
            // 半截密文不能留:取消/IO 错都删
            vaultManager.deleteBlob(unlocked.vaultId, blobId)
            throw e
        }
        createdBlobs.add(blobId)
        index.addEntry(
            name = uniqueName(src.name, index, parentId),
            kind = kindOf(src.name, src.mimeType),
            blobId = blobId,
            // 索引记明文长度(导出显示/播放器 seek 用);源报不出大小时回落密文长度
            sizeBytes = if (src.sizeBytes >= 0) src.sizeBytes else vaultManager.blobSize(unlocked.vaultId, blobId),
            parentId = parentId,
            mimeType = src.mimeType,
        )
        onProgress(1)
        return 1
    }

    /** 同目录内重名消解:"a.jpg" → "a (2).jpg" → "a (3).jpg"。文件夹不参与文件冲突。 */
    internal fun uniqueName(name: String, index: VaultIndex, parentId: String?): String {
        val taken = index.childrenOf(parentId).map { it.name }.toSet()
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    private fun kindOf(name: String, mime: String?): MediaKind = when {
        mime?.startsWith("image/") == true -> MediaKind.IMAGE
        mime?.startsWith("video/") == true -> MediaKind.VIDEO
        mime?.startsWith("audio/") == true -> MediaKind.AUDIO
        else -> when (name.substringAfterLast('.', "").lowercase()) {
            in IMAGE_EXTS -> MediaKind.IMAGE
            in VIDEO_EXTS -> MediaKind.VIDEO
            in AUDIO_EXTS -> MediaKind.AUDIO
            else -> MediaKind.OTHER
        }
    }

    private fun copyChecked(ins: InputStream, out: OutputStream, isCancelled: () -> Boolean) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            val n = ins.read(buf)
            if (n < 0) return
            out.write(buf, 0, n)
        }
    }

    // ---------------- 导出 ----------------

    /**
     * 解密导出:逐条目解密 blob → sink。单文件失败记录名字继续整批;
     * sink.abort() 只清本次半成品;[isCancelled] 命中即中止。
     */
    fun exportEntries(
        unlocked: UnlockedVault,
        entries: List<io.vaultx.app.core.vault.VaultEntry>,
        index: VaultIndex,
        sinkFactory: ExportSinkFactory,
        isCancelled: () -> Boolean = { false },
    ): ExportResult {
        check(activeFlag.compareAndSet(false, true)) { "transfer already active" }
        try {
            var exported = 0
            val failed = mutableListOf<String>()
            for (entry in entries) {
                if (isCancelled()) throw TransferCancelledException()
                if (entry.isFolder) continue
                val blobId = entry.blobId
                if (blobId == null || !vaultManager.blobExists(unlocked.vaultId, blobId)) {
                    failed += entry.name
                    continue
                }
                val rel = relPathOf(entry, index)
                val sink = try {
                    sinkFactory.create(rel, entry.mimeType)
                } catch (e: Exception) {
                    failed += entry.name
                    continue
                }
                try {
                    vaultManager.openBlobStream(unlocked, blobId).use { dec ->
                        sink.output.use { out -> copyChecked(dec, out, isCancelled) }
                    }
                    sink.commit()
                    exported++
                } catch (e: TransferCancelledException) {
                    sink.abort()
                    throw e
                } catch (e: Exception) {
                    sink.abort()
                    failed += entry.name
                }
            }
            return ExportResult(exported = exported, failedNames = failed)
        } finally {
            activeFlag.set(false)
        }
    }

    /** 导出相对路径:folder/sub/name.ext(目录链从索引回溯)。 */
    private fun relPathOf(entry: io.vaultx.app.core.vault.VaultEntry, index: VaultIndex): String {
        val parts = mutableListOf(entry.name)
        var cur = entry.parentId
        while (cur != null) {
            val folder = index.find(cur) ?: break
            parts.add(0, folder.name)
            cur = folder.parentId
        }
        return parts.joinToString("/")
    }

    companion object {
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")
        private val AUDIO_EXTS = setOf("mp3", "aac", "flac", "wav", "ogg", "m4a", "opus", "wma")
    }
}
