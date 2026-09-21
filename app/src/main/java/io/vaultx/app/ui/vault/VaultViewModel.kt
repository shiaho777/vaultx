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
import kotlinx.coroutines.withContext

/** 排序档位。 */
enum class SortBy(val label: String) { NAME("名称"), TIME("时间"), SIZE("大小"), KIND("类型") }

/** 传输进度。 */
data class TransferState(val done: Int, val total: Int, val label: String)

/**
 * 库内文件浏览/操作的状态中枢。所有磁盘+解密动作都在 IO 线程;
 * 索引改动先改内存再 saveIndex 落盘。
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

    private val _sortBy = MutableStateFlow(SortBy.NAME)
    val sortBy: StateFlow<SortBy> = _sortBy

    private val _transfer = MutableStateFlow<TransferState?>(null)
    val transfer: StateFlow<TransferState?> = _transfer

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val cancelFlag = AtomicBoolean(false)

    val currentFolderId: String? get() = _folderStack.value.lastOrNull()?.id
    val viaDecoy: Boolean get() = unlocked?.viaDecoy == true

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val idx = unlocked?.let { container.vaultManager.loadIndex(it) }
            _index.value = idx
            // 栈里已被删的文件夹清掉(如外部操作)
            val ids = idx?.entries?.map { it.id }?.toSet() ?: emptySet()
            _folderStack.value = _folderStack.value.filter { it.id in ids }
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
    fun setSortBy(s: SortBy) { _sortBy.value = s }
    fun clearError() { _error.value = null }

    fun openFolder(entry: VaultEntry) {
        if (entry.isFolder) _folderStack.value = _folderStack.value + entry
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

    // ---------------- 选择 ----------------

    fun toggleSelect(id: String) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun selectAll() {
        _selection.value = visibleEntries().map { it.id }.toSet()
    }

    // ---------------- 导入 ----------------

    fun import(sources: List<TransferEngine.ImportSource>) {
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 0, "导入中…")
            try {
                val u = unlocked ?: return@launch
                val idx = _index.value ?: container.vaultManager.loadIndex(u)
                container.transferEngine.import(
                    u, sources, currentFolderId, idx,
                    isCancelled = { cancelFlag.get() },
                    onProgress = { done, total -> _transfer.value = TransferState(done, total, "导入中…") },
                )
                container.vaultManager.saveIndex(u, idx)
                refresh()
            } catch (e: TransferEngine.TransferCancelledException) {
                refresh()
            } catch (e: Throwable) {
                _error.value = "导入失败:${e.message}"
            } finally {
                _transfer.value = null
            }
        }
    }

    fun cancelTransfer() {
        cancelFlag.set(true)
    }

    // ---------------- 条目操作 ----------------

    fun renameEntry(id: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val u = unlocked ?: return@launch
            val idx = _index.value ?: return@launch
            val i = idx.entries.indexOfFirst { it.id == id }
            if (i < 0 || newName.isBlank()) return@launch
            idx.entries[i] = idx.entries[i].copy(name = newName.trim(), updatedAt = System.currentTimeMillis())
            persist(idx)
        }
    }

    fun moveEntries(ids: Set<String>, targetFolderId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val u = unlocked ?: return@launch
            val idx = _index.value ?: return@launch
            // 防环:目标不能是被移条目自身或其子孙
            val forbidden = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
            if (targetFolderId != null && targetFolderId in forbidden) {
                _error.value = "不能移动到自身内部"
                return@launch
            }
            var changed = false
            ids.forEach { id ->
                val i = idx.entries.indexOfFirst { it.id == id }
                if (i >= 0 && idx.entries[i].parentId != targetFolderId) {
                    idx.entries[i] = idx.entries[i].copy(parentId = targetFolderId, updatedAt = System.currentTimeMillis())
                    changed = true
                }
            }
            if (changed) persist(idx)
            clearSelection()
        }
    }

    fun deleteEntries(ids: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val u = unlocked ?: return@launch
            val idx = _index.value ?: return@launch
            // 级联:文件夹连子孙一起删;收集 blobId 先删索引再删密文
            val doomed = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
            val blobIds = idx.entries.filter { it.id in doomed }.mapNotNull { it.blobId }
            idx.entries.removeAll { it.id in doomed }
            persist(idx)
            blobIds.forEach { container.vaultManager.deleteBlob(u.vaultId, it) }
            clearSelection()
        }
    }

    fun newFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val u = unlocked ?: return@launch
            val idx = _index.value ?: return@launch
            idx.addEntry(name = name.trim(), kind = MediaKind.FOLDER, parentId = currentFolderId)
            persist(idx)
        }
    }

    // ---------------- 导出 ----------------

    fun exportEntries(ids: Set<String>, sinkFactory: TransferEngine.ExportSinkFactory, onDone: (Int, Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _transfer.value = TransferState(0, 0, "导出中…")
            try {
                val u = unlocked ?: return@launch
                val idx = _index.value ?: return@launch
                // 展开文件夹:导出其全部子孙文件
                val doomed = ids.flatMap { idx.descendantIds(it) }.toSet() + ids
                val targets = idx.entries.filter { it.id in doomed && !it.isFolder }
                val res = container.transferEngine.exportEntries(
                    u, targets, idx, sinkFactory,
                    isCancelled = { cancelFlag.get() },
                )
                withContext(Dispatchers.Main) { onDone(res.exported, res.failed) }
            } catch (_: TransferEngine.TransferCancelledException) {
            } catch (e: Throwable) {
                _error.value = "导出失败:${e.message}"
            } finally {
                _transfer.value = null
                clearSelection()
            }
        }
    }

    /** 更新条目为已生成缩略图。 */
    fun ensureThumb(entry: VaultEntry) {
        val blobId = entry.blobId ?: return
        if (entry.hasThumb || entry.isFolder) return
        viewModelScope.launch(Dispatchers.IO) {
            val u = unlocked ?: return@launch
            val bytes = container.thumbnailer.generate(u, blobId, entry.kind) ?: return@launch
            container.vaultManager.writeThumb(u, blobId, bytes)
            val idx = _index.value ?: return@launch
            val i = idx.entries.indexOfFirst { it.id == entry.id }
            if (i >= 0 && !idx.entries[i].hasThumb) {
                idx.entries[i] = idx.entries[i].copy(hasThumb = true)
                persist(idx)
            }
        }
    }

    private fun persist(idx: VaultIndex) {
        val u = unlocked ?: return
        runCatching { container.vaultManager.saveIndex(u, idx) }
            .onFailure { _error.value = "保存索引失败:${it.message}" }
        _index.value = idx
    }
}
