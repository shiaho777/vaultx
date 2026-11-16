package io.vaultx.app.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.core.vault.VaultMeta
import io.vaultx.app.ui.components.ConfirmDialog
import io.vaultx.app.ui.components.PasswordField
import io.vaultx.app.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 库设置:改密码 / 总密码 / 诱骗库 / 生物识别 / 磁盘占用。
 * 诱骗会话中管理区隐藏(诱骗持有者不该看到这些开关的存在)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsScreen(
    container: AppContainer,
    vaultId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val unlocked = container.session.get(vaultId)

    // 协程/回调内使用的文案在组合期捕获
    val cancelText = stringResource(R.string.vset_cancel)
    val busyConvertingIndex = stringResource(R.string.vset_busy_converting_index)
    val errToggleFailedFmt = stringResource(R.string.vset_error_toggle_failed)
    val errBioInitFmt = stringResource(R.string.vset_error_bio_init)
    val errBioWrap = stringResource(R.string.vset_error_bio_wrap)
    val noticeBioEnabled = stringResource(R.string.vset_notice_bio_enabled)
    val errWriteFmt = stringResource(R.string.vset_error_write)
    val errBioAuthFmt = stringResource(R.string.vset_error_bio_auth)
    val bioPromptTitle = stringResource(R.string.vset_bio_prompt_title)
    val bioPromptSubtitle = stringResource(R.string.vset_bio_prompt_subtitle)
    val errGenericFmt = stringResource(R.string.vset_error_generic)
    val strengthFmt = stringResource(R.string.vset_password_strength)
    val passwordShortError = stringResource(R.string.vset_error_password_short)
    val passwordMismatchError = stringResource(R.string.vset_error_password_mismatch)
    val busyChangingPassword = stringResource(R.string.vset_busy_changing_password)
    val noticePasswordUpdated = stringResource(R.string.vset_notice_password_updated)
    val busyConfiguringDecoy = stringResource(R.string.vset_busy_configuring_decoy)
    val noticeDecoyConfigured = stringResource(R.string.vset_notice_decoy_configured)
    val noticeDecoyRemoved = stringResource(R.string.vset_notice_decoy_removed)
    val noticeBioDisabled = stringResource(R.string.vset_notice_bio_disabled)

    // 会话内存副本零 IO;无会话(异常路径)才在 IO 线程读盘兜底
    var meta by remember { mutableStateOf(unlocked?.meta ?: container.vaultManager.metaOf(vaultId)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var changePwDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var decoyDialog by remember { mutableStateOf(false) }
    var disableDecoyConfirm by remember { mutableStateOf(false) }
    var disableBioConfirm by remember { mutableStateOf(false) }

    val viaDecoy = unlocked?.viaDecoy == true
    var bioEnabled by remember { mutableStateOf(false) }
    val bioAvailable = container.biometrics.isAvailable(context)
    // 诱骗库与真库共用磁盘目录:有诱骗时不显示物理占用(否则会向诱骗会话泄露隐藏体量)
    var diskUsage by remember { mutableStateOf<Long?>(null) }
    // 磁盘占用是全目录 walk,生物包裹体是文件读——都放 IO 线程,不在组合期做
    androidx.compose.runtime.LaunchedEffect(vaultId, meta.hasDecoy) {
        bioEnabled = withContext(Dispatchers.IO) { container.vaultManager.readBioWrap(vaultId) != null }
        diskUsage = if (meta.hasDecoy) null
        else withContext(Dispatchers.IO) { container.vaultManager.vaultDiskUsage(vaultId) }
    }

    fun refreshMeta() {
        // 优先取会话内存副本(改密码/开关诱骗后引擎已同步更新),避免主线程读盘
        val u = container.session.get(vaultId)
        if (u != null) {
            meta = u.meta
        } else {
            scope.launch {
                meta = withContext(Dispatchers.IO) { container.vaultManager.metaOf(vaultId) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vset_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.vset_back))
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

            // 基本信息
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SettingRow(stringResource(R.string.vset_label_name), meta.name) }
                TextButton(onClick = { renameDialog = true }) { Text(stringResource(R.string.vset_rename)) }
            }
            diskUsage?.let { SettingRow(stringResource(R.string.vset_label_disk_usage), formatBytes(it)) }
            // 备份状态:诱骗会话不显示(真库备份时间属于管理信息)
            if (!viaDecoy) {
                val backupTimes by container.settings.backupTimes.collectAsState()
                val lastBackup = backupTimes[vaultId] ?: 0L
                SettingRow(
                    stringResource(R.string.backup_row_title),
                    if (lastBackup == 0L) stringResource(R.string.backup_row_never)
                    else stringResource(
                        R.string.backup_row_last,
                        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(lastBackup)),
                    ),
                )
                Text(
                    stringResource(R.string.backup_row_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingRow(
                stringResource(R.string.vset_label_kdf_strength),
                stringResource(R.string.vset_kdf_params, meta.kdfParams.memoryKiB / 1024, meta.kdfParams.iterations),
            )
            // 诱骗会话中不显示任何"你在假库"的标记——界面与真库完全一致才有意义

            busy?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.vset_section_password), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { changePwDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (viaDecoy) R.string.vset_change_decoy_password else R.string.vset_change_password_no_reencrypt)) }

            if (!viaDecoy) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text(stringResource(R.string.vset_section_access_control), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))

                // 总密码开关
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.vset_master_password), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (meta.hasDecoy) R.string.vset_master_gate_hint_decoy else R.string.vset_master_gate_hint_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = meta.masterGate,
                        enabled = unlocked != null && !(meta.hasDecoy && meta.masterGate),
                        onCheckedChange = { wantOn ->
                            val u = unlocked ?: return@Switch
                            scope.launch {
                                busy = busyConvertingIndex
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        // 与库内导入/删除共用同一把索引锁,防并发写互踩
                                        container.indexMutexFor(vaultId).withLock {
                                            val idx = container.vaultManager.loadIndex(u)
                                            container.vaultManager.setMasterGate(u, idx, wantOn)
                                        }
                                    }
                                }.onSuccess { refreshMeta() }
                                    .onFailure { error = String.format(errToggleFailedFmt, it.message) }
                                busy = null
                            }
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 诱骗库
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.vset_decoy_vault), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(if (meta.hasDecoy) R.string.vset_decoy_hint_configured else R.string.vset_decoy_hint_unconfigured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (meta.hasDecoy) {
                        TextButton(onClick = { disableDecoyConfirm = true }) {
                            Text(stringResource(R.string.vset_remove), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = { decoyDialog = true }) { Text(stringResource(R.string.vset_configure)) }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 生物识别
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.vset_biometric_unlock), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(
                                when {
                                    !bioAvailable -> R.string.vset_bio_hint_unavailable
                                    bioEnabled -> R.string.vset_bio_hint_enabled
                                    else -> R.string.vset_bio_hint_disabled
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bioEnabled) {
                        TextButton(onClick = { disableBioConfirm = true }) {
                            Text(stringResource(R.string.vset_disable), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(
                            enabled = bioAvailable && unlocked != null && !viaDecoy,
                            onClick = {
                                val u = unlocked ?: return@TextButton
                                val activity = context as? FragmentActivity ?: return@TextButton
                                // auth-required 密钥的 ENCRYPT 同样要现场活体——开启也走 BiometricPrompt
                                val crypto = try {
                                    container.biometrics.cryptoObjectForWrap(vaultId)
                                } catch (e: Exception) {
                                    error = String.format(errBioInitFmt, e.message)
                                    return@TextButton
                                }
                                BiometricPrompt(
                                    activity,
                                    ContextCompat.getMainExecutor(context),
                                    object : BiometricPrompt.AuthenticationCallback() {
                                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                            val wrapped = result.cryptoObject?.let {
                                                container.biometrics.wrapVmkWith(it, u.crypto.vmk)
                                            }
                                            if (wrapped == null) {
                                                error = errBioWrap
                                                return
                                            }
                                            scope.launch {
                                                runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        container.vaultManager.writeBioWrap(vaultId, wrapped)
                                                    }
                                                }.onSuccess {
                                                    bioEnabled = true
                                                    notice = noticeBioEnabled
                                                    refreshMeta()
                                                }.onFailure { error = String.format(errWriteFmt, it.message) }
                                            }
                                        }

                                        override fun onAuthenticationError(code: Int, errString: CharSequence) {
                                            if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                                                code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                            ) {
                                                error = String.format(errBioAuthFmt, errString)
                                            }
                                        }
                                    },
                                ).authenticate(
                                    BiometricPrompt.PromptInfo.Builder()
                                        .setTitle(bioPromptTitle)
                                        .setSubtitle(bioPromptSubtitle)
                                        .setNegativeButtonText(cancelText)
                                        .build(),
                                    crypto,
                                )
                            },
                        ) { Text(stringResource(R.string.vset_enable)) }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ---------- 重命名库 ----------
    if (renameDialog) {
        var name by remember { mutableStateOf(meta.name) }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text(stringResource(R.string.vset_rename_dialog_title)) },
            text = {
                Column(Modifier.imePadding()) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; err = null },
                        singleLine = true,
                        isError = err != null,
                        supportingText = { err?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth().imePadding(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        err = context.getString(R.string.vset_error_name_empty)
                    } else {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { container.vaultManager.renameVault(vaultId, trimmed) }
                            }.onSuccess { renameDialog = false; refreshMeta() }
                                .onFailure { err = String.format(errGenericFmt, it.message) }
                        }
                    }
                }) { Text(stringResource(R.string.vset_ok)) }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text(stringResource(R.string.vset_cancel)) } },
        )
    }

    // ---------- 改密码 ----------
    if (changePwDialog) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { changePwDialog = false },
            title = { Text(stringResource(if (viaDecoy) R.string.vset_change_decoy_password else R.string.vset_change_password_title)) },
            text = {
                Column(Modifier.imePadding()) {
                    Text(stringResource(R.string.vset_change_password_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        pw, { pw = it; err = null }, stringResource(R.string.vset_new_password),
                        supportingText = io.vaultx.app.core.crypto.PasswordStrength.labelRes(io.vaultx.app.core.crypto.PasswordStrength.score(pw))
                            .takeIf { it != 0 }?.let { String.format(strengthFmt, stringResource(it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw2, { pw2 = it; err = null }, stringResource(R.string.vset_confirm_new_password), isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pw.length < 4 -> err = passwordShortError
                        pw != pw2 -> err = passwordMismatchError
                        else -> {
                            val u = unlocked ?: return@TextButton
                            scope.launch {
                                busy = busyChangingPassword
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        container.vaultManager.changePassword(u, pw.toCharArray())
                                    }
                                }.onSuccess { notice = noticePasswordUpdated; changePwDialog = false; refreshMeta() }
                                    .onFailure { err = String.format(errGenericFmt, it.message) }
                                busy = null
                            }
                        }
                    }
                }) { Text(stringResource(R.string.vset_confirm)) }
            },
            dismissButton = { TextButton(onClick = { changePwDialog = false }) { Text(stringResource(R.string.vset_cancel)) } },
        )
    }

    // ---------- 配置诱骗库 ----------
    if (decoyDialog) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { decoyDialog = false },
            title = { Text(stringResource(R.string.vset_decoy_dialog_title)) },
            text = {
                Column(Modifier.imePadding()) {
                    Text(
                        stringResource(R.string.vset_decoy_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        pw, { pw = it; err = null }, stringResource(R.string.vset_decoy_password),
                        supportingText = io.vaultx.app.core.crypto.PasswordStrength.labelRes(io.vaultx.app.core.crypto.PasswordStrength.score(pw))
                            .takeIf { it != 0 }?.let { String.format(strengthFmt, stringResource(it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw2, { pw2 = it; err = null }, stringResource(R.string.vset_confirm_decoy_password), isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pw.length < 4 -> err = passwordShortError
                        pw != pw2 -> err = passwordMismatchError
                        else -> {
                            val u = unlocked ?: return@TextButton
                            scope.launch {
                                busy = busyConfiguringDecoy
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        container.indexMutexFor(vaultId).withLock {
                                            container.vaultManager.enableDecoy(u, pw.toCharArray())
                                        }
                                    }
                                }.onSuccess { notice = noticeDecoyConfigured; decoyDialog = false; refreshMeta() }
                                    .onFailure { err = String.format(errGenericFmt, it.message) }
                                busy = null
                            }
                        }
                    }
                }) { Text(stringResource(R.string.vset_enable)) }
            },
            dismissButton = { TextButton(onClick = { decoyDialog = false }) { Text(stringResource(R.string.vset_cancel)) } },
        )
    }

    // ---------- 移除诱骗 ----------
    if (disableDecoyConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.vset_remove_decoy_title),
            text = stringResource(R.string.vset_remove_decoy_text),
            confirmText = stringResource(R.string.vset_remove),
            danger = true,
            onConfirm = {
                val u = unlocked ?: return@ConfirmDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            container.indexMutexFor(vaultId).withLock { container.vaultManager.disableDecoy(u) }
                        }
                    }.onSuccess { notice = noticeDecoyRemoved; refreshMeta() }
                        .onFailure { error = String.format(errGenericFmt, it.message) }
                    disableDecoyConfirm = false
                }
            },
            onDismiss = { disableDecoyConfirm = false },
        )
    }

    // ---------- 关闭生物识别 ----------
    if (disableBioConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.vset_disable_bio_title),
            text = stringResource(R.string.vset_disable_bio_text),
            confirmText = stringResource(R.string.vset_disable),
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { container.vaultManager.deleteBioWrap(vaultId) }
                        runCatching { container.biometrics.deleteKey(vaultId) }
                    }
                    bioEnabled = false
                    disableBioConfirm = false
                    notice = noticeBioDisabled
                    refreshMeta()
                }
            },
            onDismiss = { disableBioConfirm = false },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, tint: androidx.compose.ui.graphics.Color? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = tint ?: MaterialTheme.colorScheme.onSurface)
    }
}
