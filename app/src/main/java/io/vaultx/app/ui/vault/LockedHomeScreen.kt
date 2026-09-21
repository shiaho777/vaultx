package io.vaultx.app.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.vaultx.app.AppContainer
import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.VaultCrypto
import io.vaultx.app.core.crypto.WrongPasswordException
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultMeta
import io.vaultx.app.ui.components.ConfirmDialog
import io.vaultx.app.ui.components.EmptyState
import io.vaultx.app.ui.components.PasswordField
import io.vaultx.app.ui.components.pressScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 有锁模式首页:库列表 + 建库 + 解锁门(密码/生物识别) + 长按管理(改名/删除/备份)。
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
    var vaults by remember { mutableStateOf(container.vaultManager.listVaults()) }
    var showCreate by remember { mutableStateOf(false) }
    var unlockTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var menuTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var renameTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<VaultMeta?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 归档导入临时状态(密码对话框用)
    var archiveImportMeta by remember { mutableStateOf<VaultMeta?>(null) }
    var archiveImportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var exportVaultMeta by remember { mutableStateOf<VaultMeta?>(null) }

    fun refresh() {
        vaults = container.vaultManager.listVaults()
    }

    /** 解锁成功后登记会话并进库。 */
    fun onUnlocked(u: UnlockedVault) {
        container.session.put(u)
        onOpenVault(u.vaultId)
    }

    /** 生物识别解锁路径:bio.wrap 存在时可用。 */
    fun biometricUnlock(meta: VaultMeta) {
        val wrapped = container.vaultManager.readBioWrap(meta.vaultId) ?: return
        if (!container.biometrics.isAvailable(context)) {
            error = "生物识别不可用"
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
                            error = "生物识别解锁失败"
                            return
                        }
                        onUnlocked(UnlockedVault(meta, VaultCrypto(vmk), viaDecoy = false))
                    }

                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (code != BiometricPrompt.ERROR_USER_CANCELED &&
                            code != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            error = "生物识别失败:$msg"
                        }
                    }
                },
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("解锁「${meta.name}」")
                    .setNegativeButtonText("使用密码")
                    .build(),
                cryptoObject,
            )
        } catch (e: BiometricKeystore_KeyInvalidated) {
            container.vaultManager.deleteBioWrap(meta.vaultId)
            container.biometrics.deleteKey(meta.vaultId)
            error = "生物识别密钥已失效(指纹变更),请用密码解锁"
        } catch (e: Exception) {
            error = "生物识别解锁失败"
        }
    }

    val archiveImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                busy = "导入归档…"
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                busy = null
                if (bytes != null) {
                    // 先读 meta 让用户确认并输密码
                    runCatching { container.vaultArchive.peekMeta(bytes.inputStream()) }
                        .onSuccess { meta -> archiveImportMeta = meta; archiveImportBytes = bytes }
                        .onFailure { error = "不是有效的 .fvault 归档" }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("保险库") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新建保险库")
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
            if (vaults.isEmpty()) {
                EmptyState(
                    title = "还没有保险库",
                    subtitle = "点右下角 + 新建一个;也可长按此处导入口令(用 .fvault 归档恢复)",
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { archiveImportLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp),
                ) { Text("导入 .fvault 归档") }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(vaults, key = { it.vaultId }) { meta ->
                        VaultCard(
                            meta = meta,
                            entryCount = container.vaultManager.loadPlainIndex(meta.vaultId)?.entries?.count { !it.isFolder },
                            hasBio = container.vaultManager.readBioWrap(meta.vaultId) != null,
                            onClick = {
                                if (container.session.isUnlocked(meta.vaultId)) {
                                    onOpenVault(meta.vaultId)
                                } else if (container.vaultManager.readBioWrap(meta.vaultId) != null) {
                                    biometricUnlock(meta)
                                } else {
                                    unlockTarget = meta
                                }
                            },
                            onLongClick = { menuTarget = meta },
                        )
                    }
                }
                TextButton(
                    onClick = { archiveImportLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                ) { Text("导入 .fvault 归档") }
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
                    busy = "创建保险库…(Argon2id 派生中)"
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
                    }.onFailure { error = "创建失败:${it.message}" }
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
                            container.vaultManager.unlock(meta.vaultId, password.toCharArray())
                        }
                    }.onSuccess { u ->
                        unlockTarget = null
                        onUnlocked(u)
                    }.onFailure { e ->
                        reportError(if (e is WrongPasswordException) "密码错误" else "解锁失败:${e.message}")
                    }
                }
            },
        )
    }

    // ---------- 长按菜单 ----------
    menuTarget?.let { meta ->
        ModalBottomSheet(onDismissRequest = { menuTarget = null }) {
            Column(Modifier.padding(bottom = 32.dp)) {
                SheetItem("重命名") { renameTarget = meta; menuTarget = null }
                SheetItem("导出 .fvault 备份") { exportVaultMeta = meta; menuTarget = null }
                SheetItem("删除(不可恢复)", danger = true) { deleteTarget = meta; menuTarget = null }
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
                busy = "导出归档…"
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            container.vaultArchive.exportVault(meta.vaultId, out)
                        }
                    }
                }.onFailure { error = "导出失败:${it.message}" }
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
            onDismissRequest = { archiveImportMeta = null; archiveImportBytes = null },
            title = { Text("导入「${meta.name}」") },
            text = {
                Column(Modifier.imePadding()) {
                    Text("输入该归档的密码(真密码或诱骗密码均可)")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; pwError = null }, "密码", isError = pwError != null, supportingText = pwError)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = archiveImportBytes ?: return@TextButton
                    scope.launch {
                        busy = "导入中…"
                        runCatching {
                            withContext(Dispatchers.IO) {
                                container.vaultArchive.importVault(bytes.inputStream(), pw.toCharArray())
                            }
                        }.onSuccess {
                            archiveImportMeta = null
                            archiveImportBytes = null
                            refresh()
                        }.onFailure { e ->
                            pwError = if (e is WrongPasswordException) "密码错误" else "导入失败:${e.message}"
                        }
                        busy = null
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { archiveImportMeta = null; archiveImportBytes = null }) { Text("取消") } },
        )
    }

    // ---------- 重命名 ----------
    renameTarget?.let { meta ->
        var name by remember { mutableStateOf(meta.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.imePadding())
            },
            confirmButton = {
                TextButton(onClick = {
                    container.vaultManager.renameVault(meta.vaultId, name)
                    renameTarget = null
                    refresh()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    // ---------- 删除确认 ----------
    deleteTarget?.let { meta ->
        ConfirmDialog(
            title = "删除「${meta.name}」?",
            text = "库内所有加密文件将永久删除,无法恢复。建议先导出 .fvault 备份。",
            confirmText = "永久删除",
            danger = true,
            onConfirm = {
                container.session.lock(meta.vaultId)
                container.biometrics.deleteKey(meta.vaultId)
                container.vaultManager.deleteVault(meta.vaultId)
                deleteTarget = null
                refresh()
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

/** 别名占位:KeyInvalidatedException 引用。 */
private typealias BiometricKeystore_KeyInvalidated = io.vaultx.app.core.security.BiometricKeystore.KeyInvalidatedException

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCard(
    meta: VaultMeta,
    entryCount: Int?,
    hasBio: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
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
                        if (entryCount != null) append(" · $entryCount 项")
                        if (meta.hasDecoy) append(" · 诱骗")
                        if (meta.masterGate) append(" · 总密码")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** 建库对话框:名称 + 密码 + 确认 + 高强度档 + 总密码开关。 */
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建保险库") },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                PasswordField(pw, { pw = it; err = null }, "密码", imeAction = ImeAction.Next)
                Spacer(Modifier.height(8.dp))
                PasswordField(pw2, { pw2 = it; err = null }, "确认密码", isError = err != null, supportingText = err)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = highSec, onCheckedChange = { highSec = it })
                    Text("高强度模式(Argon2id 64MiB,解锁更慢)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = masterGate, onCheckedChange = { masterGate = it })
                    Text("总密码(进库必须解锁;关闭则索引明文可读)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> err = "名称不能为空"
                    pw.length < VaultManager_MIN_PW -> err = "密码至少 $VaultManager_MIN_PW 位"
                    pw != pw2 -> err = "两次密码不一致"
                    else -> onCreate(name.trim(), pw, highSec, masterGate)
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val VaultManager_MIN_PW = 4

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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("解锁「${meta.name}」") },
        text = {
            Column(Modifier.imePadding()) {
                PasswordField(
                    pw, { pw = it; err = null }, "密码",
                    isError = err != null, supportingText = err,
                    onImeAction = { if (pw.isNotEmpty()) onUnlock(pw) { err = it } },
                )
                if (hasBio) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onBiometric) {
                        Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("改用生物识别")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (pw.isNotEmpty()) onUnlock(pw) { err = it } }) { Text("解锁") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
