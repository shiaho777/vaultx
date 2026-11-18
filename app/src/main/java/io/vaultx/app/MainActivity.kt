package io.vaultx.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.vaultx.app.ui.nav.AppNav
import io.vaultx.app.ui.theme.ThemeMode
import io.vaultx.app.ui.theme.VaultXTheme
import kotlinx.coroutines.launch

/**
 * 入口。继承 FragmentActivity——BiometricPrompt 需要 Fragment 宿主。
 * FLAG_SECURE 随设置即时生效(防截屏/最近任务缩略图)。
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // SplashScreen API:冷启动用系统 splash(中性底+图标),替代旧主题黑底闪
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as VaultXApp).container

        // 系统分享冷启动:暂存导入源,等解锁进库后再消费(绝不绕过解锁)
        handleShareIntent(intent, container)
        handleShortcutIntent(intent, container)

        // 防截屏开关 → 窗口 flag 实时同步
        lifecycleScope.launch {
            container.settings.flagSecure.collect { enabled ->
                if (enabled) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE,
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }

        setContent {
            val themeModeName by container.settings.themeMode.collectAsState()
            val dynamicColor by container.settings.dynamicColor.collectAsState()
            val themeMode = runCatching { ThemeMode.valueOf(themeModeName) }.getOrDefault(ThemeMode.SYSTEM)
            VaultXTheme(themeMode = themeMode, dynamicColor = dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
                    AppNav(container)
                }
            }
        }
    }

    /** singleTask:应用已在运行时分享走这里,不再开新实例。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as VaultXApp).container
        handleShareIntent(intent, container)
        handleShortcutIntent(intent, container)
    }

    /** 长按图标快捷方式:vaultx://shortcut/<target> → 容器暂存,AppNav 消费路由。 */
    private fun handleShortcutIntent(intent: Intent?, container: AppContainer) {
        val data = intent?.data ?: return
        if (data.scheme == "vaultx" && data.host == "shortcut") {
            data.lastPathSegment?.let { container.setPendingShortcut(it) }
        }
    }

    /**
     * ACTION_SEND 取 EXTRA_STREAM 单 Uri;ACTION_SEND_MULTIPLE 取 Uri 列表。
     * 只把 Uri 包成 ImportSource 暂存到容器(元数据 query 失败也有兜底名),
     * 真正的解密/导入由 VaultHome 在已解锁会话里消费。
     */
    private fun handleShareIntent(intent: Intent?, container: AppContainer) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamUri(intent))
            Intent.ACTION_SEND_MULTIPLE -> streamUris(intent)
            else -> return
        }
        if (uris.isEmpty()) return
        val sources = uris.map { container.safTransfer.fileSource(it) }
        container.setPendingShare(sources)
    }

    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun streamUris(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}
