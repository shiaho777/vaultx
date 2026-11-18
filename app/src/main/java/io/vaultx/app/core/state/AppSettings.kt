package io.vaultx.app.core.state

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.vaultx.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settingsStore by preferencesDataStore(name = "vaultx_settings")

/**
 * 应用级设置(DataStore Preferences)。
 *
 * - [autoLockSeconds]:进入后台后多久锁定(<=0 = 立即)。
 * - [flagSecure]:防截屏/录屏/最近任务预览。debug 构建默认关(方便截图验证),release 默认开。
 */
class AppSettings(context: Context, scope: CoroutineScope) {

    private val store = context.settingsStore

    val autoLockSeconds: StateFlow<Int> = store.data
        .map { it[KEY_AUTO_LOCK_SECONDS] ?: DEFAULT_AUTO_LOCK_SECONDS }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_AUTO_LOCK_SECONDS)

    val flagSecure: StateFlow<Boolean> = store.data
        .map { it[KEY_FLAG_SECURE] ?: DEFAULT_FLAG_SECURE }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_FLAG_SECURE)

    suspend fun setAutoLockSeconds(seconds: Int) {
        store.edit { it[KEY_AUTO_LOCK_SECONDS] = seconds }
    }

    suspend fun setFlagSecure(enabled: Boolean) {
        store.edit { it[KEY_FLAG_SECURE] = enabled }
    }

    /** 文件排序偏好(SortBy 枚举名)。 */
    val sortBy: StateFlow<String> = store.data
        .map { it[KEY_SORT_BY] ?: DEFAULT_SORT_BY }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_SORT_BY)

    suspend fun setSortBy(name: String) {
        store.edit { it[KEY_SORT_BY] = name }
    }

    /** 浏览视图偏好("GRID"/"LIST")。 */
    val viewMode: StateFlow<String> = store.data
        .map { it[KEY_VIEW_MODE] ?: DEFAULT_VIEW_MODE }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_VIEW_MODE)

    suspend fun setViewMode(mode: String) {
        store.edit { it[KEY_VIEW_MODE] = mode }
    }

    /** 主题模式("SYSTEM"/"LIGHT"/"DARK")。 */
    val themeMode: StateFlow<String> = store.data
        .map { it[KEY_THEME_MODE] ?: DEFAULT_THEME_MODE }
        .stateIn(scope, SharingStarted.Eagerly, DEFAULT_THEME_MODE)

    suspend fun setThemeMode(mode: String) {
        store.edit { it[KEY_THEME_MODE] = mode }
    }

    /** Material You 动态取色(Android 12+;低版本自动回退品牌色)。默认开。 */
    val dynamicColor: StateFlow<Boolean> = store.data
        .map { it[KEY_DYNAMIC_COLOR] ?: true }
        .stateIn(scope, SharingStarted.Eagerly, true)

    suspend fun setDynamicColor(enabled: Boolean) {
        store.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    /** 首次启动引导是否已完成。 */
    val onboardingDone: StateFlow<Boolean> = store.data
        .map { it[KEY_ONBOARDING_DONE] ?: false }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun setOnboardingDone() {
        store.edit { it[KEY_ONBOARDING_DONE] = true }
    }

    /**
     * 每库最近一次 .fvault 备份导出时间(epoch ms;0 = 从未备份)。
     * JSON map 存单键,避免每库一个 DataStore 键的动态膨胀。
     */
    val backupTimes: StateFlow<Map<String, Long>> = store.data
        .map { prefs -> decodeBackupTimes(prefs[KEY_BACKUP_TIMES]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    suspend fun recordBackup(vaultId: String, atMillis: Long = System.currentTimeMillis()) {
        store.edit { prefs ->
            val cur = decodeBackupTimes(prefs[KEY_BACKUP_TIMES])
            prefs[KEY_BACKUP_TIMES] = (cur + (vaultId to atMillis)).entries
                .joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    companion object {
        private val KEY_AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
        private val KEY_FLAG_SECURE = booleanPreferencesKey("flag_secure")
        private val KEY_SORT_BY = stringPreferencesKey("sort_by")
        private val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        private val KEY_ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val KEY_BACKUP_TIMES = stringPreferencesKey("backup_times")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")

        /** "vaultId=epochMs;vaultId=epochMs" 解码;vaultId 是 UUID,不含分隔符。坏数据回落空表。 */
        private fun decodeBackupTimes(raw: String?): Map<String, Long> {
            if (raw.isNullOrBlank()) return emptyMap()
            return raw.split(';').mapNotNull { part ->
                val i = part.lastIndexOf('=')
                if (i <= 0) null
                else part.substring(i + 1).toLongOrNull()?.let { part.substring(0, i) to it }
            }.toMap()
        }

        /** 备份提醒阈值:超过 30 天未备份(或从未备份)提示。 */
        const val BACKUP_REMINDER_DAYS = 30L

        const val DEFAULT_AUTO_LOCK_SECONDS = 60
        val DEFAULT_FLAG_SECURE = !BuildConfig.DEBUG
        const val DEFAULT_SORT_BY = "NAME"
        const val DEFAULT_VIEW_MODE = "GRID"
        const val DEFAULT_THEME_MODE = "SYSTEM"
    }
}
