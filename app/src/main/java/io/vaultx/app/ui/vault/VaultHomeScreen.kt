package io.vaultx.app.ui.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.PlayCircle
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultImageRef
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.VaultEntry
import io.vaultx.app.ui.components.EmptyState
import io.vaultx.app.ui.components.PasswordField
import io.vaultx.app.ui.components.TransferProgressBar
import io.vaultx.app.ui.components.TEXT_PREVIEW_MAX_BYTES
import io.vaultx.app.ui.components.decodePreviewText
import io.vaultx.app.ui.components.formatBytes
import io.vaultx.app.ui.components.isTextFileName
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
    // 长按进选择态给触感反馈——没有它用户不确定手势是否生效
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val longPressHaptic = {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
    }
    val vm: VaultViewModel = viewModel(key = "vault-$vaultId") { VaultViewModel(container, vaultId) }

    val index by vm.index.collectAsState()
    val folderStack by vm.folderStack.collectAsState()
    val selection by vm.selection.collectAsState()
    val query by vm.query.collectAsState()
    val sortBy by vm.sortBy.collectAsState()
    val sortReversed by vm.sortReversed.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    val transfer by vm.transfer.collectAsState()
    val error by vm.error.collectAsState()
    val notice by vm.notice.collectAsState()
    val undo by vm.undoDelete.collectAsState()

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
    var infoTarget by remember { mutableStateOf<VaultEntry?>(null) }
    // .vlt 加密导出:先收密码 → 选保存位置 → 流式写出
    var vltExportFor by remember { mutableStateOf<VaultEntry?>(null) }
    var vltPassword by remember { mutableStateOf<String?>(null) }
    // "导入到此处"的目标文件夹(文件夹溢出菜单触发),null = 当前目录
    var importTarget by remember { mutableStateOf<String?>(null) }

    // 拖拽移动状态:选中态下拖卡片到文件夹格子上
    var dragEntry by remember { mutableStateOf<VaultEntry?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(IntSize.Zero) }
    var dropTargetId by remember { mutableStateOf<String?>(null) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val folderBounds = remember { mutableStateMapOf<String, Rect>() }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var gridBounds by remember { mutableStateOf<Rect?>(null) }
    // 不能落在被拖条目自身/子孙上
    val dragForbidden = remember(selection, index) {
        if (index == null) selection
        else selection + selection.flatMap { index!!.descendantIds(it) }
    }

    // 排序结果只在输入变化时重算——拖拽/进度等高频重组不能每帧重排全表
    val entries = remember(index, folderStack, query, sortBy, sortReversed) { vm.visibleEntries() }
    // 从库设置返回(改名等)时重读 meta
    var metaTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val meta = remember(index, metaTick) { runCatching { container.vaultManager.metaOf(vaultId) }.getOrNull() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) metaTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 文件夹格子要显示子项数:一次聚合 parentId→count,避免每格全表扫
    val folderCounts = remember(index) {
        index?.entries?.groupingBy { it.parentId }?.eachCount() ?: emptyMap()
    }

    // SAF 启动器
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val target = importTarget
        importTarget = null
        if (uris.isNotEmpty()) {
            vm.import(uris.map { container.safTransfer.fileSource(it) }, intoFolderId = target)
        }
    }
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = importTarget
        importTarget = null
        if (uri != null) vm.import(listOf(container.safTransfer.treeSource(uri)), intoFolderId = target)
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
    // 单文件走"另存为":系统保存框直接带文件名,比先选目录更顺手
    val exportFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val ids = pendingExport
        pendingExport = null
        if (uri != null && ids != null) {
            vm.exportEntries(ids, container.safTransfer.documentSinkFactory(uri))
        }
    }
    // .vlt 加密导出位置
    val vltLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = vltExportFor
        val pw = vltPassword
        vltExportFor = null
        vltPassword = null
        if (uri != null && entry != null && pw != null) {
            context.contentResolver.openOutputStream(uri)?.let { out ->
                vm.exportAsVlt(entry, pw.toCharArray(), out)
            }
        }
    }

    /** 导出入口:恰好一个文件 → CreateDocument;多选/含文件夹 → 选目录。 */
    fun launchExport(ids: Set<String>) {
        pendingExport = ids
        val single = ids.singleOrNull()?.let { index?.find(it) }
        if (single != null && !single.isFolder) {
            exportFileLauncher.launch(single.name)
        } else {
            exportLauncher.launch(null)
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
    // 选择集中途清空(返回键/锁定)时丢弃悬空的拖拽影子
    LaunchedEffect(selection.isEmpty()) {
        if (selection.isEmpty()) {
            dragEntry = null
            dropTargetId = null
            dragOffset = Offset.Zero
        }
    }

    // 返回优先级:清选择 → 关搜索 → 上一层目录 → 系统返回
    BackHandler(enabled = selection.isNotEmpty() || searching || folderStack.isNotEmpty()) {
        when {
            selection.isNotEmpty() -> vm.clearSelection()
            searching -> { searching = false; vm.setQuery("") }
            else -> vm.navigateUp()
        }
    }

    /** 单点行为统一走这里:选模式切选;文件夹进入;文本预览;OTHER 弹菜单;媒体开查看器。 */
    val onEntryTap: (VaultEntry) -> Unit = { entry ->
        when {
            selection.isNotEmpty() -> vm.toggleSelect(entry.id)
            entry.isFolder -> vm.openFolder(entry)
            isTextEntry(entry) -> textPreview = entry
            entry.kind == MediaKind.OTHER -> overflowFor = entry
            else -> onOpenEntry(entry)
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
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    } else {
                        Column {
                            Text(meta?.name ?: "保险库", style = MaterialTheme.typography.titleMedium)
                            if (folderStack.isNotEmpty()) {
                                // 可点面包屑:任意段直接跳回;深路径横向可滚不被裁
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                ) {
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
                    IconButton(onClick = { vm.setViewMode(if (viewMode == "GRID") "LIST" else "GRID") }) {
                        Icon(
                            if (viewMode == "GRID") Icons.AutoMirrored.Filled.List else Icons.Filled.GridView,
                            contentDescription = if (viewMode == "GRID") "列表视图" else "网格视图",
                        )
                    }
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                // 当前档显示方向箭头(再点翻转),其他档显示勾位
                                text = { Text(s.label) },
                                onClick = { vm.setSortBy(s); sortMenu = false },
                                trailingIcon = {
                                    if (s == sortBy) Icon(
                                        if (sortReversed) Icons.Filled.ArrowUpward
                                        else Icons.Filled.ArrowDownward,
                                        contentDescription = "再点翻转方向",
                                    )
                                },
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { overlayOrigin = it.boundsInWindow().topLeft },
        ) {
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
                // 删除撤销条:窗口内 blob 仍在,一键整批恢复
                undo?.let { pd ->
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已删除 ${pd.entries.size} 项",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.undoDelete() }) {
                                Text("撤销", color = MaterialTheme.colorScheme.inversePrimary)
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    // 传输进行中不闪"空目录"——进度条已占位,导入完自然填充
                    if (transfer == null) {
                        EmptyState(
                            title = if (query.isNotBlank()) "没有匹配的文件" else "这里是空的",
                            subtitle = if (query.isNotBlank()) "换个关键词试试" else "点右下角 + 导入文件或文件夹",
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                } else if (viewMode == "LIST") {
                    LazyColumn(Modifier.weight(1f)) {
                        items(entries, key = { it.id }) { entry ->
                            EntryListRow(
                                entry = entry,
                                vaultId = vaultId,
                                childCount = if (entry.isFolder) folderCounts[entry.id] ?: 0 else null,
                                subtitle = if (query.isNotBlank()) "位于:${parentPathOf(entry, index)}" else null,
                                selected = entry.id in selection,
                                selectionMode = selection.isNotEmpty(),
                                onNeedThumb = { vm.ensureThumb(entry) },
                                onClick = { onEntryTap(entry) },
                                onLongClick = { longPressHaptic(); vm.toggleSelect(entry.id) },
                                onOverflow = { overflowFor = entry },
                            )
                        }
                    }
                } else {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    // 拖拽悬到网格上下边缘时自动滚动——目标文件夹在屏外也能拖到;
                    // 滚动后格子 bounds 经 onGloballyPositioned 刷新,落点判定每帧重算
                    LaunchedEffect(dragEntry != null) {
                        if (dragEntry == null) return@LaunchedEffect
                        while (true) {
                            val b = gridBounds
                            val tipY = dragOrigin.y + dragOffset.y + dragSize.height / 2f
                            if (b != null) {
                                val edge = with(density) { 64.dp.toPx() }
                                val step = with(density) { 14.dp.toPx() }
                                when {
                                    tipY < b.top + edge -> gridState.dispatchRawDelta(-step)
                                    tipY > b.bottom - edge -> gridState.dispatchRawDelta(step)
                                }
                                val tip = dragOrigin + dragOffset + Offset(dragSize.width / 2f, dragSize.height / 2f)
                                dropTargetId = folderBounds.entries
                                    .firstOrNull { (id, r) -> id !in dragForbidden && r.contains(tip) }
                                    ?.key
                            }
                            kotlinx.coroutines.delay(16)
                        }
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.weight(1f).onGloballyPositioned { gridBounds = it.boundsInWindow() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(entries, key = { it.id }) { entry ->
                            EntryCell(
                                entry = entry,
                                vaultId = vaultId,
                                childCount = if (entry.isFolder) folderCounts[entry.id] ?: 0 else null,
                                subtitle = if (query.isNotBlank()) "位于:${parentPathOf(entry, index)}" else null,
                                selected = entry.id in selection,
                                selectionMode = selection.isNotEmpty(),
                                draggable = selection.isNotEmpty() && entry.id in selection,
                                dropHighlighted = entry.id == dropTargetId,
                                onBoundsChanged = if (entry.isFolder) {
                                    { r -> if (r == null) folderBounds.remove(entry.id) else folderBounds[entry.id] = r }
                                } else {
                                    null
                                },
                                onDragStart = { origin, size ->
                                    dragEntry = entry
                                    dragOrigin = origin
                                    dragSize = size
                                    dragOffset = Offset.Zero
                                    dropTargetId = null
                                },
                                onDrag = { amt ->
                                    dragOffset += amt
                                    val tip = dragOrigin + dragOffset + Offset(dragSize.width / 2f, dragSize.height / 2f)
                                    dropTargetId = folderBounds.entries
                                        .firstOrNull { (id, r) -> id !in dragForbidden && r.contains(tip) }
                                        ?.key
                                },
                                onDragEnd = {
                                    val target = dropTargetId
                                    if (target != null) vm.moveEntries(selection, target)
                                    dragEntry = null
                                    dropTargetId = null
                                    dragOffset = Offset.Zero
                                },
                                onNeedThumb = { vm.ensureThumb(entry) },
                                onClick = { onEntryTap(entry) },
                                onLongClick = { longPressHaptic(); vm.toggleSelect(entry.id) },
                                onOverflow = { overflowFor = entry },
                            )
                        }
                    }
                }
            }

            // 拖拽移动的影子卡片(跟随手指;落在文件夹上时该格高亮)
            dragEntry?.let { de ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.offset {
                        IntOffset(
                            (dragOrigin.x - overlayOrigin.x + dragOffset.x).roundToInt(),
                            (dragOrigin.y - overlayOrigin.y + dragOffset.y).roundToInt(),
                        )
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(iconFor(de.kind), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (selection.size > 1) "${de.name} 等 ${selection.size} 项" else de.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
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
                    IconButton(onClick = { launchExport(selection) }) {
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
            // 文件/文件夹都可副本——文件夹走 VM 的递归复制(子孙 blob 逐个重加密)
            if (entry.isFolder || entry.blobId != null) {
                DropdownMenuItem(
                    text = { Text("创建副本") },
                    onClick = { vm.duplicateEntry(entry.id); overflowFor = null },
                )
            }
            DropdownMenuItem(
                text = { Text("导出") },
                onClick = {
                    launchExport(setOf(entry.id))
                    overflowFor = null
                },
            )
            if (!entry.isFolder && entry.blobId != null) {
                DropdownMenuItem(
                    text = { Text("加密导出(.vlt)") },
                    onClick = {
                        vltExportFor = entry
                        overflowFor = null
                    },
                )
            }
            if (entry.isFolder) {
                DropdownMenuItem(
                    text = { Text("导入文件到此处…") },
                    onClick = {
                        importTarget = entry.id
                        overflowFor = null
                        importFilesLauncher.launch(arrayOf("*/*"))
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
                text = { Text("属性") },
                onClick = { infoTarget = entry; overflowFor = null },
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
        // 全部选中项同一父目录时标出"当前位置",避免无意义点选
        val currentParent = remember(selection, idx) {
            idx?.let { i -> selection.mapNotNull { i.find(it)?.parentId }.distinct().singleOrNull() }
        }
        AlertDialog(
            onDismissRequest = { moveDialog = false },
            title = { Text("移动到") },
            text = {
                Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                    MoveTargetRow(
                        if (currentParent == null) "根目录(当前)" else "根目录",
                        onClick = { vm.moveEntries(selection, null); moveDialog = false },
                    )
                    folders.forEach { (f, path) ->
                        MoveTargetRow(
                            if (f.id == currentParent) "$path(当前)" else path,
                            onClick = { vm.moveEntries(selection, f.id); moveDialog = false },
                        )
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
                        // 索引里的 sizeBytes 只作 UI 参考——流可能更大,截到上限防内存膨胀
                        decodePreviewText(it.readNBytes(TEXT_PREVIEW_MAX_BYTES.toInt() + 1))
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

    // ---------- 条目属性 ----------
    infoTarget?.let { entry ->
        val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }
        AlertDialog(
            onDismissRequest = { infoTarget = null },
            title = { Text(entry.name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    InfoRow("类型", when (entry.kind) {
                        MediaKind.IMAGE -> "图片"; MediaKind.VIDEO -> "视频"
                        MediaKind.AUDIO -> "音频"; MediaKind.FOLDER -> "文件夹"
                        MediaKind.OTHER -> "文件"
                    })
                    if (entry.isFolder) {
                        // 直接子项数 + 递归子孙数与总大小——"这个文件夹占多少空间"是常见诉求
                        val desc = remember(entry.id, index) {
                            index?.let { i ->
                                i.descendantIds(entry.id).mapNotNull { i.find(it) }
                            } ?: emptyList()
                        }
                        InfoRow("包含", "${folderCounts[entry.id] ?: 0} 项(共 ${desc.size} 个子孙条目)")
                        InfoRow("总大小", formatBytes(desc.sumOf { it.sizeBytes }))
                    } else {
                        InfoRow("大小", formatBytes(entry.sizeBytes))
                    }
                    entry.mimeType?.let { InfoRow("MIME", it) }
                    InfoRow("位置", parentPathOf(entry, index))
                    InfoRow("创建", fmt.format(java.util.Date(entry.createdAt)))
                    InfoRow("修改", fmt.format(java.util.Date(entry.updatedAt)))
                }
            },
            confirmButton = { TextButton(onClick = { infoTarget = null }) { Text("关闭") } },
        )
    }

    // ---------- .vlt 加密导出(独立密码) ----------
    vltExportFor?.let { entry ->
        if (vltPassword == null) {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var err by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { vltExportFor = null },
                title = { Text("加密导出「${entry.name}」") },
                text = {
                    Column(Modifier.imePadding()) {
                        Text("为该 .vlt 文件设一个独立密码(与库密码无关);持有该密码的人可在无锁模式下解开。")
                        Spacer(Modifier.height(8.dp))
                        PasswordField(pw, { pw = it; err = null }, "导出密码")
                        Spacer(Modifier.height(4.dp))
                        PasswordField(pw2, { pw2 = it; err = null }, "确认密码", isError = err != null, supportingText = err)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            pw.length < 4 -> err = "至少 4 位"
                            pw != pw2 -> err = "两次输入不一致"
                            else -> {
                                vltPassword = pw
                                vltLauncher.launch("${entry.name}.vlt")
                            }
                        }
                    }) { Text("选择保存位置") }
                },
                dismissButton = { TextButton(onClick = { vltExportFor = null }) { Text("取消") } },
            )
        }
    }

    // ---------- 删除确认 ----------
    if (deleteConfirm) {
        // 级联:文件夹连子孙一起删,确认框如实显示总数(去重:选了文件夹又选其子项只计一次)
        val doomedCount = remember(selection, index) {
            selection.flatMap { id -> (index?.descendantIds(id) ?: emptySet()) + id }.toSet().size
        }
        io.vaultx.app.ui.components.ConfirmDialog(
            title = "删除 $doomedCount 项?",
            text = if (doomedCount > selection.size) "文件夹内的全部内容会一并删除。删除后有 8 秒撤销窗口。"
            else "删除后有 8 秒撤销窗口,可在底部条中恢复。",
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
    val seen = hashSetOf(entry.id)
    var cur = entry.parentId
    while (cur != null && cur !in seen) {
        val p = index?.find(cur) ?: break
        seen.add(p.id)
        parts.add(0, p.name)
        cur = p.parentId
    }
    return parts.joinToString(" / ")
}

/** 条目所在目录的路径标签(属性对话框用):"根目录 / a / b"。 */
private fun parentPathOf(
    entry: VaultEntry,
    index: io.vaultx.app.core.vault.VaultIndex?,
): String {
    val parts = mutableListOf<String>()
    val seen = hashSetOf(entry.id)
    var cur = entry.parentId
    while (cur != null && cur !in seen) {
        val p = index?.find(cur) ?: break
        seen.add(p.id)
        parts.add(0, p.name)
        cur = p.parentId
    }
    return if (parts.isEmpty()) "根目录" else parts.joinToString(" / ")
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** 列表视图的行条目(与网格格子共享点击语义)。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryListRow(
    entry: VaultEntry,
    vaultId: String,
    childCount: Int?,
    subtitle: String? = null,
    selected: Boolean,
    selectionMode: Boolean,
    onNeedThumb: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    // 列表模式也要懒生成缩略图(VM 内 in-flight 去重,重复调用安全)
    androidx.compose.runtime.LaunchedEffect(entry.id) {
        if (!entry.isFolder && !entry.hasThumb &&
            (entry.kind == MediaKind.IMAGE || entry.kind == MediaKind.VIDEO || entry.kind == MediaKind.AUDIO)
        ) {
            onNeedThumb()
        }
    }
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.hasThumb && entry.blobId != null && !entry.isFolder) {
                AsyncImage(
                    model = VaultImageRef(vaultId, entry.blobId, preferThumb = true),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    iconFor(entry.kind), null, Modifier.size(28.dp),
                    tint = if (entry.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    subtitle ?: if (entry.isFolder) "${childCount ?: 0} 项" else formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            } else if (!selectionMode) {
                IconButton(onClick = onOverflow, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, null, Modifier.size(18.dp))
                }
            }
        }
    }
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
    childCount: Int?,
    subtitle: String? = null,
    selected: Boolean,
    selectionMode: Boolean,
    draggable: Boolean,
    dropHighlighted: Boolean,
    onBoundsChanged: ((Rect?) -> Unit)?,
    onDragStart: (origin: Offset, size: IntSize) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onNeedThumb: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var cellWindow by remember { mutableStateOf(Rect.Zero) }
    // 媒体类条目懒生成加密缩略图(一次即可,hasThumb 落索引)
    androidx.compose.runtime.LaunchedEffect(entry.id) {
        if (!entry.isFolder && !entry.hasThumb &&
            (entry.kind == MediaKind.IMAGE || entry.kind == MediaKind.VIDEO || entry.kind == MediaKind.AUDIO)
        ) {
            onNeedThumb()
        }
    }
    // 文件夹格子向父级上报自己的窗口矩形,滚出屏幕时注销(防幽灵落点)
    androidx.compose.runtime.DisposableEffect(entry.id) {
        onDispose { onBoundsChanged?.invoke(null) }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.82f)
            .onGloballyPositioned {
                cellWindow = it.boundsInWindow()
                onBoundsChanged?.invoke(cellWindow)
            }
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // 选中态下可拖:拖到某个文件夹格子上松手即移动(长按仍是点选,不冲突)
            .pointerInput(draggable) {
                if (!draggable) return@pointerInput
                detectDragGestures(
                    onDragStart = { onDragStart(cellWindow.topLeft, IntSize(size.width, size.height)) },
                    onDrag = { change, amt -> change.consume(); onDrag(amt) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .border(
                width = if (dropHighlighted) 2.dp else 0.dp,
                color = if (dropHighlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = MaterialTheme.shapes.medium,
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                dropHighlighted -> MaterialTheme.colorScheme.tertiaryContainer
                selected -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
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
                        entry.hasThumb && entry.blobId != null -> {
                            // 图/视频/音频共用加密缩略图;音视频叠个播放标示意可播
                            AsyncImage(
                                model = VaultImageRef(vaultId, entry.blobId, preferThumb = true),
                                contentDescription = entry.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                            if (entry.kind == MediaKind.VIDEO || entry.kind == MediaKind.AUDIO) {
                                Icon(
                                    Icons.Filled.PlayCircle, null,
                                    Modifier.align(Alignment.Center).size(28.dp),
                                    tint = Color.White.copy(alpha = 0.85f),
                                )
                            }
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
                Text(
                    subtitle ?: if (entry.isFolder) "${childCount ?: 0} 项" else formatBytes(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
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

/** 可内联预览的文本类条目:OTHER 类型 + 文本扩展名 + ≤512KB(防大文件卡 UI)。 */
private fun isTextEntry(e: VaultEntry): Boolean =
    !e.isFolder && e.kind == MediaKind.OTHER && isTextFileName(e.name, e.sizeBytes)
