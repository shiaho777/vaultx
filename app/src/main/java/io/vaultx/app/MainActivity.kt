package io.vaultx.app

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
}
