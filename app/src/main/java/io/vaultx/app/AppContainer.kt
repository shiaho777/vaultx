package io.vaultx.app

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.SingletonImageLoader
import io.vaultx.app.core.crypto.TinkStreaming
import io.vaultx.app.core.media.Thumbnailer
import io.vaultx.app.core.security.BiometricKeystore
import io.vaultx.app.core.session.SessionManager
import io.vaultx.app.core.state.AppSettings
import io.vaultx.app.core.state.VaultSessionHolder
import io.vaultx.app.core.transfer.SafTransfer
import io.vaultx.app.core.transfer.TransferEngine
import io.vaultx.app.core.vault.VaultArchive
import io.vaultx.app.core.vault.VaultManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖容器:避免 DI 框架(KSP 会增加构建脆弱性)。
 * 所有核心引擎在这里创建与接线。
 */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = AppSettings(context, appScope)
    val vaultManager = VaultManager(context.filesDir)
    val transferEngine = TransferEngine(vaultManager)
    val vaultArchive = VaultArchive(vaultManager)
    val session = VaultSessionHolder(
        scope = appScope,
        onLocked = { vaultId ->
            vaultManager.onVaultLocked(vaultId)
            // Coil 内存缓存里可能滞留已解密的明文位图(缩略图/原图),
            // 与"锁定即清空"语义一致,锁定时一并丢弃
            clearImageMemoryCache()
        },
    )
    val thumbnailer = Thumbnailer(vaultManager)
    val safTransfer = SafTransfer(context)
    val sessionManager = SessionManager(context.cacheDir)
    val biometrics = BiometricKeystore()

    fun init() {
        TinkStreaming.ensureRegistered()
        sweepSessionResidue()
        sweepPendingImports()
        watchAutoLock()
    }

    /** 冷启动清扫无锁模式可能残留的会话目录(进程死亡后的痕迹)。 */
    private fun sweepSessionResidue() {
        File(context.cacheDir, SESSIONS_DIR).deleteRecursively()
    }

    /** 冷启动清扫导入中断残留的 .pending 临时目录(进程死于半途时 catch 清理不会执行)。 */
    private fun sweepPendingImports() {
        vaultManager.pendingRoot().deleteRecursively()
    }

    /** 丢弃 Coil 内存缓存中的全部已解密位图;首次调用会触发 ImageLoader 惰性创建,失败静默跳过。 */
    fun clearImageMemoryCache() {
        runCatching {
            SingletonImageLoader.get(context).memoryCache?.clear()
        }
    }

    private fun watchAutoLock() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                session.onBackground(settings.autoLockSeconds.value)
            }

            override fun onStart(owner: LifecycleOwner) {
                session.onForeground()
            }
        })
    }

    companion object {
        const val SESSIONS_DIR = "sessions"
    }
}
