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
        /** 当前文件已拷贝字节数(50ms 节流;收尾必达)。大文件进度不再长期停在 0/1。 */
        onBytes: (bytes: Long) -> Unit = {},
    ): ImportResult {
        check(activeFlag.compareAndSet(false, true)) { "transfer already active" }
        val total = sources.sumOf { countFiles(it) }
        val throttle = Throttle()
        val byteThrottle = Throttle()
        // 名字表按目录预建:判重/重名消解不再逐文件全表扫描(O(n²) → O(n))。
        // 本次导入是索引唯一写者(调用方全程持索引锁),addEntry 后同步登记即始终一致。
        val siblings = HashMap<String?, MutableList<VaultEntry>>()
        // 回收站条目不参与判重/重名消解——否则与已删文件同名同大小的新导入被静默跳过,
        // 且与 uniqueName(经 childrenOf 排除回收站)口径相反
        index.entries.filter { !it.isTrashed }
            .forEach { siblings.getOrPut(it.parentId) { mutableListOf() }.add(it) }
        fun addTracked(
            name: String,
            kind: MediaKind,
            blobId: String? = null,
            sizeBytes: Long = 0,
            parentId: String?,
            mimeType: String? = null,
        ): VaultEntry {
            val e = index.addEntry(name, kind, blobId, sizeBytes, parentId, mimeType)
            siblings.getOrPut(parentId) { mutableListOf() }.add(e)
            return e
        }
        var done = 0
        var imported = 0
        var lastCheckpoint = System.nanoTime()
        try {
            for (src in sources) {
                imported += importOne(unlocked, src, parentId, siblings, isCancelled, onFileStart, ::addTracked, byteThrottle, onBytes) {
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
        siblings: MutableMap<String?, MutableList<VaultEntry>>,
        isCancelled: () -> Boolean,
        onFileStart: (String) -> Unit,
        addTracked: (name: String, kind: MediaKind, blobId: String?, sizeBytes: Long, parentId: String?, mimeType: String?) -> VaultEntry,
        byteThrottle: Throttle,
        onBytes: (Long) -> Unit,
        onFileDone: () -> Unit,
    ): Int {
        if (isCancelled()) throw TransferCancelledException()
        val brothers = siblings.getOrPut(parentId) { mutableListOf() }
        if (src.isDirectory) {
            val folder = addTracked(uniqueIn(src.name, brothers), MediaKind.FOLDER, null, 0, parentId, null)
            val children = src.children()
            var n = 0
            for (child in children) {
                n += importOne(unlocked, child, folder.id, siblings, isCancelled, onFileStart, addTracked, byteThrottle, onBytes, onFileDone)
            }
            // 空目录在 countFiles 里占 1 份进度,这里补上;非空目录进度全部由子项贡献
            if (children.isEmpty()) onFileDone()
            return n
        }
        onFileStart(src.name)
        // 同名同大小 = 已存在的重复文件:跳过,不产 "(2)" 副本
        val isDup = src.sizeBytes > 0 && brothers.any {
            !it.isFolder && it.name == src.name && it.sizeBytes == src.sizeBytes
        }
        if (isDup) {
            onFileDone()
            return 0
        }
        val blobId = vaultManager.newBlobId()
        val sink = vaultManager.prepareBlobSink(unlocked.vaultId, blobId)
        onBytes(0)
        var copied = 0L
        try {
            sink.outputStream().use { raw ->
                unlocked.crypto.encryptingStream(raw, VaultCrypto.blobAd(unlocked.vaultId, blobId)).use { enc ->
                    src.open().use { ins ->
                        copyChecked(ins, enc, isCancelled) { n ->
                            copied += n
                            byteThrottle.emitValue(copied, onBytes)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // 半截密文不能留:取消/IO 错都删(该文件尚未进索引,删了不会悬空)
            vaultManager.deleteBlob(unlocked.vaultId, blobId)
            throw e
        }
        onBytes(copied) // 收尾必达:小文件不足节流间隔也要报出最终字节数
        addTracked(
            uniqueIn(src.name, brothers),
            kindOf(src.name, src.mimeType),
            blobId,
            // 索引记明文长度(导出显示/播放器 seek 用);源报不出大小时用实际
            // 拷贝的明文字节数——绝不能回落密文长度(Tink 头+分块 tag 会让
            // 播放器按错误长度 seek,静默播放失败)
            if (src.sizeBytes >= 0) src.sizeBytes else copied,
            parentId,
            src.mimeType,
        )
        onFileDone()
        return 1
    }

    /** 同目录内重名消解:"a.jpg" → "a (2).jpg" → "a (3).jpg"。[excludeId] 排除条目自身(改名/移动时)。 */
    internal fun uniqueName(name: String, index: VaultIndex, parentId: String?, excludeId: String? = null): String {
        val taken = index.childrenOf(parentId).asSequence()
            .filter { it.id != excludeId }
            .map { it.name }
            .toHashSet()
        return resolveUnique(name, taken)
    }

    /** 导入热路径版:名字集来自预建的目录兄弟表,免全表扫描。 */
    private fun uniqueIn(name: String, brothers: List<VaultEntry>): String =
        resolveUnique(name, brothers.mapTo(HashSet()) { it.name })

    private fun resolveUnique(name: String, taken: Set<String>): String {
        // 外部名(SAF 显示名/用户输入)去路径分隔与控制字符——名字会进索引进而拼导出 relPath,
        // 不拦住 "../" 之类会破坏目录结构语义(甚至制造同名幻影)
        val clean = sanitizeName(name)
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

    /** 文件名/MIME → 条目类型(导入与 .vlt 解密入库共用)。 */
    fun kindOf(name: String, mime: String?): MediaKind = when {
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

    private fun copyChecked(
        ins: InputStream,
        out: OutputStream,
        isCancelled: () -> Boolean,
        onChunk: (Int) -> Unit = {},
    ) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            if (isCancelled()) throw TransferCancelledException()
            val n = ins.read(buf)
            if (n < 0) return
            out.write(buf, 0, n)
            onChunk(n)
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
        /** 当前文件已导出字节数(50ms 节流;收尾必达)。 */
        onBytes: (bytes: Long) -> Unit = {},
    ): ExportResult {
        check(activeFlag.compareAndSet(false, true)) { "transfer already active" }
        val total = entries.size
        val throttle = Throttle()
        val byteThrottle = Throttle()
        val byId = index.entries.associateBy { it.id }
        var done = 0
        try {
            var exported = 0
            val failed = mutableListOf<String>()
            for (entry in entries) {
                if (isCancelled()) throw TransferCancelledException(done)
                onFileStart(entry.name)
                if (entry.isFolder) {
                    // 空文件夹也要在导出端出现,否则目录结构丢信息;建目录失败计入失败名
                    runCatching { sinkFactory.ensureDir(relPathOf(entry, byId)) }
                        .onFailure { failed += entry.name }
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
                val rel = relPathOf(entry, byId)
                val sink = try {
                    sinkFactory.create(rel, entry.mimeType)
                } catch (e: Exception) {
                    failed += entry.name
                    done++
                    throttle.emit(done, total, onProgress)
                    continue
                }
                try {
                    onBytes(0)
                    var copied = 0L
                    vaultManager.openBlobStream(unlocked, blobId).use { dec ->
                        sink.output.use { out ->
                            copyChecked(dec, out, isCancelled) { n ->
                                copied += n
                                byteThrottle.emitValue(copied, onBytes)
                            }
                        }
                    }
                    onBytes(copied)
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

    /**
     * 导出相对路径:folder/sub/name.ext(目录链从索引回溯)。
     * [byId] 预建 id→entry 映射:逐条目 index.find 线性扫是 O(n²),万级导出分钟级卡顿。
     */
    private fun relPathOf(entry: VaultEntry, byId: Map<String, VaultEntry>): String {
        val parts = mutableListOf(sanitizeName(entry.name))
        val seen = hashSetOf(entry.id)
        var cur = entry.parentId
        while (cur != null && cur !in seen) {
            val folder = byId[cur] ?: break
            seen.add(folder.id)
            parts.add(0, sanitizeName(folder.name))
            cur = folder.parentId
        }
        return parts.joinToString("/")
    }

    /** 50ms 节流:进度回调不至于每个文件/分块都推一次 StateFlow。 */
    private class Throttle(private val minIntervalNs: Long = 50_000_000L) {
        private var last = 0L
        fun emit(done: Int, total: Int, sink: (Int, Int) -> Unit) {
            val now = System.nanoTime()
            if (now - last >= minIntervalNs) {
                last = now
                sink(done, total)
            }
        }
        fun emitValue(value: Long, sink: (Long) -> Unit) {
            val now = System.nanoTime()
            if (now - last >= minIntervalNs) {
                last = now
                sink(value)
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
