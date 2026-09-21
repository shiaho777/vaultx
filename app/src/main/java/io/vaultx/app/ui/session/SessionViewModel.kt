package io.vaultx.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.vaultx.app.AppContainer
import io.vaultx.app.core.session.SessionManager.SessionFile
import io.vaultx.app.core.transfer.TransferEngine
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 无锁模式状态中枢——全部操作都在 IO 线程,文件即焚语义由 SessionManager 保证。 */
class SessionViewModel(private val container: AppContainer) : ViewModel() {

    private val _files = MutableStateFlow<List<SessionFile>>(emptyList())
    val files: StateFlow<List<SessionFile>> = _files

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _files.value = container.sessionManager.listFiles()
        }
    }

    fun clearError() { _error.value = null }

    fun import(sources: List<TransferEngine.ImportSource>) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = "导入中…"
            try {
                for (src in sources) {
                    src.open().use { ins -> container.sessionManager.importFile(src.name, ins) }
                }
            } catch (e: Throwable) {
                _error.value = "导入失败:${e.message}"
            }
            _busy.value = null
            _files.value = container.sessionManager.listFiles()
        }
    }

    /** 导入 .vlt 便携密文(需一次性密码)。 */
    fun importVlt(source: TransferEngine.ImportSource, password: CharArray, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = "解密中…"
            try {
                source.open().use { ins ->
                    container.sessionManager.importVlt(source.name, ins, password)
                }
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: Throwable) {
                _error.value = if (e is io.vaultx.app.core.crypto.WrongPasswordException) "密码错误" else "解密失败:${e.message}"
                withContext(Dispatchers.Main) { onResult(false) }
            }
            _busy.value = null
            _files.value = container.sessionManager.listFiles()
        }
    }

    /** 加密导出为 .vlt(一次性密码)。 */
    fun exportVlt(storedName: String, password: CharArray, output: OutputStream, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = "加密导出中…"
            try {
                output.use { container.sessionManager.exportAsVlt(storedName, password, it) }
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Throwable) {
                _error.value = "导出失败:${e.message}"
            }
            _busy.value = null
        }
    }

    /** 明文导出(会话文件本来就是明文,直接拷出)。 */
    fun exportPlain(storedName: String, output: OutputStream) {
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = "导出中…"
            try {
                output.use { out -> container.sessionManager.file(storedName).inputStream().use { it.copyTo(out) } }
            } catch (e: Throwable) {
                _error.value = "导出失败:${e.message}"
            }
            _busy.value = null
        }
    }

    fun rename(storedName: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.sessionManager.rename(storedName, newName)
            _files.value = container.sessionManager.listFiles()
        }
    }

    fun delete(storedName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            container.sessionManager.delete(storedName)
            _files.value = container.sessionManager.listFiles()
        }
    }

    /** 退出即焚。 */
    fun destroySession() {
        container.sessionManager.destroy()
        _files.value = emptyList()
    }

    override fun onCleared() {
        // 任何退出路径(确认退出/系统返回弹栈/锁定清栈)都即焚——
        // 不能只靠退出确认对话框,系统 back 不会经过它
        container.sessionManager.destroy()
    }
}
