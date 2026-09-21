package io.vaultx.app.core.transfer

import io.vaultx.app.core.crypto.VaultCrypto
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultEntry
import io.vaultx.app.core.vault.VaultIndex
import io.vaultx.app.core.vault.VaultManager
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 导入/导出核心——纯 JVM,不碰 SAF(那是 [SafTransfer] 的事)。
 *
 * 导入管线:源 → 加密写入 blobs/<blobId> → 追加索引条目。
 * - 进度回调 50ms 节流(海量小文件下不至于引发 UI 重组风暴;收尾必达)
 * - [onCheckpoint] 每 1.5s 把当前索引持久化一次;取消/出错时终存一次——
 *   已完成条目不因取消回滚(blob 先于索引条目落盘,检查点永远落在一致态)
 * - 取消是协作式:每个分块边界查 [isCancelled];在写的那半截 blob 由单文件 catch 清理
 * - 传输未结束前拒绝重复启动([active] 标志)
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
        /** 为空文件夹建目录(默认空实现;SAF 端逐级建目录)。 */
        fun ensureDir(relPath: String) {}
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

    class TransferCancelledException(val completed: Int = 0) : Exception()

    // ---------------- 导入 ----------------

    /**
     * 把 sources 导入 index 的 [parentId] 之下(原地改 index)。
     * 重名自动 "(2)";目录源递归;[isCancelled] 命中即抛 [TransferCancelledException]
     * (携带已完成数);[onCheckpoint] 供调用方周期性把 index 落盘。
     */
    fun import(
        unlocked: UnlockedVault,
        sources: List<ImportSource>,
        parentId: String?,
        index: VaultIndex,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onCheckpoint: (VaultIndex) -> Unit = {},
        onFileStart: (name: String) -> Unit = {},
    ): ImportResult {
        check(activeFlag.compareAndSet(false, true)) { "transfer already active" }
        val total = sources.sumOf { countFiles(it) }
        val throttle = Throttle()
        var done = 0
        var imported = 0
        var lastCheckpoint = System.nanoTime()
        try {
            for (src in sources) {
                imported += importOne(unlocked, src, parentId, index, isCancelled, onFileStart) {
                    done++
                    throttle.emit(done, total, onProgress)
                    val now = System.nanoTime()
                    if (now - lastCheckpoint >= CHECKPOINT_NS) {
                        lastCheckpoint = now
                        onCheckpoint(index)
                    }
                }
            }
            onProgress(done, total)
            // skipped = 已计进度但未新增(同名同大小判重跳过)
            return ImportResult(imported = imported, skipped = done - imported)
        } catch (e: Throwable) {
            // 终存一次:已完成的条目不丢(其 blob 均已完整落盘,索引与之一致)
            runCatching { onCheckpoint(index) }
            onProgress(done, total)
            if (e is TransferCancelledException) throw TransferCancelledException(imported)
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
        isCancelled: () -> Boolean,
        onFileStart: (String) -> Unit,
        onFileDone: () -> Unit,
    ): Int {
        if (isCancelled()) throw TransferCancelledException()
        if (src.isDirectory) {
            val folder = index.addEntry(
                name = uniqueName(src.name, index, parentId),
                kind = MediaKind.FOLDER,
                parentId = parentId,
            )
            val children = src.children()
            var n = 0
            for (child in children) {
                n += importOne(unlocked, child, folder.id, index, isCancelled, onFileStart, onFileDone)
            }
            // 空目录在 countFiles 里占 1 份进度,这里补上;非空目录进度全部由子项贡献
            if (children.isEmpty()) onFileDone()
            return n
        }
        onFileStart(src.name)
        // 同名同大小 = 已存在的重复文件:跳过,不产 "(2)" 副本
        val isDup = src.sizeBytes > 0 && index.childrenOf(parentId).any {
            !it.isFolder && it.name == src.name && it.sizeBytes == src.sizeBytes
        }
        if (isDup) {
            onFileDone()
            return 0
        }
        val blobId = vaultManager.newBlobId()
        val sink = vaultManager.prepareBlobSink(unlocked.vaultId, blobId)
        try {
            sink.outputStream().use { raw ->
                unlocked.crypto.encryptingStream(raw, VaultCrypto.blobAd(unlocked.vaultId, blobId)).use { enc ->
                    src.open().use { ins -> copyChecked(ins, enc, isCancelled) }
                }
            }
        } catch (e: Throwable) {
            // 半截密文不能留:取消/IO 错都删(该文件尚未进索引,删了不会悬空)
            vaultManager.deleteBlob(unlocked.vaultId, blobId)
            throw e
        }
        index.addEntry(
            name = uniqueName(src.name, index, parentId),
            kind = kindOf(src.name, src.mimeType),
            blobId = blobId,
            // 索引记明文长度(导出显示/播放器 seek 用);源报不出大小时回落密文长度
            sizeBytes = if (src.sizeBytes >= 0) src.sizeBytes else vaultManager.blobSize(unlocked.vaultId, blobId),
            parentId = parentId,
            mimeType = src.mimeType,
        )
        onFileDone()
        return 1
    }

    /** 同目录内重名消解:"a.jpg" → "a (2).jpg" → "a (3).jpg"。[excludeId] 排除条目自身(改名/移动时)。 */
    internal fun uniqueName(name: String, index: VaultIndex, parentId: String?, excludeId: String? = null): String {
        // 外部名(SAF 显示名/用户输入)去路径分隔与控制字符——名字会进索引进而拼导出 relPath,
        // 不拦住 "../" 之类会破坏目录结构语义(甚至制造同名幻影)
        val clean = sanitizeName(name)
        val taken = index.childrenOf(parentId).asSequence()
            .filter { it.id != excludeId }
            .map { it.name }
            .toHashSet()
        if (clean !in taken) return clean
        val dot = clean.lastIndexOf('.')
        val stem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    internal fun sanitizeName(name: String): String {
        val stripped = name.map { if (it == '/' || it == '\\' || it < ' ') '_' else it }
            .joinToString("").trim()
        return if (stripped.isBlank() || stripped == "." || stripped == "..") "unnamed" else stripped
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
     * 解密导出:逐条目解密 blob → sink。文件夹条目经 [ExportSinkFactory.ensureDir]
     * 落地为空目录;单文件失败记录名字继续整批;sink.abort() 只清本次半成品;
     * [isCancelled] 命中即中止。
     */
    fun exportEntries(
        unlocked: UnlockedVault,
        entries: List<VaultEntry>,
        index: VaultIndex,
        sinkFactory: ExportSinkFactory,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFileStart: (name: String) -> Unit = {},
    ): ExportResult {
        check(activeFlag.compareAndSet(false, true)) { "transfer already active" }
        val total = entries.size
        val throttle = Throttle()
        var done = 0
        try {
            var exported = 0
            val failed = mutableListOf<String>()
            for (entry in entries) {
                if (isCancelled()) throw TransferCancelledException(done)
                onFileStart(entry.name)
                if (entry.isFolder) {
                    // 空文件夹也要在导出端出现,否则目录结构丢信息
                    runCatching { sinkFactory.ensureDir(relPathOf(entry, index)) }
                    done++
                    throttle.emit(done, total, onProgress)
                    continue
                }
                val blobId = entry.blobId
                if (blobId == null || !vaultManager.blobExists(unlocked.vaultId, blobId)) {
                    failed += entry.name
                    done++
                    throttle.emit(done, total, onProgress)
                    continue
                }
                val rel = relPathOf(entry, index)
                val sink = try {
                    sinkFactory.create(rel, entry.mimeType)
                } catch (e: Exception) {
                    failed += entry.name
                    done++
                    throttle.emit(done, total, onProgress)
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
                    throw TransferCancelledException(done)
                } catch (e: Exception) {
                    sink.abort()
                    failed += entry.name
                }
                done++
                throttle.emit(done, total, onProgress)
            }
            onProgress(done, total)
            return ExportResult(exported = exported, failedNames = failed)
        } finally {
            activeFlag.set(false)
        }
    }

    /** 导出相对路径:folder/sub/name.ext(目录链从索引回溯)。 */
    private fun relPathOf(entry: VaultEntry, index: VaultIndex): String {
        val parts = mutableListOf(sanitizeName(entry.name))
        val seen = hashSetOf(entry.id)
        var cur = entry.parentId
        while (cur != null && cur !in seen) {
            val folder = index.find(cur) ?: break
            seen.add(folder.id)
            parts.add(0, sanitizeName(folder.name))
            cur = folder.parentId
        }
        return parts.joinToString("/")
    }

    /** 50ms 节流:进度回调不至于每个文件都推一次 StateFlow。 */
    private class Throttle(private val minIntervalNs: Long = 50_000_000L) {
        private var last = 0L
        fun emit(done: Int, total: Int, sink: (Int, Int) -> Unit) {
            val now = System.nanoTime()
            if (now - last >= minIntervalNs) {
                last = now
                sink(done, total)
            }
        }
    }

    companion object {
        private const val CHECKPOINT_NS = 1_500_000_000L
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")
        private val AUDIO_EXTS = setOf("mp3", "aac", "flac", "wav", "ogg", "m4a", "opus", "wma")
    }
}
