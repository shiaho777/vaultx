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

    companion object {
        private val KEY_AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
        private val KEY_FLAG_SECURE = booleanPreferencesKey("flag_secure")
        private val KEY_SORT_BY = stringPreferencesKey("sort_by")
        private val KEY_VIEW_MODE = stringPreferencesKey("view_mode")

        const val DEFAULT_AUTO_LOCK_SECONDS = 60
        val DEFAULT_FLAG_SECURE = !BuildConfig.DEBUG
        const val DEFAULT_SORT_BY = "NAME"
        const val DEFAULT_VIEW_MODE = "GRID"
    }
}
