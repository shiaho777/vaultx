package io.vaultx.app.ui.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import io.vaultx.app.AppContainer
import io.vaultx.app.ui.mode.ModeSelectScreen
import io.vaultx.app.ui.session.SessionHomeScreen
import io.vaultx.app.ui.session.SessionPlayerScreen
import io.vaultx.app.ui.settings.AppSettingsScreen
import io.vaultx.app.ui.vault.LockedHomeScreen
import io.vaultx.app.ui.vault.VaultHomeScreen
import io.vaultx.app.ui.vault.VaultSettingsScreen
import io.vaultx.app.ui.viewer.AudioPlayerScreen
import io.vaultx.app.ui.viewer.ImageViewerScreen
import io.vaultx.app.ui.viewer.VideoPlayerScreen

/**
 * 导航宿主:Navigation 3 的 NavDisplay + 序列化 Route 栈。
 *
 * 关键安全行为:订阅 [VaultSessionHolder.lockEvents]——库被锁定(手动/超时/诱骗切换)
 * 时,把栈里属于该库的所有页面一并弹出,界面上不留任何明文入口。
 */
@Composable
fun AppNav(container: AppContainer, modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(Route.ModeSelect)

    // 锁定事件 → 裁剪返回栈
    LaunchedEffect(Unit) {
        container.session.lockEvents.collect { vaultId ->
            if (vaultId == null) {
                backStack.removeAll { Route.isVaultScoped(it) || it == Route.SessionHome || it is Route.SessionPlayer }
            } else {
                backStack.removeAll { Route.vaultScoped(it, vaultId) }
            }
            if (backStack.isEmpty()) backStack.add(Route.LockedHome)
        }
    }

    val enterSpec: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation3.scene.Scene<androidx.navigation3.runtime.NavKey>>.() -> ContentTransform = {
        slideInHorizontally(tween(280)) { it / 4 } + fadeIn(tween(280)) togetherWith
            slideOutHorizontally(tween(280)) { -it / 4 } + fadeOut(tween(280))
    }
    val popSpec: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation3.scene.Scene<androidx.navigation3.runtime.NavKey>>.() -> ContentTransform = {
        slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(280)) togetherWith
            slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(280))
    }
    val predictivePopSpec: androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation3.scene.Scene<androidx.navigation3.runtime.NavKey>>.(Int) -> ContentTransform = { _ ->
        slideInHorizontally(tween(280)) { -it / 4 } + fadeIn(tween(280)) togetherWith
            slideOutHorizontally(tween(280)) { it / 4 } + fadeOut(tween(280))
    }

    val context = LocalContext.current
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        // 栈底再弹会留空栈死屏;根级返回交还系统结束 Activity
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            } else {
                (context as? android.app.Activity)?.finish()
            }
        },
        transitionSpec = enterSpec,
        popTransitionSpec = popSpec,
        predictivePopTransitionSpec = predictivePopSpec,
        entryProvider = entryProvider {
            entry<Route.ModeSelect> {
                ModeSelectScreen(
                    onLockedMode = { backStack.add(Route.LockedHome) },
                    onSessionMode = { backStack.add(Route.SessionHome) },
                    onSettings = { backStack.add(Route.AppSettings) },
                )
            }
            entry<Route.AppSettings> {
                AppSettingsScreen(container = container, onBack = { backStack.removeLastOrNull() })
            }
            entry<Route.LockedHome> {
                LockedHomeScreen(
                    container = container,
                    onOpenVault = { backStack.add(Route.VaultHome(it)) },
                )
            }
            entry<Route.VaultHome> { key ->
                VaultHomeScreen(
                    container = container,
                    vaultId = key.vaultId,
                    onOpenEntry = { entry ->
                        when (entry.kind) {
                            io.vaultx.app.core.vault.MediaKind.IMAGE -> backStack.add(Route.ImageViewer(key.vaultId, entry.id))
                            io.vaultx.app.core.vault.MediaKind.VIDEO -> backStack.add(Route.VideoPlayer(key.vaultId, entry.id))
                            io.vaultx.app.core.vault.MediaKind.AUDIO -> backStack.add(Route.AudioPlayer(key.vaultId, entry.id))
                            else -> Unit
                        }
                    },
                    onSettings = { backStack.add(Route.VaultSettings(key.vaultId)) },
                )
            }
            entry<Route.VaultSettings> { key ->
                VaultSettingsScreen(
                    container = container,
                    vaultId = key.vaultId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.ImageViewer> { key ->
                ImageViewerScreen(
                    container = container, vaultId = key.vaultId, entryId = key.entryId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.VideoPlayer> { key ->
                VideoPlayerScreen(
                    container = container, vaultId = key.vaultId, entryId = key.entryId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.AudioPlayer> { key ->
                AudioPlayerScreen(
                    container = container, vaultId = key.vaultId, entryId = key.entryId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.SessionHome> {
                SessionHomeScreen(
                    container = container,
                    onPreview = { backStack.add(Route.SessionPlayer(it)) },
                    onExit = { backStack.removeLastOrNull() },
                )
            }
            entry<Route.SessionPlayer> { key ->
                SessionPlayerScreen(
                    container = container, storedName = key.storedName,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}
