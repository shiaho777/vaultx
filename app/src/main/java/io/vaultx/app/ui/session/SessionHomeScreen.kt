package io.vaultx.app.ui.session

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.vaultx.app.AppContainer
import io.vaultx.app.core.session.SessionManager.SessionFile
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.ui.components.ConfirmDialog
import io.vaultx.app.ui.components.EmptyState
import io.vaultx.app.ui.components.PasswordField
import io.vaultx.app.ui.components.formatBytes

/**
 * 无锁模式主页:临时文件列表 + 导入 + .vlt 加密转换/预览 + 退出即焚。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHomeScreen(
    container: AppContainer,
    onPreview: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SessionViewModel = viewModel { SessionViewModel(container) }
    val files by vm.files.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()

    var menuFor by remember { mutableStateOf<SessionFile?>(null) }
    var renameFor by remember { mutableStateOf<SessionFile?>(null) }
    var deleteFor by remember { mutableStateOf<SessionFile?>(null) }
    var exitConfirm by remember { mutableStateOf(false) }
    // .vlt 相关
    var vltPasswordFor by remember { mutableStateOf<android.net.Uri?>(null) }
    var vltExportFor by remember { mutableStateOf<SessionFile?>(null) }
    var plainExportFor by remember { mutableStateOf<SessionFile?>(null) }
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // .vlt 文件需要密码;其他直接进临时空间
        val vlt = uris.firstOrNull { it.lastPathSegment?.endsWith(".vlt") == true }
        val plain = uris.filter { it.lastPathSegment?.endsWith(".vlt") != true }
        if (plain.isNotEmpty()) vm.import(plain.map { container.safTransfer.fileSource(it) })
        if (vlt != null) vltPasswordFor = vlt
    }

    val vltExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val f = vltExportFor
        if (uri != null && f != null) {
            // 密码在对话框收集——见 vltExportFor 处理链
            pendingExportUri = uri
        }
    }

    val plainExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val f = plainExportFor
        plainExportFor = null
        if (uri != null && f != null) {
            context.contentResolver.openOutputStream(uri)?.let { vm.exportPlain(f.storedName, it) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("临时空间") },
                navigationIcon = {
                    IconButton(onClick = { exitConfirm = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "导入")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 即焚提示条
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "退出或重启后内容消失 · 点 + 导入文件或 .vlt 密文",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            busy?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp))
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (files.isEmpty()) {
                EmptyState("临时空间是空的", "导入文件或 .vlt 便携密文开始", Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(files, key = { it.storedName }) { f ->
                        SessionFileRow(
                            file = f,
                            onClick = {
                                when (f.kind) {
                                    MediaKind.IMAGE, MediaKind.VIDEO, MediaKind.AUDIO -> onPreview(f.storedName)
                                    else -> menuFor = f
                                }
                            },
                            onMenu = { menuFor = f },
                        )
                    }
                }
            }
        }
    }

    // ---------- 条目菜单 ----------
    menuFor?.let { f ->
        DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
            if (f.kind == MediaKind.IMAGE || f.kind == MediaKind.VIDEO || f.kind == MediaKind.AUDIO) {
                DropdownMenuItem(text = { Text("预览") }, onClick = { onPreview(f.storedName); menuFor = null })
            }
            DropdownMenuItem(text = { Text("重命名") }, onClick = { renameFor = f; menuFor = null })
            DropdownMenuItem(
                text = { Text("导出为 .vlt 密文") },
                onClick = { vltExportFor = f; menuFor = null },
            )
            DropdownMenuItem(
                text = { Text("导出明文") },
                onClick = { plainExportFor = f; menuFor = null },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { deleteFor = f; menuFor = null },
            )
        }
    }

    // ---------- .vlt 导入密码 ----------
    vltPasswordFor?.let { uri ->
        var pw by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { vltPasswordFor = null },
            title = { Text("打开 .vlt") },
            text = {
                Column(Modifier.imePadding()) {
                    Text("输入该文件的密码")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; err = null }, "密码", isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.importVlt(container.safTransfer.fileSource(uri), pw.toCharArray()) { ok ->
                        if (ok) vltPasswordFor = null else err = "密码错误或文件损坏"
                    }
                }) { Text("打开") }
            },
            dismissButton = { TextButton(onClick = { vltPasswordFor = null }) { Text("取消") } },
        )
    }

    // ---------- .vlt 导出(密码 → 文件选择器) ----------
    vltExportFor?.let { f ->
        var pw by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { vltExportFor = null },
            title = { Text("导出为 .vlt") },
            text = {
                Column(Modifier.imePadding()) {
                    Text("设一个一次性密码;接收方用它解密,无需此应用账号")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; err = null }, "密码", isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pw.isEmpty()) {
                        err = "密码不能为空"
                    } else {
                        vltExportLauncher.launch("${f.storedName}.vlt")
                    }
                }) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = { vltExportFor = null }) { Text("取消") } },
        )
        // 文件位置选好后再加密导出
        pendingExportUri?.let { uri ->
            androidx.compose.runtime.LaunchedEffect(uri) {
                context.contentResolver.openOutputStream(uri)?.let { out ->
                    vm.exportVlt(f.storedName, pw.toCharArray(), out) {}
                }
                vltExportFor = null
                pendingExportUri = null
            }
        }
    }

    // ---------- 明文导出 ----------
    plainExportFor?.let { f ->
        androidx.compose.runtime.LaunchedEffect(f) {
            plainExportLauncher.launch(f.storedName)
        }
    }

    // ---------- 重命名 ----------
    renameFor?.let { f ->
        var name by remember { mutableStateOf(f.storedName) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { vm.rename(f.storedName, name); renameFor = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } },
        )
    }

    // ---------- 删除确认 ----------
    deleteFor?.let { f ->
        ConfirmDialog(
            title = "删除「${f.displayName}」?",
            text = "临时文件立即销毁。",
            confirmText = "删除",
            onConfirm = { vm.delete(f.storedName); deleteFor = null },
            onDismiss = { deleteFor = null },
        )
    }

    // ---------- 退出确认 ----------
    if (exitConfirm) {
        ConfirmDialog(
            title = "退出临时空间?",
            text = "空间内 ${files.size} 个文件将永久销毁。",
            confirmText = "销毁并退出",
            danger = true,
            onConfirm = {
                vm.destroySession()
                exitConfirm = false
                onExit()
            },
            onDismiss = { exitConfirm = false },
        )
    }
}

@Composable
private fun SessionFileRow(file: SessionFile, onClick: () -> Unit, onMenu: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onMenu),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (file.kind) {
                    MediaKind.IMAGE -> Icons.Filled.Image
                    MediaKind.VIDEO -> Icons.Filled.Movie
                    MediaKind.AUDIO -> Icons.Filled.AudioFile
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    formatBytes(file.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, null)
            }
        }
    }
}
