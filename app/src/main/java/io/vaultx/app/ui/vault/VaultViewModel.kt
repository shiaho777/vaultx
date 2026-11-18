package io.vaultx.app.ui.vault

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.core.state.AppSettings
import io.vaultx.app.core.transfer.TransferEngine
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultEntry
import io.vaultx.app.core.vault.VaultIndex
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 排序档位。 */
enum class SortBy(@StringRes val labelRes: Int) {
    NAME(R.string.vm_sort_name), TIME(R.string.vm_sort_time), SIZE(R.string.vm_sort_size), KIND(R.string.vm_sort_kind),
}

/** 传输进度。[fileBytes] = 当前文件已传输字节数(大文件不再长期停在 0/1)。 */
data class TransferState(val done: Int, val total: Int, val label: String, val fileBytes: Long = 0)

/**
 * 库内文件浏览/操作的状态中枢。所有磁盘+解密动作都在 IO 线程。
 *
 * 索引一致性(竞态防线):
 * - 所有索引读-改-写都串行经过 [indexMutex];导入全程持锁,期间发起的
 *   改名/移动/删除排队,取锁后重读最新索引再改——杜绝"导入终存覆盖中途修改"
 * - 删除顺序:先 saveIndex 落盘成功,再删 blob——保存失败时文件完好保留
 * - 取锁后重新校验会话(等待期间可能已被自动锁定)
 * - 传输未结束前拒绝启动新传输
 */
class VaultViewModel(
    private val container: AppContainer,
    val vaultId: String,
) : ViewModel() {

    private val unlocked: UnlockedVault? get() = container.session.get(vaultId)

    /** 本地化文案:经容器的应用级 Context 读取字符串资源。 */
    private fun str(@StringRes resId: Int, vararg args: Any?): String =
        if (args.isEmpty()) container.context.getString(resId)
        else container.context.getString(resId, *args)

    private val _index = MutableStateFlow<VaultIndex?>(null)
    val index: StateFlow<VaultIndex?> = _index

    /** 当前所在文件夹链(空 = 根目录)。 */
    private val _folderStack = MutableStateFlow<List<VaultEntry>>(emptyList())
    val folderStack: StateFlow<List<VaultEntry>> = _folderStack

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** 只看收藏(跨目录)。与搜索/标签筛选互斥,切换时清其他。 */
    private val _starredOnly = MutableStateFlow(false)
    val starredOnly: StateFlow<Boolean> = _starredOnly

    /** 标签筛选(跨目录;null = 不筛选)。 */
    private val _tagFilter = MutableStateFlow<String?>(null)
    val tagFilter: StateFlow<String?> = _tagFilter

    /**
     * .vlt 导入队列由 VM 持有(不放组合 remember):Activity 重建时
     * 组合态清零会让剩余文件静默不导入;VM 在导航栈内按 key 缓存,重建后仍在。
     */
    private val _vltQueue = MutableStateFlow<List<TransferEngine.ImportSource>>(emptyList())
    val vltQueue: StateFlow<List<TransferEngine.ImportSource>> = _vltQueue
    private val _vltTarget = MutableStateFlow<String?>(null)
    val vltTarget: StateFlow<String?> = _vltTarget

    fun enqueueVlt(sources: List<TransferEngine.ImportSource>, targetFolder: String?) {
        if (sources.isEmpty()) return
        _vltTarget.value = targetFolder
        _vltQueue.value = _vltQueue.value + sources
    }

    fun popVlt() { _vltQueue.value = _vltQueue.value.drop(1) }
    fun clearVltQueue() { _vltQueue.value = emptyList(); _vltTarget.value = null }

    private val _sortBy = MutableStateFlow(
        runCatching { SortBy.valueOf(container.settings.sortBy.value) }.getOrDefault(SortBy.NAME),
    )
    val sortBy: StateFlow<SortBy> = _sortBy

    /** 当前档方向是否翻转(会话内状态,不持久化——默认方向已足够直觉)。 */
    private val _sortReversed = MutableStateFlow(false)
    val sortReversed: StateFlow<Boolean> = _sortReversed

    /** 网格/列表视图(持久化)。 */
    private val _viewMode = MutableStateFlow(container.settings.viewMode.value)
    val viewMode: StateFlow<String> = _viewMode

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /**
     * 备份到期提醒:超过阈值未导出 .fvault(或从未备份)。
     * 诱骗会话恒 false——备份时间属于真库管理信息,不能向诱骗持有者泄露。
     */
    val backupDue: StateFlow<Boolean> = combine(
        container.settings.backupTimes,
        _index,
    ) { times, idx ->
        val u = unlocked
        if (u == null || u.viaDecoy || idx == null || idx.entries.isEmpty()) {
            false
        } else {
            val last = times[vaultId] ?: 0L
            System.currentTimeMillis() - last > AppSettings.BACKUP_REMINDER_DAYS * 24 * 3600 * 1000
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 非错误性结果提示(如"已导出 8 项,1 个失败")。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** 待最终落删的删除批次(撤销窗口内 blob 完好)。 */
    data class PendingDeletion(val entries: List<VaultEntry>, val blobIds: List<String>)

    private val _undoDelete = MutableStateFlow<PendingDeletion?>(null)
    val undoDelete: StateFlow<PendingDeletion?> = _undoDelete

    // 与设置页共享同一把锁(容器按 vaultId 提供):索引读改写全局串行
    private val indexMutex: Mutex = container.indexMutexFor(vaultId)
    private val cancelFlag = AtomicBoolean(false)
    // 传输入口 CAS 占位:_transfer 的可见检查与 IO 协程置位之间有窗口,快速双击可并发
    private val transferBusy = AtomicBoolean(false)
    private var undoJob: kotlinx.coroutines.Job? = null
    // 缩略图标记合并落盘:滚动画廊时逐张全量重加密索引会造成 N 次写放大
    private val thumbFlushJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)

    val currentFolderId: String? get() = _folderStack.value.lastOrNull()?.id
    val viaDecoy: Boolean get() = unlocked?.viaDecoy == true

    init {
        refresh()
        // 孤儿 blob 清扫(取消/崩溃残迹)。诱骗库跳过:两套索引各只覆盖自己的 blob,
        // 单边清扫会误删另一边的数据
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                if (!u.meta.hasDecoy) {
                    runCatching {
                        container.vaultManager.sweepOrphanBlobs(vaultId, container.vaultManager.loadIndex(u))
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = runCatching { container.vaultManager.loadIndex(u) }
                    .getOrElse {
                        _error.value = str(R.string.vm_error_index_load, it.message)
                        return@withLock
                    }
                // 回收站保留期到期:惰性清扫(进库/刷新时),先落盘再删 blob
                purgeExpiredTrash(u, idx)
                _index.value = idx
                // 栈里已被删/已进回收站的文件夹清掉
                val ids = idx.entries.filter { !it.isTrashed }.map { it.id }.toSet()
                _folderStack.value = _folderStack.value.filter { it.id in ids }
            }
        }
    }

    /** 回收站到期清扫:trashedAt 超过保留期的条目彻底删除。须持 indexMutex。 */
    private fun purgeExpiredTrash(u: UnlockedVault, idx: VaultIndex) {
        val now = System.currentTimeMillis()
        val expired = idx.entries.filter { (it.trashedAt ?: Long.MAX_VALUE) < now - VaultIndex.TRASH_RETENTION_MS }
        if (expired.isEmpty()) return
        val doomed = expired.flatMap { idx.descendantIds(it.id) }.toSet() + expired.map { it.id }
        val blobs = idx.entries.filter { it.id in doomed }.mapNotNull { it.blobId }
        idx.entries.removeAll { it.id in doomed }
        if (persist(u, idx)) {
            blobs.forEach { runCatching { container.vaultManager.deleteBlob(u.vaultId, it) } }
        }
    }

    /** 当前应展示的条目:搜索态全局命中;否则当前目录子项;排序应用。 */
    fun visibleEntries(): List<VaultEntry> {
        val idx = _index.value ?: return emptyList()
        val q = _query.value
        val tag = _tagFilter.value
        val base = when {
            q.isNotBlank() -> idx.search(q)
            tag != null -> idx.entriesWithTag(tag)
            _starredOnly.value -> idx.starredEntries()
            else -> idx.childrenOf(currentFolderId)
        }
        return sortEntries(base)
    }

    private fun sortEntries(list: List<VaultEntry>): List<VaultEntry> {
        val base = compareByDescending<VaultEntry> { it.isFolder }
            .thenComparing { a, b ->
                // 各档默认方向:名称升序,时间/大小降序(新的/大的在前),类型升序
                val c = when (_sortBy.value) {
                    SortBy.NAME -> NAME_COLLATOR.compare(a.name, b.name)
                    SortBy.TIME -> b.updatedAt.compareTo(a.updatedAt)
                    SortBy.SIZE -> b.sizeBytes.compareTo(a.sizeBytes)
                    SortBy.KIND -> a.kind.compareTo(b.kind)
                }
                if (_sortReversed.value) -c else c
            }
            // 等值键按名称决胜,保证顺序稳定可预期(否则每次重组可能换位)
            .thenComparing { a, b -> NAME_COLLATOR.compare(a.name, b.name) }
        return list.sortedWith(base)
    }

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotBlank()) { _starredOnly.value = false; _tagFilter.value = null }
    }

    /** 只看收藏开关;开启时退出搜索/标签筛选。 */
    fun setStarredOnly(on: Boolean) {
        _starredOnly.value = on
        if (on) { _query.value = ""; _tagFilter.value = null }
    }

    /** 标签筛选;null 清除。开启时退出搜索/收藏筛选。 */
    fun setTagFilter(tag: String?) {
        _tagFilter.value = tag
        if (tag != null) { _query.value = ""; _starredOnly.value = false }
    }

    /** 收藏/取消收藏(批量)。 */
    fun setStarred(ids: Set<String>, starred: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                var changed = false
                idx.entries.replaceAll { e ->
                    if (e.id in ids && e.starred != starred) { changed = true; e.copy(starred = starred) } else e
                }
                if (changed) persist(u, idx)
            }
        }
    }

    /** 给条目加标签(归一、去重、限量);已存在则无操作。 */
    fun addTag(id: String, rawTag: String) {
        val tag = VaultIndex.normalizeTag(rawTag) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                val i = idx.entries.indexOfFirst { it.id == id }
                if (i < 0) return@withLock
                val e = idx.entries[i]
                if (tag in e.tags) return@withLock
                if (e.tags.size >= VaultIndex.MAX_TAGS_PER_ENTRY) {
                    _error.value = str(R.string.vm_error_tag_limit, VaultIndex.MAX_TAGS_PER_ENTRY)
                    return@withLock
                }
                idx.entries[i] = e.copy(tags = e.tags + tag)
                persist(u, idx)
            }
        }
    }

    /** 移除条目上的标签。 */
    fun removeTag(id: String, tag: String) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                val i = idx.entries.indexOfFirst { it.id == id }
                if (i < 0) return@withLock
                val e = idx.entries[i]
                if (tag !in e.tags) return@withLock
                idx.entries[i] = e.copy(tags = e.tags - tag)
                persist(u, idx)
                // 被筛掉的标签若已无任何条目,清掉筛选避免空视图困惑
                if (_tagFilter.value == tag && idx.entries.none { tag in it.tags && !it.isTrashed }) {
                    _tagFilter.value = null
                }
            }
        }
    }
    /** 选排序档位:再点当前档 → 翻转方向;换档 → 该档默认方向。 */
    fun setSortBy(s: SortBy) {
        if (s == _sortBy.value) {
            _sortReversed.value = !_sortReversed.value
        } else {
            _sortBy.value = s
            _sortReversed.value = false
        }
        viewModelScope.launch { container.settings.setSortBy(s.name) }
    }
    fun setViewMode(mode: String) {
        _viewMode.value = mode
        viewModelScope.launch { container.settings.setViewMode(mode) }
    }
    fun clearError() { _error.value = null }
    fun clearNotice() { _notice.value = null }

    fun openFolder(entry: VaultEntry) {
        if (entry.isFolder) {
            _folderStack.value = _folderStack.value + entry
            // 从搜索/收藏/标签结果点进文件夹时退出筛选态,展示其内容
            _query.value = ""
            _starredOnly.value = false
            _tagFilter.value = null
        }
    }

    /** 返回上一层;已在根目录返回 false(交给系统返回)。 */
    fun navigateUp(): Boolean {
        if (_folderStack.value.isEmpty()) return false
        _folderStack.value = _folderStack.value.dropLast(1)
        return true
    }

    fun jumpToBreadcrumb(index: Int) {
        _folderStack.value = _folderStack.value.take(index + 1)
    }

    /** 立即锁定本库(清 VMK、弹回锁定界面)。 */
    fun lockNow() {
        container.session.lock(vaultId)
    }

    // ---------------- 选择 ----------------

    fun toggleSelect(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun selectAll() {
        _selection.value = visibleEntries().map { it.id }.toSet()
    }

    /**
     * 单文件加密导出为 `.vlt`(独立于库密码的一次性密码,可安全传给他人)。
     * 明文只在流里过,不落地;密码数组用完即清零。
     */
    fun exportAsVlt(entry: VaultEntry, password: CharArray, output: java.io.OutputStream) {
        if (!transferBusy.compareAndSet(false, true)) {
            password.fill('\u0000')
            _error.value = str(R.string.vm_transfer_busy)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 1, str(R.string.vm_transfer_encrypt_export))
            try {
                val u = unlocked ?: return@launch
                val blobId = entry.blobId ?: throw IllegalStateException("folder has no blob")
                output.use { out ->
                    container.vaultManager.openBlobStream(u, blobId).use { dec ->
                        io.vaultx.app.core.crypto.PortableCipher.openEncryptingStream(password, out).use { enc ->
                            // 分块拷贝:大文件可取消、有字节进度(整段 encryptTo 两者皆无)
                            val buf = ByteArray(64 * 1024)
                            var size = 0L
                            var lastEmit = 0L
                            while (true) {
                                if (cancelFlag.get()) throw TransferEngine.TransferCancelledException()
                                val n = dec.read(buf)
                                if (n < 0) break
                                enc.write(buf, 0, n)
                                size += n
                                val now = System.nanoTime()
                                if (now - lastEmit >= 50_000_000L) {
                                    lastEmit = now
                                    _transfer.value = _transfer.value?.copy(fileBytes = size)
                                }
                            }
                            _transfer.value = _transfer.value?.copy(fileBytes = size)
                        }
                    }
                }
                _notice.value = str(R.string.vm_notice_exported_vlt, entry.name)
            } catch (e: TransferEngine.TransferCancelledException) {
                _notice.value = str(R.string.vm_notice_export_cancelled)
            } catch (e: Throwable) {
                _error.value = str(R.string.vm_error_export, e.message)
            } finally {
                transferBusy.set(false)
                password.fill('\u0000')
                _transfer.value = null
            }
        }
    }

    // ---------------- 导入 ----------------

    /**
     * 导入 `.vlt` 便携密文并解密入库:解密流 → blob 加密,明文只过流不落地。
     * 名字剥 `.vlt` 后缀(大小写不敏感)再走重名消解;密码数组由本方法接管清零。
     */
    /** [onDone] 结果:null = 成功;非空 = 失败原因(供对话框内联展示,取消与错密码区分开)。 */
    fun importVlt(
        source: TransferEngine.ImportSource,
        password: CharArray,
        intoFolderId: String?,
        onDone: (String?) -> Unit = {},
    ) {
        if (!transferBusy.compareAndSet(false, true)) {
            password.fill('\u0000')
            _error.value = str(R.string.vm_transfer_busy)
            onDone(str(R.string.vm_transfer_busy))
            return
        }
        val targetFolder = intoFolderId ?: currentFolderId
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 1, str(R.string.vm_transfer_decrypt_import))
            var failure: String? = null
            try {
                indexMutex.withLock {
                    val u = unlocked ?: return@withLock
                        val idx = container.vaultManager.loadIndex(u)
                    val baseName = source.name.let {
                        if (it.endsWith(".vlt", ignoreCase = true)) it.dropLast(4) else it
                    }
                    val name = container.transferEngine.uniqueName(baseName, idx, targetFolder)
                    val blobId = container.vaultManager.newBlobId()
                    val sink = container.vaultManager.prepareBlobSink(u.vaultId, blobId)
                    var size = 0L
                    try {
                        source.open().use { ins ->
                            io.vaultx.app.core.crypto.PortableCipher.openDecryptingStream(password, ins).use { dec ->
                                sink.outputStream().use { raw ->
                                    u.crypto.encryptingStream(
                                        raw, io.vaultx.app.core.crypto.VaultCrypto.blobAd(u.vaultId, blobId),
                                    ).use { enc ->
                                        // 分块拷贝:大文件可取消、有字节进度(整段 copyTo 两者皆无)
                                        val buf = ByteArray(64 * 1024)
                                        var lastEmit = 0L
                                        while (true) {
                                            if (cancelFlag.get()) throw TransferEngine.TransferCancelledException()
                                            val n = dec.read(buf)
                                            if (n < 0) break
                                            enc.write(buf, 0, n)
                                            size += n
                                            val now = System.nanoTime()
                                            if (now - lastEmit >= 50_000_000L) {
                                                lastEmit = now
                                                _transfer.value = _transfer.value?.copy(fileBytes = size)
                                            }
                                        }
                                        _transfer.value = _transfer.value?.copy(fileBytes = size)
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        container.vaultManager.deleteBlob(u.vaultId, blobId)
                        throw e
                    }
                    idx.addEntry(
                        name = name,
                        kind = container.transferEngine.kindOf(baseName, null),
                        blobId = blobId,
                        sizeBytes = size,
                        parentId = targetFolder,
                    )
                    persist(u, idx)
                    _notice.value = str(R.string.vm_notice_imported_vlt, name)
                }
            } catch (e: io.vaultx.app.core.crypto.WrongPasswordException) {
                failure = str(R.string.vm_error_wrong_password)
                _error.value = failure
            } catch (e: TransferEngine.TransferCancelledException) {
                // 用户主动取消 = 结束这个文件,按成功弹出队列(不算失败)
                _notice.value = str(R.string.vm_notice_import_cancelled)
            } catch (e: Throwable) {
                failure = str(R.string.vm_error_import, e.message)
                _error.value = failure
            } finally {
                transferBusy.set(false)
                password.fill('\u0000')
                _transfer.value = null
                withContext(Dispatchers.Main) { onDone(failure) }
            }
        }
    }

    /** [intoFolderId] 指定目标文件夹("导入到此处"),null = 当前目录。 */
    fun import(sources: List<TransferEngine.ImportSource>, intoFolderId: String? = null) {
        if (!transferBusy.compareAndSet(false, true)) {
            _error.value = str(R.string.vm_transfer_busy)
            return
        }
        val targetFolder = intoFolderId ?: currentFolderId
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 0, str(R.string.vm_transfer_importing))
            // 导入全程持索引锁:中途的改名/删除排队,取锁后读到的是导入后的最新索引
            indexMutex.withLock {
                try {
                    val u = unlocked ?: return@withLock
                    val idx = container.vaultManager.loadIndex(u)
                    val res = container.transferEngine.import(
                        u, sources, targetFolder, idx,
                        isCancelled = { cancelFlag.get() },
                        onProgress = { done, total -> _transfer.value = _transfer.value?.copy(done = done, total = total) ?: TransferState(done, total, str(R.string.vm_transfer_importing)) },
                        onCheckpoint = { i -> container.vaultManager.saveIndex(u, i) },
                        onFileStart = { name -> _transfer.value = _transfer.value?.copy(label = name, fileBytes = 0) ?: TransferState(0, 0, name) },
                        onBytes = { b -> _transfer.value = _transfer.value?.copy(fileBytes = b) },
                    )
                    container.vaultManager.saveIndex(u, idx)
                    _index.value = idx
                    _notice.value = buildString {
                        append(str(R.string.vm_notice_imported_count, res.imported))
                        if (res.skipped > 0) append(str(R.string.vm_notice_imported_skipped, res.skipped))
                    }
                } catch (e: TransferEngine.TransferCancelledException) {
                    // 检查点已终存;重读让 UI 显示保留下的部分导入
                    val u = unlocked
                    if (u != null) _index.value = container.vaultManager.loadIndex(u)
                    if (e.completed > 0) _notice.value = str(R.string.vm_notice_import_cancelled_partial, e.completed)
                } catch (e: Throwable) {
                    _error.value = str(R.string.vm_error_import, e.message)
                } finally {
                    transferBusy.set(false)
                    _transfer.value = null
                }
            }
        }
    }

    fun cancelTransfer() {
        cancelFlag.set(true)
    }

    // ---------------- 条目操作 ----------------

    fun renameEntry(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                val i = idx.entries.indexOfFirst { it.id == id }
                val trimmed = newName.trim()
                if (i < 0 || trimmed.isEmpty()) return@withLock
                val cur = idx.entries[i]
                if (cur.name == trimmed) return@withLock // 无变化不落盘
                // 同目录重名自动 "(2)",与导入语义一致
                val unique = container.transferEngine.uniqueName(trimmed, idx, cur.parentId, excludeId = id)
                idx.entries[i] = cur.copy(name = unique, updatedAt = System.currentTimeMillis())
                persist(u, idx)
            }
        }
    }

    fun moveEntries(ids: Set<String>, targetFolderId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                // 防环:目标不能是被移条目自身或其子孙
                val forbidden = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
                if (targetFolderId != null && targetFolderId in forbidden) {
                    _error.value = str(R.string.vm_error_move_into_self)
                    return@withLock
                }
                var changed = false
                ids.forEach { id ->
                    val i = idx.entries.indexOfFirst { it.id == id }
                    if (i >= 0 && idx.entries[i].parentId != targetFolderId) {
                        val e = idx.entries[i]
                        // 目标目录已有同名 → 自动消解(逐个处理,先移者先占位)
                        val unique = container.transferEngine.uniqueName(e.name, idx, targetFolderId, excludeId = id)
                        idx.entries[i] = e.copy(parentId = targetFolderId, name = unique, updatedAt = System.currentTimeMillis())
                        changed = true
                    }
                }
                if (changed) persist(u, idx)
                clearSelection()
            }
        }
    }

    /**
     * 删除 = 移入回收站:条目打 trashedAt 标记,blob 原样保留。
     * 8 秒撤销条只是快捷入口;窗口过后仍可在回收站恢复(保留期 30 天)。
     * 崩溃安全:任何时刻索引与 blob 都一致,不存在"索引已删但密文滞留/丢失"窗口。
     */
    fun deleteEntries(ids: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                // 级联:文件夹连子孙一起标记
                val doomed = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
                val now = System.currentTimeMillis()
                val victims = mutableListOf<VaultEntry>()
                idx.entries.replaceAll { e ->
                    if (e.id in doomed && !e.isTrashed) {
                        val t = e.copy(trashedAt = now)
                        victims.add(t)
                        t
                    } else e
                }
                if (victims.isEmpty()) return@withLock
                if (!persist(u, idx)) return@withLock // 保存失败:什么都不动
                val pd = PendingDeletion(victims, victims.mapNotNull { it.blobId })
                _undoDelete.value = pd
                scheduleUndoExpiry(pd)
                _notice.value = str(R.string.vm_notice_deleted_count, victims.size)
                clearSelection()
            }
        }
    }

    /** 撤销最近一次删除:索引恢复(重名自动消解;blob 已被落实删除的条目跳过)。 */
    fun undoDelete() {
        val pd = _undoDelete.value ?: return
        undoDeleteBatch(pd)
    }

    /** 撤销一批删除 = 清除该批的回收站标记(快捷撤销条与回收站恢复共用)。 */
    private fun undoDeleteBatch(pd: PendingDeletion) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                if (_undoDelete.value === pd) {
                    undoJob?.cancel()
                    _undoDelete.value = null
                }
                val u = unlocked ?: return@withLock
                restoreTrashed(u, pd.entries.map { it.id }.toSet())
            }
        }
    }

    /**
     * 从回收站恢复顶层条目(连子孙)。父目录已不在(被彻底删除)→ 落回根目录;
     * 目标位置重名自动消解。须持 indexMutex。
     */
    private fun restoreTrashed(u: UnlockedVault, rootIds: Set<String>) {
        val idx = container.vaultManager.loadIndex(u)
        val restoring = rootIds.flatMap { idx.descendantIds(it) }.toSet() + rootIds
        var restored = 0
        // 先恢复文件夹再恢复文件:父链先就位,重名消解才看得到正确的兄弟集
        val ordered = idx.entries.filter { it.id in restoring && it.isTrashed }
            .sortedByDescending { it.isFolder }
        ordered.forEach { e ->
            val i = idx.entries.indexOfFirst { it.id == e.id }
            if (i < 0) return@forEach
            val parentAlive = e.parentId == null ||
                (idx.find(e.parentId)?.let { !it.isTrashed || it.id in restoring } == true)
            val targetParent = if (parentAlive) e.parentId else null
            val name = container.transferEngine.uniqueName(e.name, idx, targetParent, excludeId = e.id)
            idx.entries[i] = e.copy(trashedAt = null, parentId = targetParent, name = name)
            restored++
        }
        if (restored == 0) return
        persist(u, idx)
        _notice.value = str(R.string.vm_notice_restored_count, restored)
    }

    /** 回收站:恢复选中顶层条目(连子孙)。 */
    fun restoreFromTrash(rootIds: Set<String>) {
        if (rootIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                restoreTrashed(u, rootIds)
            }
        }
    }

    /** 回收站:彻底删除选中顶层条目(连子孙)。先索引落盘成功,再删 blob。 */
    fun purgeFromTrash(rootIds: Set<String>) {
        if (rootIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                val doomed = rootIds.flatMap { idx.descendantIds(it) }.toSet() + rootIds
                val blobs = idx.entries.filter { it.id in doomed }.mapNotNull { it.blobId }
                val count = idx.entries.count { it.id in doomed }
                idx.entries.removeAll { it.id in doomed }
                if (!persist(u, idx)) return@withLock
                blobs.forEach { runCatching { container.vaultManager.deleteBlob(u.vaultId, it) } }
                _notice.value = str(R.string.vm_notice_purged_count, count)
            }
        }
    }

    /** 回收站:一键清空(全部彻底删除)。 */
    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                purgeFromTrashLocked(u, idx, idx.entries.filter { it.isTrashed }.map { it.id }.toSet())
            }
        }
    }

    private fun purgeFromTrashLocked(u: UnlockedVault, idx: VaultIndex, ids: Set<String>) {
        if (ids.isEmpty()) return
        val doomed = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
        val blobs = idx.entries.filter { it.id in doomed }.mapNotNull { it.blobId }
        val count = idx.entries.count { it.id in doomed }
        idx.entries.removeAll { it.id in doomed }
        if (!persist(u, idx)) return
        blobs.forEach { runCatching { container.vaultManager.deleteBlob(u.vaultId, it) } }
        _notice.value = str(R.string.vm_notice_purged_count, count)
    }

    /** 撤销窗到期:只收起快捷撤销条——条目仍在回收站,保留期内随时可恢复。 */
    private fun scheduleUndoExpiry(pd: PendingDeletion) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            kotlinx.coroutines.delay(UNDO_WINDOW_MS)
            if (_undoDelete.value === pd) _undoDelete.value = null
        }
    }

    override fun onCleared() {
        // 回收站语义:离开库/锁定不需要落实任何删除——条目与 blob 都完好保留在回收站
        _undoDelete.value = null
        undoJob?.cancel()
        // 未落盘的缩略图标记兜底同步落盘(scope 已取消,协程路线不可用);
        // 会话可能已锁定,失败静默——标记可由下次浏览幂等重建
        thumbFlushJob.getAndSet(null)?.cancel()
        runCatching {
            val u = unlocked ?: return@runCatching
            val idx = _index.value ?: return@runCatching
            container.vaultManager.saveIndex(u, idx)
        }
    }

    fun newFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                idx.addEntry(
                    name = container.transferEngine.uniqueName(trimmed, idx, currentFolderId),
                    kind = MediaKind.FOLDER,
                    parentId = currentFolderId,
                )
                persist(u, idx)
            }
        }
    }

    /**
     * 库内复制副本:文件 → 解密流重加密到新 blob;文件夹 → BFS 逐层复制子孙,
     * parentId 走 oldId→newId 映射。明文只在流里过,不落地;
     * 中途失败清理已建 blob 并回滚已加的索引条目。
     */
    fun duplicateEntry(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                val idx = container.vaultManager.loadIndex(u)
                val src = idx.find(id) ?: return@withLock
                val createdBlobs = mutableListOf<String>()
                val addedIds = mutableListOf<String>()
                try {
                    val rootCopy = idx.addEntry(
                        name = container.transferEngine.uniqueName(src.name, idx, src.parentId),
                        kind = src.kind,
                        blobId = src.blobId?.let { copyBlob(u, it, createdBlobs) },
                        sizeBytes = src.sizeBytes,
                        parentId = src.parentId,
                        mimeType = src.mimeType,
                    )
                    addedIds += rootCopy.id
                    if (src.isFolder) {
                        val idMap = mutableMapOf(src.id to rootCopy.id)
                        val visited = mutableSetOf(src.id) // 损坏索引的父子环(A→B→A)不能死循环
                        var frontier = listOf(src.id)
                        while (frontier.isNotEmpty()) {
                            // 新条目的 parentId 是新 id,永远不会撞上 frontier 的旧 id——边加边查安全
                            val children = idx.entries
                                .filter { it.parentId in frontier && it.id !in visited && !it.isTrashed }
                            children.forEach { visited += it.id }
                            frontier = children.map { it.id }
                            children.forEach { e ->
                                val added = idx.addEntry(
                                    name = e.name, // 副本文件夹内部无冲突,原名保留
                                    kind = e.kind,
                                    blobId = e.blobId?.let { copyBlob(u, it, createdBlobs) },
                                    sizeBytes = e.sizeBytes,
                                    parentId = idMap[e.parentId],
                                    mimeType = e.mimeType,
                                )
                                addedIds += added.id
                                idMap[e.id] = added.id
                            }
                        }
                    }
                    persist(u, idx)
                    _notice.value = str(R.string.vm_notice_duplicated)
                } catch (e: Throwable) {
                    createdBlobs.forEach { container.vaultManager.deleteBlob(u.vaultId, it) }
                    idx.entries.removeAll { it.id in addedIds }
                    persist(u, idx)
                    _error.value = str(R.string.vm_error_duplicate, e.message)
                }
            }
        }
    }

    /** 复制单个 blob:解密 → 新 blobId 重新加密(AD 绑新 id)。先记账再写,失败可清半成品。 */
    private fun copyBlob(u: UnlockedVault, srcBlob: String, createdBlobs: MutableList<String>): String {
        val newBlob = container.vaultManager.newBlobId()
        createdBlobs += newBlob
        val sink = container.vaultManager.prepareBlobSink(u.vaultId, newBlob)
        container.vaultManager.openBlobStream(u, srcBlob).use { dec ->
            sink.outputStream().use { raw ->
                u.crypto.encryptingStream(
                    raw, io.vaultx.app.core.crypto.VaultCrypto.blobAd(u.vaultId, newBlob),
                ).use { enc -> dec.copyTo(enc) }
            }
        }
        return newBlob
    }

    // ---------------- 导出 ----------------

    fun exportEntries(ids: Set<String>, sinkFactory: TransferEngine.ExportSinkFactory) {
        if (!transferBusy.compareAndSet(false, true)) {
            _error.value = str(R.string.vm_transfer_busy)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 0, str(R.string.vm_transfer_exporting))
            try {
                val u = unlocked ?: return@launch
                // 在索引锁内收集目标快照(VaultEntry 不可变)并复制索引本身——
                // relPathOf 在导出全程会回溯父链,期间并发的改名/删除不能碰活列表
                val (targets, idx) = indexMutex.withLock {
                    val i = _index.value ?: container.vaultManager.loadIndex(u)
                    val doomed = ids.flatMap { i.descendantIds(it, includeTrashed = false) }.toSet() + ids
                    val t = i.entries.filter { it.id in doomed && !it.isTrashed }
                    t to VaultIndex(entries = ArrayList(i.entries), updatedAt = i.updatedAt)
                }
                val res = container.transferEngine.exportEntries(
                    u, targets, idx, sinkFactory,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { done, total -> _transfer.value = _transfer.value?.copy(done = done, total = total) ?: TransferState(done, total, str(R.string.vm_transfer_exporting)) },
                    onFileStart = { name -> _transfer.value = _transfer.value?.copy(label = name, fileBytes = 0) ?: TransferState(0, 0, name) },
                    onBytes = { b -> _transfer.value = _transfer.value?.copy(fileBytes = b) },
                )
                _notice.value = buildString {
                    append(str(R.string.vm_notice_exported_count, res.exported))
                    if (res.failed > 0) {
                        append(str(R.string.vm_notice_exported_failed, res.failed))
                        res.failedNames.take(3).let { if (it.isNotEmpty()) append(":${it.joinToString(str(R.string.vm_name_separator))}") }
                    }
                }
            } catch (_: TransferEngine.TransferCancelledException) {
                _notice.value = str(R.string.vm_notice_export_cancelled)
            } catch (e: Throwable) {
                _error.value = str(R.string.vm_error_export, e.message)
            } finally {
                transferBusy.set(false)
                _transfer.value = null
                clearSelection()
            }
        }
    }

    /** 更新条目为已生成缩略图。 */
    private val thumbInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun ensureThumb(entry: VaultEntry) {
        val blobId = entry.blobId ?: return
        // 去重:重组/快速滚动会重复触发;同 blob 并发 writeThumb 会撞 writeAtomic 的 .tmp
        if (entry.hasThumb || entry.isFolder || !thumbInFlight.add(blobId)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val u = unlocked ?: return@launch
                val bytes = container.thumbnailer.generate(u, blobId, entry.kind) ?: return@launch
                container.vaultManager.writeThumb(u, blobId, bytes)
                indexMutex.withLock {
                    val u2 = unlocked ?: return@withLock
                    val idx = container.vaultManager.loadIndex(u2)
                    val i = idx.entries.indexOfFirst { it.id == entry.id }
                    if (i >= 0 && !idx.entries[i].hasThumb) {
                        idx.entries[i] = idx.entries[i].copy(hasThumb = true)
                        // 只刷新内存态,延迟合并落盘:滚动画廊时逐张全量重加密索引
                        // 会造成 N 次写放大。未落盘窗口内进程死亡也无碍——thumb 文件
                        // 已存在,下次浏览会重新生成标记(幂等)
                        _index.value = idx
                        scheduleThumbFlush()
                    }
                }
            } catch (_: Throwable) {
                // 缩略图是尽力而为的增强:锁定中途/解码失败都不该冒泡成崩溃,
                // 残留的 thumb 文件由 deleteBlob/sweepOrphanBlobs 收尾
            } finally {
                thumbInFlight.remove(blobId)
            }
        }
    }

    /** 合并落盘缩略图标记:每张新缩略图重置 1.5s 防抖,滚动停止后一次性写盘。 */
    private fun scheduleThumbFlush() {
        while (true) {
            val prev = thumbFlushJob.get()
            val job = viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(THUMB_FLUSH_DEBOUNCE_MS)
                flushThumbsNow()
            }
            if (thumbFlushJob.compareAndSet(prev, job)) {
                prev?.cancel() // 真防抖:取消上一轮重新计时(此前 join 成串行链,滚动中仍周期写盘)
                return
            }
            job.cancel()
        }
    }

    private suspend fun flushThumbsNow() {
        indexMutex.withLock {
            val u = unlocked ?: return@withLock
            // 必须存内存态:loadIndex 返回管理器缓存快照,不含 ensureThumb 刚打的
            // 标记——重读再存等于写回旧索引,hasThumb 永远落不了盘、缩略图反复重建
            val idx = _index.value ?: return@withLock
            runCatching { container.vaultManager.saveIndex(u, idx) }
        }
    }

    /** 索引落盘并刷新内存态;失败时报错且不更新界面(返回 false)。 */
    private fun persist(u: UnlockedVault, idx: VaultIndex): Boolean {
        // 常规落盘顺带覆盖未持久化的缩略图标记,防抖任务作废
        thumbFlushJob.getAndSet(null)?.cancel()
        return runCatching { container.vaultManager.saveIndex(u, idx) }
            .onSuccess { _index.value = idx }
            .onFailure { _error.value = str(R.string.vm_error_index_save, it.message) }
            .isSuccess
    }

    companion object {
        /** 快捷撤销条展示时长;窗口过后条目仍在回收站(保留期见 VaultIndex)。 */
        private const val UNDO_WINDOW_MS = 8_000L

        /** 缩略图标记合并落盘的防抖间隔。 */
        private const val THUMB_FLUSH_DEBOUNCE_MS = 1_500L

        /**
         * 中文文件名按拼音序排列的 Collator(Comparator 接口线程安全);
         * 比 codepoint 序更符合中文用户直觉("文档"排在"图片"后)。
         */
        private val NAME_COLLATOR: java.text.Collator =
            java.text.Collator.getInstance(java.util.Locale.CHINA).apply {
                strength = java.text.Collator.PRIMARY // 忽略大小写/音调差异
            }
    }
}
