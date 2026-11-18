package io.vaultx.app.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.PasswordStrength
import io.vaultx.app.core.crypto.VaultCrypto
import io.vaultx.app.core.crypto.WrongPasswordException
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultMeta
import io.vaultx.app.ui.components.ConfirmDialog
import io.vaultx.app.ui.components.EmptyState
import io.vaultx.app.ui.components.PasswordField
import io.vaultx.app.ui.components.formatBytes
import io.vaultx.app.ui.components.pressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有锁模式首页:库列表 + 建库 + 解锁门(密码/生物识别) + 长按管理(改名/删除/备份)
 * + 顶栏紧急销毁。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedHomeScreen(
    container: AppContainer,
    onOpenVault: (String) -> Unit,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var vaults by remember { mutableStateOf<List<VaultCardInfo>?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var unlockTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var menuTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var renameTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var panicConfirm by remember { mutableStateOf(false) }
    var overflowMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 归档导入临时状态(密码对话框用)——只留 Uri,归档体不整段进内存
    var archiveImportMeta by remember { mutableStateOf<VaultMeta?>(null) }
    var archiveImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var exportVaultMeta by remember { mutableStateOf<VaultMeta?>(null) }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val backups = container.settings.backupTimes.value
            val list = runCatching {
                container.vaultManager.listVaults().map { meta ->
                    VaultCardInfo(
                        meta = meta,
                        entryCount = container.vaultManager.loadPlainIndex(meta.vaultId)
                            ?.entries?.count { !it.isFolder && !it.isTrashed },
                        // 诱骗库与真库共用磁盘目录:显示物理占用会把隐藏体量泄露给诱骗会话
                        usageBytes = if (meta.hasDecoy) null else container.vaultManager.vaultDiskUsage(meta.vaultId),
                        hasBio = container.vaultManager.readBioWrap(meta.vaultId) != null,
                        // 备份状态与占用同口径:配置诱骗的库不展示管理信息
                        lastBackup = if (meta.hasDecoy) null else backups[meta.vaultId] ?: 0L,
                    )
                }
            }.getOrElse {
                withContext(Dispatchers.Main) { error = context.getString(R.string.locked_load_failed, it.message) }
                null
            }
            if (list != null) vaults = list
        }
    }

    // 冷启动加载 + 从库内返回时刷新(条目数/占用可能已变)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 解锁成功后登记会话并进库。 */
    fun onUnlocked(u: UnlockedVault) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        container.session.put(u)
        onOpenVault(u.vaultId)
    }

    /** 生物识别解锁路径:bio.wrap 存在时可用;失败/点"使用密码"回退密码框。 */
    fun biometricUnlock(meta: VaultMeta) {
        val wrapped = container.vaultManager.readBioWrap(meta.vaultId) ?: return
        if (!container.biometrics.isAvailable(context)) {
            error = context.getString(R.string.locked_bio_unavailable)
            return
        }
        val activity = context as? FragmentActivity ?: return
        try {
            val cryptoObject = container.biometrics.cryptoObjectForUnlock(meta.vaultId, wrapped)
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(context),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val vmk = result.cryptoObject?.let { container.biometrics.unwrapVmk(it, wrapped) }
                        if (vmk == null) {
                            error = context.getString(R.string.locked_bio_unlock_failed)
                            unlockTarget = meta
                            return
                        }
                        onUnlocked(UnlockedVault(meta, VaultCrypto(vmk, meta.chunkSize), viaDecoy = false))
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        when (code) {
                            // 用户点了"使用密码"→ 回退密码框,不是死路
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON -> unlockTarget = meta
                            BiometricPrompt.ERROR_USER_CANCELED -> Unit
                            else -> {
                                error = context.getString(R.string.locked_bio_error, msg)
                                unlockTarget = meta
                            }
                        }
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(context.getString(R.string.locked_unlock_title, meta.name))
                    .setNegativeButtonText(context.getString(R.string.locked_use_password))
                    .build(),
                cryptoObject,
            )
        } catch (e: BiometricKeystore_KeyInvalidated) {
            container.vaultManager.deleteBioWrap(meta.vaultId)
            container.biometrics.deleteKey(meta.vaultId)
            error = context.getString(R.string.locked_bio_key_invalidated)
            unlockTarget = meta
        } catch (e: Exception) {
            error = context.getString(R.string.locked_bio_unlock_failed)
            unlockTarget = meta
        }
    }

    val archiveImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = context.getString(R.string.locked_reading_archive)
                // 不整段读进内存:peekMeta 扫头拿到 meta 就停;正式导入时再开一条流
                val meta = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            container.vaultArchive.peekMeta(it)
                        }
                    }.getOrNull()
                }
                busy = null
                if (meta != null) {
                    archiveImportMeta = meta
                    archiveImportUri = uri
                } else {
                    error = context.getString(R.string.locked_invalid_archive)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.locked_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.locked_back))
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { overflowMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.locked_more))
                        }
                        DropdownMenu(expanded = overflowMenu, onDismissRequest = { overflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.locked_import_archive)) },
                                onClick = {
                                    overflowMenu = false
                                    archiveImportLauncher.launch(arrayOf("*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.locked_panic_wipe_all), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { overflowMenu = false; panicConfirm = true },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.locked_new_vault))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy != null) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(busy!!, style = MaterialTheme.typography.labelMedium)
                }
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            val list = vaults
            when {
                list == null -> {
                    // 冷启动加载态:避免闪"空态"错觉
                    Column(
                        Modifier.weight(1f).fillMaxWidth().padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                list.isEmpty() -> {
                    EmptyState(
                        title = stringResource(R.string.locked_empty_title),
                        subtitle = stringResource(R.string.locked_empty_subtitle),
                        modifier = Modifier.weight(1f),
                    )
                }
                else -> {
                    LazyColumn(Modifier.weight(1f)) {
                        items(list, key = { it.meta.vaultId }) { info ->
                            VaultCard(
                                info = info,
                                onClick = {
                                    if (container.session.isUnlocked(info.meta.vaultId)) {
                                        onOpenVault(info.meta.vaultId)
                                    } else if (info.hasBio) {
                                        biometricUnlock(info.meta)
                                    } else {
                                        unlockTarget = info.meta
                                    }
                                },
                                onLongClick = { menuTarget = info.meta },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------- 建库对话框 ----------
    if (showCreate) {
        CreateVaultDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, password, highSec, masterGate ->
                showCreate = false
                scope.launch {
                    busy = context.getString(R.string.locked_creating)
                    runCatching {
                        withContext(Dispatchers.IO) {
                            container.vaultManager.createVault(
                                name,
                                password.toCharArray(),
                                masterGate = masterGate,
                                kdfParams = if (highSec) KdfParams.HIGH_SECURITY else KdfParams.DEFAULT,
                                onCreated = { container.session.put(it) },
                            )
                        }
                    }.onSuccess { meta ->
                        refresh()
                        onOpenVault(meta.vaultId)
                    }.onFailure { error = context.getString(R.string.locked_create_failed, it.message) }
                    busy = null
                }
            },
        )
    }

    // ---------- 解锁对话框 ----------
    unlockTarget?.let { meta ->
        UnlockDialog(
            meta = meta,
            hasBio = container.vaultManager.readBioWrap(meta.vaultId) != null,
            onBiometric = { biometricUnlock(meta); unlockTarget = null },
            onDismiss = { unlockTarget = null },
            onUnlock = { password, reportError ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            // rewrapParams=DEFAULT:老库的弱 KDF 参数在解锁时透明升级(只升不降)
                            container.vaultManager.unlock(meta.vaultId, password.toCharArray(), rewrapParams = KdfParams.DEFAULT)
                        }
                    }.onSuccess { u ->
                        // 对话框若在派生期间被取消,绝不能照旧开门
                        if (unlockTarget != null) {
                            unlockTarget = null
                            onUnlocked(u)
                        }
                    }.onFailure { e ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        reportError(
                            if (e is WrongPasswordException) context.getString(R.string.locked_wrong_password)
                            else context.getString(R.string.locked_unlock_failed, e.message),
                        )
                    }
                }
            },
        )
    }

    // ---------- 长按菜单 ----------
    menuTarget?.let { meta ->
        ModalBottomSheet(onDismissRequest = { menuTarget = null }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                SheetItem(stringResource(R.string.locked_rename)) { renameTarget = meta; menuTarget = null }
                SheetItem(stringResource(R.string.locked_export_backup)) { exportVaultMeta = meta; menuTarget = null }
                SheetItem(stringResource(R.string.locked_delete_irreversible), danger = true) { deleteTarget = meta; menuTarget = null }
            }
        }
    }

    // ---------- 归档导出 ----------
    val archiveExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val meta = exportVaultMeta
        exportVaultMeta = null
        if (uri != null && meta != null) {
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            container.vaultArchive.exportVault(meta.vaultId, out) { d, t ->
                                busy = context.getString(R.string.locked_export_progress, d, t)
                            }
                        }
                    }
                }.onSuccess { container.settings.recordBackup(meta.vaultId) }
                    .onFailure { error = context.getString(R.string.locked_export_failed, it.message) }
                busy = null
            }
        }
    }
    if (exportVaultMeta != null) {
        // 触发文件选择器
        androidx.compose.runtime.LaunchedEffect(exportVaultMeta) {
            archiveExportLauncher.launch("${exportVaultMeta!!.name}.fvault")
        }
    }

    // ---------- 归档导入(密码确认) ----------
    archiveImportMeta?.let { meta ->
        var pw by remember { mutableStateOf("") }
        var pwError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { archiveImportMeta = null; archiveImportUri = null },
            title = { Text(stringResource(R.string.locked_import_title, meta.name)) },
            text = {
                Column(Modifier.imePadding()) {
                    Text(stringResource(R.string.locked_import_password_hint))
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; pwError = null }, stringResource(R.string.locked_password), isError = pwError != null, supportingText = pwError)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = archiveImportUri ?: return@TextButton
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { ins ->
                                    container.vaultArchive.importVault(ins, pw.toCharArray()) { d, t ->
                                        busy = context.getString(R.string.locked_import_progress, d, t)
                                    }
                                } ?: throw io.vaultx.app.core.vault.ArchiveException(context.getString(R.string.locked_cannot_read_file))
                            }
                        }.onSuccess {
                            archiveImportMeta = null
                            archiveImportUri = null
                            refresh()
                        }.onFailure { e ->
                            pwError = if (e is WrongPasswordException) context.getString(R.string.locked_wrong_password) else e.message
                        }
                        busy = null
                    }
                }) { Text(stringResource(R.string.locked_import)) }
            },
            dismissButton = { TextButton(onClick = { archiveImportMeta = null; archiveImportUri = null }) { Text(stringResource(R.string.locked_cancel)) } },
        )
    }

    // ---------- 重命名 ----------
    renameTarget?.let { meta ->
        var name by remember { mutableStateOf(meta.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.locked_rename)) },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.locked_name)) }, modifier = Modifier.imePadding())
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = name
                    renameTarget = null
                    scope.launch(Dispatchers.IO) {
                        runCatching { container.vaultManager.renameVault(meta.vaultId, newName) }
                        refresh()
                    }
                }) { Text(stringResource(R.string.locked_confirm)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.locked_cancel)) } },
        )
    }

    // ---------- 删除确认 ----------
    deleteTarget?.let { meta ->
        ConfirmDialog(
            title = stringResource(R.string.locked_delete_title, meta.name),
            text = stringResource(R.string.locked_delete_text),
            confirmText = stringResource(R.string.locked_delete_forever),
            danger = true,
            onConfirm = {
                deleteTarget = null
                // 先同步锁会话(内存密钥即刻失效),重 IO 放后台
                container.session.lock(meta.vaultId)
                scope.launch(Dispatchers.IO) {
                    runCatching { container.biometrics.deleteKey(meta.vaultId) }
                    runCatching { container.vaultManager.deleteVault(meta.vaultId) }
                    refresh()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    // ---------- 紧急销毁 ----------
    if (panicConfirm) {
        var confirmText by remember { mutableStateOf("") }
        val panicKeyword = stringResource(R.string.locked_panic_keyword)
        AlertDialog(
            onDismissRequest = { panicConfirm = false },
            title = { Text(stringResource(R.string.locked_panic_title), color = MaterialTheme.colorScheme.error) },
            text = {
                Column(Modifier.imePadding()) {
                    Text(
                        stringResource(R.string.locked_panic_text, vaults?.size ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text(stringResource(R.string.locked_panic_confirm_label, panicKeyword)) },
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
                        scope.launch {
                            busy = context.getString(R.string.locked_wiping)
                            withContext(Dispatchers.IO) { container.panicWipe() }
                            busy = null
                            refresh()
                        }
                    },
                ) { Text(stringResource(R.string.locked_wipe_now), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { panicConfirm = false }) { Text(stringResource(R.string.locked_cancel)) } },
        )
    }
}

/** 别名占位:KeyInvalidatedException 引用。 */
private typealias BiometricKeystore_KeyInvalidated = io.vaultx.app.core.security.BiometricKeystore.KeyInvalidatedException

/** 卡片展示所需的快照:meta + 条目数 + 磁盘占用 + 是否已配生物识别 + 备份状态。 */
private data class VaultCardInfo(
    val meta: VaultMeta,
    val entryCount: Int?,
    val usageBytes: Long?,
    val hasBio: Boolean,
    /** 0 = 从未备份;>0 = 最近备份 epoch ms。null = 不展示(诱骗配置的库,避免泄露管理信息)。 */
    val lastBackup: Long?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCard(
    info: VaultCardInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val meta = info.meta
    val entryCount = info.entryCount
    val usageBytes = info.usageBytes
    val hasBio = info.hasBio
    val interactionSource = remember { MutableInteractionSource() }
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(meta.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(fmt.format(Date(meta.createdAt)))
                        if (entryCount != null) append(context.getString(R.string.locked_entry_count, entryCount))
                        if (usageBytes != null) append(" · ${formatBytes(usageBytes)}")
                        // 不显示"诱骗"标记:胁迫者打开 App 一眼看到它就前功尽弃
                        if (meta.masterGate) append(context.getString(R.string.locked_master_password_badge))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 备份状态:从未备份/超期才提示(正常备份不打扰);有文件的库才值得提醒
                val lastBackup = info.lastBackup
                if (lastBackup != null && (entryCount ?: 0) > 0) {
                    val overdue = System.currentTimeMillis() - lastBackup >
                        io.vaultx.app.core.state.AppSettings.BACKUP_REMINDER_DAYS * 24 * 3600 * 1000
                    if (overdue) {
                        Text(
                            if (lastBackup == 0L) stringResource(R.string.backup_never_short)
                            else stringResource(R.string.backup_overdue),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            if (hasBio) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun SheetItem(text: String, danger: Boolean = false, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth(),
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

/** 建库对话框:名称 + 密码(带强度条) + 确认 + 高强度档 + 总密码开关。 */
@Composable
private fun CreateVaultDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, password: String, highSec: Boolean, masterGate: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var highSec by remember { mutableStateOf(false) }
    var masterGate by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locked_create_title)) },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.locked_name)) }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                PasswordField(pw, { pw = it; err = null }, stringResource(R.string.locked_password), imeAction = ImeAction.Next)
                if (pw.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    val score = PasswordStrength.score(pw)
                    LinearProgressIndicator(
                        progress = { score / 4f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = when (score) {
                            1 -> MaterialTheme.colorScheme.error
                            2 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.locked_password_strength, stringResource(PasswordStrength.labelRes(score))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                PasswordField(pw2, { pw2 = it; err = null }, stringResource(R.string.locked_confirm_password), isError = err != null, supportingText = err)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = highSec, onCheckedChange = { highSec = it })
                    Text(stringResource(R.string.locked_high_security))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = masterGate, onCheckedChange = { masterGate = it })
                    Text(stringResource(R.string.locked_master_gate))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> err = context.getString(R.string.locked_name_empty)
                    pw.length < VaultManager_MIN_PW -> err = context.getString(R.string.locked_password_min, VaultManager_MIN_PW)
                    pw != pw2 -> err = context.getString(R.string.locked_password_mismatch)
                    else -> onCreate(name.trim(), pw, highSec, masterGate)
                }
            }) { Text(stringResource(R.string.locked_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.locked_cancel)) } },
    )
}

private const val VaultManager_MIN_PW = io.vaultx.app.core.vault.VaultManager.MIN_PASSWORD_LENGTH

/** 解锁对话框;诱骗密码同样走这里(服务端表现一致)。 */
@Composable
private fun UnlockDialog(
    meta: VaultMeta,
    hasBio: Boolean,
    onBiometric: () -> Unit,
    onDismiss: () -> Unit,
    onUnlock: (password: String, reportError: (String) -> Unit) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun tryUnlock() {
        if (pw.isNotEmpty() && !busy) {
            busy = true
            onUnlock(pw) {
                busy = false
                err = it
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.locked_unlock_title, meta.name)) },
        text = {
            Column(Modifier.imePadding()) {
                PasswordField(
                    pw, { pw = it; err = null }, stringResource(R.string.locked_password),
                    isError = err != null, supportingText = err,
                    onImeAction = { tryUnlock() },
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.locked_deriving), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (hasBio && !busy) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBiometric) {
                        Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.locked_use_biometrics))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { tryUnlock() }, enabled = !busy) { Text(stringResource(R.string.locked_unlock)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.locked_cancel)) } },
    )
}
