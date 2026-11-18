package io.vaultx.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 应用设置:后台自动锁定时长 + 防截屏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(container: AppContainer, onBack: () -> Unit, onAbout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val autoLock by container.settings.autoLockSeconds.collectAsState()
    val flagSecure by container.settings.flagSecure.collectAsState()
    val themeMode by container.settings.themeMode.collectAsState()
    val dynamicColor by container.settings.dynamicColor.collectAsState()

    val lockOptions = listOf(
        0 to stringResource(R.string.aset_lock_immediately),
        30 to stringResource(R.string.aset_lock_30_seconds),
        60 to stringResource(R.string.aset_lock_1_minute),
        300 to stringResource(R.string.aset_lock_5_minutes),
        600 to stringResource(R.string.aset_lock_10_minutes),
        900 to stringResource(R.string.aset_lock_15_minutes),
    )
    val panicKeyword = stringResource(R.string.aset_panic_confirm_keyword)
    var panicConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.aset_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.aset_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_theme_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            // 主题三选:坏数据/未知值回落跟随系统
            val themeOptions = listOf(
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            )
            val selectedTheme = runCatching { ThemeMode.valueOf(themeMode) }.getOrDefault(ThemeMode.SYSTEM)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = selectedTheme == mode,
                        onClick = { scope.launch { container.settings.setThemeMode(mode.name) } },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = themeOptions.size),
                    ) { Text(label) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_dynamic_color), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_dynamic_color_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = dynamicColor,
                    onCheckedChange = { scope.launch { container.settings.setDynamicColor(it) } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.aset_section_security), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.aset_flag_secure), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.aset_flag_secure_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = flagSecure,
                    onCheckedChange = { scope.launch { container.settings.setFlagSecure(it) } },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(stringResource(R.string.aset_auto_lock_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.aset_auto_lock_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            lockOptions.forEach { (sec, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = autoLock == sec,
                        onClick = { scope.launch { container.settings.setAutoLockSeconds(sec) } },
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                stringResource(R.string.aset_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // 关于入口:版本、许可、社区与隐私(置于危险区之前)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAbout)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_entry_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.about_entry_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.aset_danger_zone), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { panicConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.aset_panic_wipe), color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(R.string.aset_panic_wipe_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (panicConfirm) {
        var confirmText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { panicConfirm = false },
            title = { Text(stringResource(R.string.aset_panic_dialog_title), color = MaterialTheme.colorScheme.error) },
            text = {
                Column(Modifier.imePadding()) {
                    Text(
                        stringResource(R.string.aset_panic_dialog_text),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text(stringResource(R.string.aset_panic_confirm_label, panicKeyword)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = confirmText == panicKeyword,
                    onClick = {
                        panicConfirm = false
                        scope.launch(Dispatchers.IO) { container.panicWipe() }
                    },
                ) { Text(stringResource(R.string.aset_panic_wipe_now), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { panicConfirm = false }) { Text(stringResource(R.string.aset_cancel)) } },
        )
    }
}
