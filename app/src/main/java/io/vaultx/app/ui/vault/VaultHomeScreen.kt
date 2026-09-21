package io.vaultx.app.ui.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultImageRef
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.VaultEntry
import io.vaultx.app.ui.components.EmptyState
import io.vaultx.app.ui.components.TransferProgressBar
import io.vaultx.app.ui.components.formatBytes
import io.vaultx.app.ui.components.pressScale

/**
 * 库内文件浏览:网格 + 面包屑 + 搜索/排序 + 多选操作条 + 导入菜单。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VaultHomeScreen(
    container: AppContainer,
    vaultId: String,
    onOpenEntry: (VaultEntry) -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val vm: VaultViewModel = viewModel(key = "vault-$vaultId") { VaultViewModel(container, vaultId) }

    val index by vm.index.collectAsState()
    val folderStack by vm.folderStack.collectAsState()
    val selection by vm.selection.collectAsState()
    val query by vm.query.collectAsState()
    val sortBy by vm.sortBy.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val error by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()

    val searchFocus = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var overflowFor by remember { mutableStateOf<VaultEntry?>(null) }
    var renameTarget by remember { mutableStateOf<VaultEntry?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var moveDialog by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<Set<String>?>(null) }
    var textPreview by remember { mutableStateOf<VaultEntry?>(null) }

    val entries = vm.visibleEntries()
    val meta = remember(index) { runCatching { container.vaultManager.metaOf(vaultId) }.getOrNull() }

    // SAF 启动器
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.import(uris.map { container.safTransfer.fileSource(it) })
        }
    }
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) vm.import(listOf(container.safTransfer.treeSource(uri)))
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val ids = pendingExport
        pendingExport = null
        if (uri != null && ids != null) {
            vm.exportEntries(ids, container.safTransfer.exportSinkFactory(uri))
        }
    }

    // 提示条自动消退
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            vm.clearNotice()
        }
    }
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }

    BackHandler(enabled = folderStack.isNotEmpty() || selection.isNotEmpty()) {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            else -> vm.navigateUp()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (folderStack.isNotEmpty()) {
                        IconButton(onClick = { vm.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一层")
                        }
                    }
                },
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.setQuery(it) },
                            placeholder = { Text("搜索文件名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    } else {
                        Column {
                            Text(meta?.name ?: "保险库", style = MaterialTheme.typography.titleMedium)
                            if (folderStack.isNotEmpty()) {
                                // 可点面包屑:任意段直接跳回
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "根目录",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { vm.jumpToBreadcrumb(-1) },
                                    )
                                    folderStack.forEachIndexed { i, f ->
                                        Text(
                                            " / ${f.name}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (i == folderStack.lastIndex) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.clickable { vm.jumpToBreadcrumb(i) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.setQuery("")
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = { vm.setSortBy(s); sortMenu = false },
                                trailingIcon = { if (s == sortBy) Icon(Icons.Filled.Check, null) },
                            )
                        }
                    }
                    IconButton(onClick = { vm.lockNow() }) {
                        Icon(Icons.Filled.Lock, contentDescription = "立即锁定")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "库设置")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { importMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "导入")
                }
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("导入文件") },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                        onClick = {
                            importMenu = false
                            importFilesLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("导入文件夹") },
                        leadingIcon = { Icon(Icons.Filled.Folder, null) },
                        onClick = {
                            importMenu = false
                            importFolderLauncher.launch(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("新建文件夹") },
                        leadingIcon = { Icon(Icons.Filled.CreateNewFolder, null) },
                        onClick = { importMenu = false; newFolderDialog = true },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                transfer?.let { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransferProgressBar(t.done, t.total, t.label, Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelTransfer() }) { Text("取消") }
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
                notice?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                if (entries.isEmpty()) {
                    EmptyState(
                        title = if (query.isNotBlank()) "没有匹配的文件" else "这里是空的",
                        subtitle = if (query.isNotBlank()) "换个关键词试试" else "点右下角 + 导入文件或文件夹",
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            EntryCell(
                                entry = entry,
                                vaultId = vaultId,
                                selected = entry.id in selection,
                                selectionMode = selection.isNotEmpty(),
                                onNeedThumb = { vm.ensureThumb(entry) },
                                onClick = {
                                    if (selection.isNotEmpty()) {
                                        vm.toggleSelect(entry.id)
                                    } else if (entry.isFolder) {
                                        vm.openFolder(entry)
                                    } else if (isTextEntry(entry)) {
                                        textPreview = entry
                                    } else if (entry.kind == MediaKind.OTHER) {
                                        // 无内建预览的类型:直接给操作菜单而不是死路
                                        overflowFor = entry
                                    } else {
                                        onOpenEntry(entry)
                                    }
                                },
                                onLongClick = { vm.toggleSelect(entry.id) },
                                onOverflow = { overflowFor = entry },
                            )
                        }
                    }
                }
            }

            // 多选浮动工具条
            if (selection.isNotEmpty()) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                ) {
                    Text(
                        "${selection.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    )
                    IconButton(onClick = { vm.selectAll() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = "全选")
                    }
                    IconButton(onClick = { moveDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "移动")
                    }
                    IconButton(onClick = {
                        pendingExport = selection
                        exportLauncher.launch(null)
                    }) {
                        Icon(Icons.Filled.Output, contentDescription = "导出")
                    }
                    IconButton(onClick = { deleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                    IconButton(onClick = { vm.clearSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = "取消选择")
                    }
                }
            }
        }
    }

    // ---------- 条目溢出菜单 ----------
    overflowFor?.let { entry ->
        DropdownMenu(expanded = true, onDismissRequest = { overflowFor = null }) {
            if (isTextEntry(entry)) {
                DropdownMenuItem(
                    text = { Text("预览") },
                    onClick = { textPreview = entry; overflowFor = null },
                )
            }
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = { renameTarget = entry; overflowFor = null },
            )
            if (!entry.isFolder) {
                DropdownMenuItem(
                    text = { Text("导出") },
                    onClick = {
                        pendingExport = setOf(entry.id)
                        exportLauncher.launch(null)
                        overflowFor = null
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("移动到…") },
                onClick = {
                    vm.clearSelection()
                    vm.toggleSelect(entry.id)
                    moveDialog = true
                    overflowFor = null
                },
            )
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    vm.clearSelection()
                    vm.toggleSelect(entry.id)
                    deleteConfirm = true
                    overflowFor = null
                },
            )
        }
    }

    // ---------- 重命名 ----------
    renameTarget?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { vm.renameEntry(entry.id, name); renameTarget = null }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    // ---------- 新建文件夹 ----------
    if (newFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { vm.newFolder(name); newFolderDialog = false }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text("取消") } },
        )
    }

    // ---------- 移动目标选择 ----------
    if (moveDialog) {
        val idx = index
        // 全路径标签:嵌套同名文件夹不再歧义;同时排除被选项的子孙(防环由 VM 兜底)
        val excluded = remember(selection, idx) {
            if (idx == null) selection
            else selection + selection.flatMap { idx.descendantIds(it) }
        }
        val folders = (idx?.entries ?: emptyList())
            .filter { it.isFolder && it.id !in excluded }
            .map { it to folderPathOf(it, idx) }
            .sortedBy { it.second }
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text("移动到") },
            text = {
                Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                    MoveTargetRow("根目录", onClick = { vm.moveEntries(selection, null); moveDialog = false })
                    folders.forEach { (f, path) ->
                        MoveTargetRow(path, onClick = { vm.moveEntries(selection, f.id); moveDialog = false })
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveDialog = false }) { Text("取消") } },
        )
    }

    // ---------- 文本预览 ----------
    textPreview?.let { entry ->
        var content by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(entry.id) {
            content = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val u = container.session.get(vaultId) ?: return@runCatching null
                    container.vaultManager.openBlobStream(u, entry.blobId!!).use {
                        String(it.readBytes(), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
        }
        AlertDialog(
            onDismissRequest = { textPreview = null },
            title = { Text(entry.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                if (content == null) {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            content!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { textPreview = null }) { Text("关闭") } },
        )
    }

    // ---------- 删除确认 ----------
    if (deleteConfirm) {
        io.vaultx.app.ui.components.ConfirmDialog(
            title = "删除 ${selection.size} 项?",
            text = "选中的文件/文件夹将从加密库中永久删除。",
            confirmText = "删除",
            danger = true,
            onConfirm = { vm.deleteEntries(selection); deleteConfirm = false },
            onDismiss = { deleteConfirm = false },
        )
    }
}

/** 文件夹的全路径标签(移动对话框用):"a / b / c"。 */
private fun folderPathOf(
    entry: VaultEntry,
    index: io.vaultx.app.core.vault.VaultIndex?,
): String {
    val parts = mutableListOf(entry.name)
    var cur = entry.parentId
    while (cur != null) {
        val p = index?.find(cur) ?: break
        parts.add(0, p.name)
        cur = p.parentId
    }
    return parts.joinToString(" / ")
}

@Composable
private fun MoveTargetRow(label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCell(
    entry: VaultEntry,
    vaultId: String,
    selected: Boolean,
    selectionMode: Boolean,
    onNeedThumb: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // 媒体类条目懒生成加密缩略图(一次即可,hasThumb 落索引)
    androidx.compose.runtime.LaunchedEffect(entry.id) {
        if (!entry.isFolder && !entry.hasThumb &&
            (entry.kind == MediaKind.IMAGE || entry.kind == MediaKind.VIDEO || entry.kind == MediaKind.AUDIO)
        ) {
            onNeedThumb()
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        entry.isFolder -> Icon(
                            Icons.Filled.Folder, null, Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        entry.kind == MediaKind.IMAGE && entry.hasThumb -> {
                            AsyncImage(
                                model = VaultImageRef(vaultId, entry.blobId!!, preferThumb = true),
                                contentDescription = entry.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        else -> Icon(
                            iconFor(entry.kind), null, Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.name,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                )
                if (!entry.isFolder) {
                    Text(
                        formatBytes(entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check, null,
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else if (!selectionMode) {
                IconButton(
                    onClick = onOverflow,
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                ) {
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun iconFor(kind: MediaKind) = when (kind) {
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.AUDIO -> Icons.Filled.AudioFile
    MediaKind.FOLDER -> Icons.Filled.Folder
    MediaKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private val TEXT_EXTS = setOf(
    "txt", "md", "log", "json", "xml", "csv", "yaml", "yml", "ini", "conf",
    "html", "htm", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh",
)

/** 可内联预览的文本类条目:OTHER 类型 + 文本扩展名 + ≤512KB(防大文件卡 UI)。 */
private fun isTextEntry(e: VaultEntry): Boolean =
    !e.isFolder && e.kind == MediaKind.OTHER &&
        e.name.substringAfterLast('.', "").lowercase() in TEXT_EXTS &&
        e.sizeBytes in 0..(512 * 1024)
