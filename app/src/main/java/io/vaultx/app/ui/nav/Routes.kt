package io.vaultx.app.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 全部目的地。NavKey 需要可序列化以支持状态恢复。 */
sealed interface Route : NavKey {

    @Serializable
    data object ModeSelect : Route

    /** 应用设置(防截屏、自动锁定)。 */
    @Serializable
    data object AppSettings : Route

    /** 有锁模式:库列表。 */
    @Serializable
    data object LockedHome : Route

    @Serializable
    data class VaultHome(val vaultId: String) : Route

    @Serializable
    data class VaultSettings(val vaultId: String) : Route

    @Serializable
    data class ImageViewer(val vaultId: String, val entryId: String) : Route

    @Serializable
    data class VideoPlayer(val vaultId: String, val entryId: String) : Route

    @Serializable
    data class AudioPlayer(val vaultId: String, val entryId: String) : Route

    /** 无锁模式:临时空间。 */
    @Serializable
    data object SessionHome : Route

    /** 无锁模式:会话文件查看器(图片/视频/音频)。 */
    @Serializable
    data class SessionPlayer(val storedName: String) : Route

    companion object {
        fun vaultScoped(key: NavKey, vaultId: String): Boolean = when (key) {
            is VaultHome -> key.vaultId == vaultId
            is VaultSettings -> key.vaultId == vaultId
            is ImageViewer -> key.vaultId == vaultId
            is VideoPlayer -> key.vaultId == vaultId
            is AudioPlayer -> key.vaultId == vaultId
            else -> false
        }

        fun isVaultScoped(key: NavKey): Boolean = when (key) {
            is VaultHome, is VaultSettings, is ImageViewer, is VideoPlayer, is AudioPlayer -> true
            else -> false
        }
    }
}
