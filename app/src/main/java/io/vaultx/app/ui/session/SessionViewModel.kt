package io.vaultx.app.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.core.session.SessionCancelledException
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

    /** 协作式取消:导入/导出分块边界检查;忙碌期间界面显示取消按钮。 */
    private val cancelFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    fun cancelBusy() { cancelFlag.set(true) }

    /** 传输入口 CAS 占位:快速双击不能并发发起两个传输(与库 VM 同一防护)。 */
    private val busyGuard = java.util.concurrent.atomic.AtomicBoolean(false)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _files.value = container.sessionManager.listFiles()
        }
    }

    fun clearError() { _error.value = null }

    /**
     * 导入文件或文件夹(树源递归拍平进会话目录——会话是平铺命名空间,
     * 重名由 SessionManager.importFile 的 unique 消解)。
     */
    fun import(sources: List<TransferEngine.ImportSource>) {
        if (!busyGuard.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _busy.value = container.context.getString(R.string.svm_importing)
            try {
                sources.forEach { importRecursive(it) }
            } catch (e: SessionCancelledException) {
                _error.value = container.context.getString(R.string.svm_cancelled)
            } catch (e: Throwable) {
                _error.value = container.context.getString(R.string.svm_import_failed, e.message)
            } finally {
                busyGuard.set(false)
            }
            _busy.value = null
            _files.value = container.sessionManager.listFiles()
        }
    }

    private fun importRecursive(src: TransferEngine.ImportSource) {
        if (cancelFlag.get()) throw SessionCancelledException()
        if (src.isDirectory) {
            src.children().forEach { importRecursive(it) }
        } else {
            src.open().use { ins ->
                container.sessionManager.importFile(src.name, ins) { cancelFlag.get() }
            }
        }
    }

    /**
     * 导入 .vlt 便携密文(需一次性密码;密码数组由本方法接管,用完清零)。
     * [onResult]:null = 成功/用户取消;非空 = 失败原因(对话框内联展示)。
     */
    fun importVlt(source: TransferEngine.ImportSource, password: CharArray, onResult: (String?) -> Unit) {
        if (!busyGuard.compareAndSet(false, true)) {
            java.util.Arrays.fill(password, 0.toChar())
            onResult(container.context.getString(R.string.svm_busy))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _busy.value = container.context.getString(R.string.svm_decrypting)
            var failure: String? = null
            try {
                source.open().use { ins ->
                    container.sessionManager.importVlt(source.name, ins, password) { cancelFlag.get() }
                }
            } catch (e: SessionCancelledException) {
                _error.value = container.context.getString(R.string.svm_cancelled)
            } catch (e: Throwable) {
                failure = if (e is io.vaultx.app.core.crypto.WrongPasswordException) {
                    container.context.getString(R.string.svm_wrong_password)
                } else {
                    container.context.getString(R.string.svm_decrypt_failed, e.message)
                }
                _error.value = failure
            } finally {
                java.util.Arrays.fill(password, 0.toChar())
                busyGuard.set(false)
            }
            _busy.value = null
            _files.value = container.sessionManager.listFiles()
            withContext(Dispatchers.Main) { onResult(failure) }
        }
    }

    /** 加密导出为 .vlt(一次性密码;密码数组由本方法接管,用完清零)。 */
    fun exportVlt(storedName: String, password: CharArray, output: OutputStream, onDone: () -> Unit) {
        if (!busyGuard.compareAndSet(false, true)) {
            java.util.Arrays.fill(password, 0.toChar())
            _error.value = container.context.getString(R.string.svm_busy)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _busy.value = container.context.getString(R.string.svm_export_encrypted_busy)
            try {
                output.use {
                    container.sessionManager.exportAsVlt(storedName, password, it) { cancelFlag.get() }
                }
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: SessionCancelledException) {
                _error.value = container.context.getString(R.string.svm_cancelled)
            } catch (e: Throwable) {
                _error.value = container.context.getString(R.string.svm_export_failed, e.message)
            } finally {
                java.util.Arrays.fill(password, 0.toChar())
                busyGuard.set(false)
            }
            _busy.value = null
        }
    }

    /** 明文导出(会话文件本来就是明文,直接拷出)。 */
    fun exportPlain(storedName: String, output: OutputStream) {
        if (!busyGuard.compareAndSet(false, true)) {
            _error.value = container.context.getString(R.string.svm_busy)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            cancelFlag.set(false)
            _busy.value = container.context.getString(R.string.svm_exporting)
            try {
                output.use { out ->
                    container.sessionManager.file(storedName).inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelFlag.get()) throw SessionCancelledException()
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
            } catch (e: SessionCancelledException) {
                _error.value = container.context.getString(R.string.svm_cancelled)
            } catch (e: Throwable) {
                _error.value = container.context.getString(R.string.svm_export_failed, e.message)
            } finally {
                busyGuard.set(false)
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

    /** 退出即焚。覆写是重 IO,放应用级后台域——主线程逐文件 rws 覆写大文件会 ANR。 */
    fun destroySession() {
        _files.value = emptyList()
        container.appScope.launch(Dispatchers.IO) { container.sessionManager.destroy() }
    }

    override fun onCleared() {
        // 任何退出路径(确认退出/系统返回弹栈/锁定清栈)都即焚——
        // 不能只靠退出确认对话框,系统 back 不会经过它。
        // appScope 不随 VM 取消:进程存活期间焚毁必达;进程死亡由冷启动清扫兜底
        container.appScope.launch(Dispatchers.IO) { container.sessionManager.destroy() }
    }
}