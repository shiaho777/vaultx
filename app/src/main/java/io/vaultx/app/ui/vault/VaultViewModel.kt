package io.vaultx.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.vaultx.app.AppContainer
import io.vaultx.app.core.transfer.TransferEngine
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultEntry
import io.vaultx.app.core.vault.VaultIndex
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 排序档位。 */
enum class SortBy(val label: String) { NAME("名称"), TIME("时间"), SIZE("大小"), KIND("类型") }

/** 传输进度。 */
data class TransferState(val done: Int, val total: Int, val label: String)

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

    private val _index = MutableStateFlow<VaultIndex?>(null)
    val index: StateFlow<VaultIndex?> = _index

    /** 当前所在文件夹链(空 = 根目录)。 */
    private val _folderStack = MutableStateFlow<List<VaultEntry>>(emptyList())
    val folderStack: StateFlow<List<VaultEntry>> = _folderStack

    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _sortBy = MutableStateFlow(
        runCatching { SortBy.valueOf(container.settings.sortBy.value) }.getOrDefault(SortBy.NAME),
    )
    val sortBy: StateFlow<SortBy> = _sortBy

    /** 网格/列表视图(持久化)。 */
    private val _viewMode = MutableStateFlow(container.settings.viewMode.value)
    val viewMode: StateFlow<String> = _viewMode

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** 非错误性结果提示(如"已导出 8 项,1 个失败")。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    /** 待最终落删的删除批次(撤销窗口内 blob 完好)。 */
    data class PendingDeletion(val entries: List<VaultEntry>, val blobIds: List<String>)

    private val _undoDelete = MutableStateFlow<PendingDeletion?>(null)
    val undoDelete: StateFlow<PendingDeletion?> = _undoDelete

    private val indexMutex = Mutex()
    private val cancelFlag = AtomicBoolean(false)
    private var undoJob: kotlinx.coroutines.Job? = null

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
                flushPendingDelete(u)
                val idx = runCatching { container.vaultManager.loadIndex(u) }
                    .getOrElse {
                        _error.value = "索引读取失败:${it.message}"
                        return@withLock
                    }
                _index.value = idx
                // 栈里已被删的文件夹清掉(如外部操作)
                val ids = idx.entries.map { it.id }.toSet()
                _folderStack.value = _folderStack.value.filter { it.id in ids }
            }
        }
    }

    /** 当前应展示的条目:搜索态全局命中;否则当前目录子项;排序应用。 */
    fun visibleEntries(): List<VaultEntry> {
        val idx = _index.value ?: return emptyList()
        val q = _query.value
        val base = if (q.isNotBlank()) idx.search(q) else idx.childrenOf(currentFolderId)
        return sortEntries(base)
    }

    private fun sortEntries(list: List<VaultEntry>): List<VaultEntry> =
        list.sortedWith(compareByDescending<VaultEntry> { it.isFolder }.thenComparing { a, b ->
            when (_sortBy.value) {
                SortBy.NAME -> a.name.compareTo(b.name, ignoreCase = true)
                SortBy.TIME -> b.updatedAt.compareTo(a.updatedAt)
                SortBy.SIZE -> b.sizeBytes.compareTo(a.sizeBytes)
                SortBy.KIND -> a.kind.compareTo(b.kind)
            }
        })

    fun setQuery(q: String) { _query.value = q }
    fun setSortBy(s: SortBy) {
        _sortBy.value = s
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
            _query.value = "" // 从搜索结果点进文件夹时退出搜索态,展示其内容
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
        viewModelScope.launch(Dispatchers.IO) {
            _transfer.value = TransferState(0, 1, "加密导出…")
            try {
                val u = unlocked ?: return@launch
                val blobId = entry.blobId ?: throw IllegalStateException("folder has no blob")
                output.use { out ->
                    container.vaultManager.openBlobStream(u, blobId).use { dec ->
                        io.vaultx.app.core.crypto.PortableCipher.encryptTo(password, dec, out)
                    }
                }
                _notice.value = "已导出 ${entry.name}.vlt"
            } catch (e: Throwable) {
                _error.value = "导出失败:${e.message}"
            } finally {
                password.fill('\u0000')
                _transfer.value = null
            }
        }
    }

    // ---------------- 导入 ----------------

    fun import(sources: List<TransferEngine.ImportSource>) {
        if (_transfer.value != null) {
            _error.value = "已有传输进行中,请先等待或取消"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 0, "导入中…")
            // 导入全程持索引锁:中途的改名/删除排队,取锁后读到的是导入后的最新索引
            indexMutex.withLock {
                try {
                    val u = unlocked ?: return@withLock
                flushPendingDelete(u)
                    val idx = container.vaultManager.loadIndex(u)
                    val res = container.transferEngine.import(
                        u, sources, currentFolderId, idx,
                        isCancelled = { cancelFlag.get() },
                        onProgress = { done, total -> _transfer.value = _transfer.value?.copy(done = done, total = total) ?: TransferState(done, total, "导入中…") },
                        onCheckpoint = { i -> container.vaultManager.saveIndex(u, i) },
                        onFileStart = { name -> _transfer.value = _transfer.value?.copy(label = name) ?: TransferState(0, 0, name) },
                    )
                    container.vaultManager.saveIndex(u, idx)
                    _index.value = idx
                    _notice.value = buildString {
                        append("已导入 ${res.imported} 项")
                        if (res.skipped > 0) append("(跳过 ${res.skipped} 个重复)")
                    }
                } catch (e: TransferEngine.TransferCancelledException) {
                    // 检查点已终存;重读让 UI 显示保留下的部分导入
                    val u = unlocked
                    if (u != null) _index.value = container.vaultManager.loadIndex(u)
                    if (e.completed > 0) _notice.value = "已取消;保留已导入的 ${e.completed} 项"
                } catch (e: Throwable) {
                    _error.value = "导入失败:${e.message}"
                } finally {
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
                flushPendingDelete(u)
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
                flushPendingDelete(u)
                val idx = container.vaultManager.loadIndex(u)
                // 防环:目标不能是被移条目自身或其子孙
                val forbidden = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
                if (targetFolderId != null && targetFolderId in forbidden) {
                    _error.value = "不能移动到自身内部"
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

    fun deleteEntries(ids: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                flushPendingDelete(u) // 上一批没过期又来一批:先把旧的落实
                val idx = container.vaultManager.loadIndex(u)
                // 级联:文件夹连子孙一起删;顺序:先索引落盘成功,blob 进撤销窗
                val doomed = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
                val victims = idx.entries.filter { it.id in doomed }
                idx.entries.removeAll { it.id in doomed }
                if (!persist(u, idx)) return@withLock // 保存失败:什么都不动
                val pd = PendingDeletion(victims, victims.mapNotNull { it.blobId })
                _undoDelete.value = pd
                scheduleDeleteFinalize(u, pd)
                _notice.value = "已删除 ${victims.size} 项"
                clearSelection()
            }
        }
    }

    /** 撤销最近一次删除:索引恢复(重名自动消解;blob 已被落实删除的条目跳过)。 */
    fun undoDelete() {
        val pd = _undoDelete.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                if (_undoDelete.value !== pd) return@withLock
                val u = unlocked ?: return@withLock
                flushPendingDelete(u)
                undoJob?.cancel()
                val idx = container.vaultManager.loadIndex(u)
                // 父链可能同批恢复:先按"批内也算存在"放行,再逐个加回
                val restoring = pd.entries.map { it.id }.toSet()
                var restored = 0
                pd.entries.forEach { e ->
                    if (idx.find(e.id) != null) return@forEach
                    val parentOk = e.parentId == null || idx.find(e.parentId) != null || e.parentId in restoring
                    val blobAlive = e.blobId == null || container.vaultManager.blobExists(u.vaultId, e.blobId)
                    if (parentOk && blobAlive) {
                        val name = container.transferEngine.uniqueName(e.name, idx, e.parentId, excludeId = e.id)
                        idx.entries.add(e.copy(name = name))
                        restored++
                    }
                }
                persist(u, idx)
                _undoDelete.value = null
                val skipped = pd.entries.size - restored
                _notice.value = if (skipped == 0) "已恢复 $restored 项" else "已恢复 $restored 项,$skipped 项已无法恢复"
            }
        }
    }

    /** 撤销窗到期:落实删除 blob。任何新索引变更也会先调 [flushPendingDelete] 落实上一批。 */
    private fun scheduleDeleteFinalize(u: UnlockedVault, pd: PendingDeletion) {
        undoJob?.cancel()
        undoJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(UNDO_WINDOW_MS)
            indexMutex.withLock {
                if (_undoDelete.value === pd) {
                    pd.blobIds.forEach { container.vaultManager.deleteBlob(u.vaultId, it) }
                    _undoDelete.value = null
                }
            }
        }
    }

    /** 立刻落实挂起的删除(新变更前/VM 销毁时调用)。须持 indexMutex。 */
    private fun flushPendingDelete(u: UnlockedVault) {
        val pd = _undoDelete.value ?: return
        undoJob?.cancel()
        _undoDelete.value = null
        pd.blobIds.forEach { runCatching { container.vaultManager.deleteBlob(u.vaultId, it) } }
    }

    override fun onCleared() {
        // VM 销毁(离开库/锁定):挂起的删除立即落实,不留"索引已删但密文滞留"的窗口
        _undoDelete.value?.blobIds?.forEach {
            runCatching { container.vaultManager.deleteBlob(vaultId, it) }
        }
        _undoDelete.value = null
    }

    fun newFolder(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            indexMutex.withLock {
                val u = unlocked ?: return@withLock
                flushPendingDelete(u)
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

    // ---------------- 导出 ----------------

    fun exportEntries(ids: Set<String>, sinkFactory: TransferEngine.ExportSinkFactory) {
        if (_transfer.value != null) {
            _error.value = "已有传输进行中,请先等待或取消"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 0, "导出中…")
            try {
                val u = unlocked ?: return@launch
                // 在索引锁内收集目标快照(VaultEntry 不可变)并复制索引本身——
                // relPathOf 在导出全程会回溯父链,期间并发的改名/删除不能碰活列表
                val (targets, idx) = indexMutex.withLock {
                    val i = _index.value ?: container.vaultManager.loadIndex(u)
                    val doomed = ids.flatMap { i.descendantIds(it) }.toSet() + ids
                    val t = i.entries.filter { it.id in doomed }
                    t to VaultIndex(entries = ArrayList(i.entries), updatedAt = i.updatedAt)
                }
                val res = container.transferEngine.exportEntries(
                    u, targets, idx, sinkFactory,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { done, total -> _transfer.value = _transfer.value?.copy(done = done, total = total) ?: TransferState(done, total, "导出中…") },
                    onFileStart = { name -> _transfer.value = _transfer.value?.copy(label = name) ?: TransferState(0, 0, name) },
                )
                _notice.value = buildString {
                    append("已导出 ${res.exported} 项")
                    if (res.failed > 0) {
                        append(",${res.failed} 个失败")
                        res.failedNames.take(3).let { if (it.isNotEmpty()) append(":${it.joinToString("、")}") }
                    }
                }
            } catch (_: TransferEngine.TransferCancelledException) {
                _notice.value = "导出已取消"
            } catch (e: Throwable) {
                _error.value = "导出失败:${e.message}"
            } finally {
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
                        persist(u2, idx)
                    }
                }
            } finally {
                thumbInFlight.remove(blobId)
            }
        }
    }

    /** 索引落盘并刷新内存态;失败时报错且不更新界面(返回 false)。 */
    private fun persist(u: UnlockedVault, idx: VaultIndex): Boolean {
        return runCatching { container.vaultManager.saveIndex(u, idx) }
            .onSuccess { _index.value = idx }
            .onFailure { _error.value = "保存索引失败:${it.message}" }
            .isSuccess
    }

    companion object {
        /** 删除撤销窗口:blob 延迟删除时长,窗口内可整批恢复。 */
        private const val UNDO_WINDOW_MS = 8_000L
    }
}
