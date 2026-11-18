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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Output
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.core.media.VaultImageRef
import io.vaultx.app.core.transfer.TransferEngine
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
    val backupDue by vm.backupDue.collectAsState()
    // 本次会话内忽略备份提醒(不持久化:下次进库仍会温和提示)
    var backupDismissed by remember { mutableStateOf(false) }
    // 回收站视图开关(同 VM 内切换,不走导航栈)
    var showTrash by remember { mutableStateOf(false) }
    val starredOnly by vm.starredOnly.collectAsState()
    val tagFilter by vm.tagFilter.collectAsState()
    val allTags = remember(index) { index?.allTags() ?: emptyList() }
    var tagMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var tagsFor by remember { mutableStateOf<VaultEntry?>(null) }
    // 横幅退场动画期间保留最后文案(直接读 error/notice 会先变空再收缩)
    var lastErrorText by remember { mutableStateOf("") }
    var lastNoticeText by remember { mutableStateOf("") }
    error?.let { lastErrorText = it }
    notice?.let { lastNoticeText = it }

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
    // 导入遇到 .vlt 便携密文:排队逐个收密码解密入库(记住目标文件夹)
    // 队列在 VM 里(组合 remember 会在 Activity 重建时清零,剩余 .vlt 静默丢失)
    val vltImportQueue by vm.vltQueue.collectAsState()
    val vltImportTarget by vm.vltTarget.collectAsState()

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

    // 过滤+排序在 VM 的 Default 线程合并流里完成(搜索词已防抖),
    // 组合层只收集结果——拖拽/进度等高频重组不再触发全表重排
    val entries by vm.visible.collectAsState()
    // 从库设置返回(改名等)时重读 meta;读盘走 IO,不占组合线程
    var metaTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val meta by androidx.compose.runtime.produceState<io.vaultx.app.core.vault.VaultMeta?>(
        initialValue = null, index, metaTick,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { container.vaultManager.metaOf(vaultId) }.getOrNull()
        }
    }
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
        index?.entries?.filter { !it.isTrashed }?.groupingBy { it.parentId }?.eachCount() ?: emptyMap()
    }

    // SAF 启动器
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val target = importTarget
        importTarget = null
        if (uris.isNotEmpty()) {
            // .vlt 便携密文分出来逐个收密码解密入库;其他走普通导入
            val sources = uris.map { container.safTransfer.fileSource(it) }
            val (vlt, plain) = sources.partition { it.name.endsWith(".vlt", ignoreCase = true) }
            if (plain.isNotEmpty()) vm.import(plain, intoFolderId = target)
            if (vlt.isNotEmpty()) vm.enqueueVlt(vlt, target)
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

    // 返回优先级:退回收站 → 清选择 → 关搜索 → 退收藏/标签筛选 → 上一层目录 → 系统返回
    BackHandler(
        enabled = showTrash || selection.isNotEmpty() || searching ||
            starredOnly || tagFilter != null || folderStack.isNotEmpty(),
    ) {
        when {
            showTrash -> showTrash = false
            selection.isNotEmpty() -> vm.clearSelection()
            searching -> { searching = false; vm.setQuery("") }
            starredOnly -> vm.setStarredOnly(false)
            tagFilter != null -> vm.setTagFilter(null)
            else -> vm.navigateUp()
        }
    }

    // 回收站视图:同 VM 同索引,顶层被删条目列表;恢复/彻底删除后自动刷新
    if (showTrash) {
        TrashScreen(vm = vm, onBack = { showTrash = false })
        return
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.vhome_navigate_up))
                        }
                    }
                },
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { vm.setQuery(it) },
                            placeholder = { Text(stringResource(R.string.vhome_search_placeholder)) },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        )
                    } else {
                        Column {
                            Text(meta?.name ?: stringResource(R.string.vhome_default_title), style = MaterialTheme.typography.titleMedium)
                            if (folderStack.isNotEmpty()) {
                                // 可点面包屑:任意段直接跳回;深路径横向可滚不被裁
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                ) {
                                    Text(
                                        stringResource(R.string.vhome_root),
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
                    // 常驻三键:搜索 / 收藏 / 锁定;低频操作收进更多菜单——
                    // 8 个图标在窄屏会挤压标题甚至溢出,收纳后任何屏宽都从容
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) vm.setQuery("")
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = stringResource(R.string.vhome_search))
                    }
                    IconButton(onClick = {
                        if (!starredOnly) searching = false // 进收藏视图退出搜索态,搜索框不残留
                        vm.setStarredOnly(!starredOnly)
                    }) {
                        Icon(
                            if (starredOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(R.string.tags_starred_only),
                            tint = if (starredOnly) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { vm.lockNow() }) {
                        Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.vhome_lock_now))
                    }
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.vhome_more))
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (viewMode == "GRID") R.string.vhome_view_list else R.string.vhome_view_grid)) },
                            leadingIcon = {
                                Icon(if (viewMode == "GRID") Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, null)
                            },
                            onClick = { vm.setViewMode(if (viewMode == "GRID") "LIST" else "GRID"); moreMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vhome_sort)) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, null) },
                            onClick = { moreMenu = false; sortMenu = true },
                        )
                        // 标签筛选:库内有标签(或正在筛选)才出现
                        if (allTags.isNotEmpty() || tagFilter != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (tagFilter != null) stringResource(R.string.tags_filter_active, tagFilter!!)
                                        else stringResource(R.string.tags_open_filter),
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Label, null,
                                        tint = if (tagFilter != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                                    )
                                },
                                onClick = { moreMenu = false; searching = false; tagMenu = true },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.trash_open)) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                            onClick = { moreMenu = false; vm.clearSelection(); showTrash = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.vhome_vault_settings)) },
                            leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { moreMenu = false; onSettings() },
                        )
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortBy.entries.forEach { s ->
                            DropdownMenuItem(
                                // 当前档显示方向箭头(再点翻转),其他档显示勾位
                                text = { Text(stringResource(s.labelRes)) },
                                onClick = { vm.setSortBy(s); sortMenu = false },
                                trailingIcon = {
                                    if (s == sortBy) Icon(
                                        if (sortReversed) Icons.Filled.ArrowUpward
                                        else Icons.Filled.ArrowDownward,
                                        contentDescription = stringResource(R.string.vhome_sort_toggle_direction),
                                    )
                                },
                            )
                        }
                    }
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tags_filter_all)) },
                            onClick = { vm.setTagFilter(null); tagMenu = false },
                        )
                        allTags.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t) },
                                onClick = { vm.setTagFilter(t); tagMenu = false },
                                trailingIcon = {
                                    if (t == tagFilter) Icon(Icons.Filled.Check, null)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { importMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vhome_import))
                }
                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vhome_import_files)) },
                        leadingIcon = { Icon(Icons.Filled.UploadFile, null) },
                        onClick = {
                            importMenu = false
                            importFilesLauncher.launch(arrayOf("*/*"))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vhome_import_folder)) },
                        leadingIcon = { Icon(Icons.Filled.Folder, null) },
                        onClick = {
                            importMenu = false
                            importFolderLauncher.launch(null)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.vhome_new_folder)) },
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
                        TransferProgressBar(t.done, t.total, t.label, Modifier.weight(1f), t.fileBytes)
                        TextButton(onClick = { vm.cancelTransfer() }) { Text(stringResource(R.string.vhome_cancel)) }
                    }
                }
                // 备份提醒:超期未导出 .fvault 时温和提示(诱骗会话不显示,VM 已保证)
                if (backupDue && !backupDismissed) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.backup_due_banner, io.vaultx.app.core.state.AppSettings.BACKUP_REMINDER_DAYS.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { backupDismissed = true; onSettings() }) {
                                Text(stringResource(R.string.backup_due_action))
                            }
                            TextButton(onClick = { backupDismissed = true }) {
                                Text(stringResource(R.string.backup_due_dismiss))
                            }
                        }
                    }
                }
                // 错误横幅:可手动关闭(此前永久滞留挡内容);进出场动画
                androidx.compose.animation.AnimatedVisibility(
                    visible = error != null,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lastErrorText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            IconButton(onClick = { vm.clearError() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.vhome_dismiss_notice),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
                // 成功提示横幅:4 秒自动消退,也可手动关;进出场动画
                androidx.compose.animation.AnimatedVisibility(
                    visible = notice != null,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                lastNoticeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                            )
                            IconButton(onClick = { vm.clearNotice() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.vhome_dismiss_notice),
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
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
                                stringResource(R.string.vhome_deleted_count, pd.entries.size),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.undoDelete() }) {
                                Text(stringResource(R.string.vhome_undo), color = MaterialTheme.colorScheme.inversePrimary)
                            }
                        }
                    }
                }
                if (entries.isEmpty()) {
                    // 传输进行中不闪"空目录"——进度条已占位,导入完自然填充
                    if (transfer == null) {
                        val (emptyTitle, emptySubtitle) = when {
                            starredOnly -> R.string.tags_empty_starred_title to R.string.tags_empty_starred_subtitle
                            tagFilter != null -> R.string.tags_empty_tag_title to R.string.vhome_empty_no_match_subtitle
                            query.isNotBlank() -> R.string.vhome_empty_no_match_title to R.string.vhome_empty_no_match_subtitle
                            else -> R.string.vhome_empty_title to R.string.vhome_empty_subtitle
                        }
                        EmptyState(
                            title = stringResource(emptyTitle),
                            subtitle = stringResource(emptySubtitle),
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
                                subtitle = if (query.isNotBlank()) stringResource(R.string.vhome_located_in, parentPathOf(entry, index, stringResource(R.string.vhome_root))) else null,
                                selected = entry.id in selection,
                                selectionMode = selection.isNotEmpty(),
                                onNeedThumb = { vm.ensureThumb(entry) },
                                onClick = { onEntryTap(entry) },
                                onLongClick = { longPressHaptic(); vm.toggleSelect(entry.id) },
                                onOverflow = { overflowFor = entry },
                                modifier = Modifier.animateItem(),
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
                                subtitle = if (query.isNotBlank()) stringResource(R.string.vhome_located_in, parentPathOf(entry, index, stringResource(R.string.vhome_root))) else null,
                                selected = entry.id in selection,
                                selectionMode = selection.isNotEmpty(),
                                draggable = selection.isNotEmpty() && entry.id in selection,
                                dropHighlighted = entry.id == dropTargetId,
                                modifier = Modifier.animateItem(),
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
                                onDragAbort = {
                                    // 手势被系统取消/格子滚出视口:只清拖拽态,不执行移动
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
                            if (selection.size > 1) stringResource(R.string.vhome_drag_multiple, de.name, selection.size) else de.name,
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
                        Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.vhome_select_all))
                    }
                    IconButton(onClick = { moveDialog = true }) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.vhome_move))
                    }
                    IconButton(onClick = { launchExport(selection) }) {
                        Icon(Icons.Filled.Output, contentDescription = stringResource(R.string.vhome_export))
                    }
                    IconButton(onClick = { deleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.vhome_delete))
                    }
                    IconButton(onClick = { vm.clearSelection() }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.vhome_clear_selection))
                    }
                }
            }
        }
    }

    // ---------- 条目溢出菜单 ----------
    overflowFor?.let { entry ->
        DropdownMenu(expanded = true, onDismissRequest = { overflowFor = null }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (entry.starred) R.string.tags_unstar else R.string.tags_star)) },
                leadingIcon = {
                    Icon(
                        if (entry.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        null,
                        tint = if (entry.starred) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                },
                onClick = { vm.setStarred(setOf(entry.id), !entry.starred); overflowFor = null },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tags_manage)) },
                leadingIcon = { Icon(Icons.Filled.Label, null) },
                onClick = { tagsFor = entry; overflowFor = null },
            )
            if (isTextEntry(entry)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vhome_preview)) },
                    onClick = { textPreview = entry; overflowFor = null },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vhome_rename)) },
                onClick = { renameTarget = entry; overflowFor = null },
            )
            // 文件/文件夹都可副本——文件夹走 VM 的递归复制(子孙 blob 逐个重加密)
            if (entry.isFolder || entry.blobId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vhome_duplicate)) },
                    onClick = { vm.duplicateEntry(entry.id); overflowFor = null },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vhome_export)) },
                onClick = {
                    launchExport(setOf(entry.id))
                    overflowFor = null
                },
            )
            if (!entry.isFolder && entry.blobId != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vhome_encrypted_export)) },
                    onClick = {
                        vltExportFor = entry
                        overflowFor = null
                    },
                )
            }
            if (entry.isFolder) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vhome_import_files_here)) },
                    onClick = {
                        importTarget = entry.id
                        overflowFor = null
                        importFilesLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vhome_move_to_ellipsis)) },
                onClick = {
                    vm.clearSelection()
                    vm.toggleSelect(entry.id)
                    moveDialog = true
                    overflowFor = null
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vhome_properties)) },
                onClick = { infoTarget = entry; overflowFor = null },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vhome_delete), color = MaterialTheme.colorScheme.error) },
                onClick = {
                    vm.clearSelection()
                    vm.toggleSelect(entry.id)
                    deleteConfirm = true
                    overflowFor = null
                },
            )
        }
    }

    // ---------- 标签管理 ----------
    tagsFor?.let { target ->
        // 从最新索引取条目(加/删标签后即时刷新芯片)
        val entry = index?.find(target.id) ?: target
        var newTag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { tagsFor = null },
            title = { Text(stringResource(R.string.tags_dialog_title, entry.name)) },
            text = {
                Column {
                    if (entry.tags.isEmpty()) {
                        Text(
                            stringResource(R.string.tags_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            entry.tags.forEach { t ->
                                androidx.compose.material3.InputChip(
                                    selected = false,
                                    onClick = { vm.removeTag(entry.id, t) },
                                    label = { Text(t) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.tags_remove, t),
                                            modifier = Modifier.size(14.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.tags_add_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onDone = {
                                if (newTag.isNotBlank()) { vm.addTag(entry.id, newTag); newTag = "" }
                            },
                        ),
                    )
                    // 库内已有标签快捷添加(排除本条目已有的)
                    val suggestions = allTags.filter { it !in entry.tags }
                    if (suggestions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            suggestions.take(12).forEach { t ->
                                androidx.compose.material3.SuggestionChip(
                                    onClick = { vm.addTag(entry.id, t) },
                                    label = { Text(t) },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTag.isNotBlank()) vm.addTag(entry.id, newTag)
                    tagsFor = null
                }) { Text(stringResource(R.string.vhome_ok)) }
            },
            dismissButton = { TextButton(onClick = { tagsFor = null }) { Text(stringResource(R.string.vhome_cancel)) } },
        )
    }

    // ---------- 重命名 ----------
    renameTarget?.let { entry ->
        var name by remember { mutableStateOf(entry.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.vhome_rename)) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { vm.renameEntry(entry.id, name); renameTarget = null }) { Text(stringResource(R.string.vhome_ok)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.vhome_cancel)) } },
        )
    }

    // ---------- 新建文件夹 ----------
    if (newFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text(stringResource(R.string.vhome_new_folder)) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = { vm.newFolder(name); newFolderDialog = false }) { Text(stringResource(R.string.vhome_create)) }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text(stringResource(R.string.vhome_cancel)) } },
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
            title = { Text(stringResource(R.string.vhome_move_to)) },
            text = {
                Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                    MoveTargetRow(
                        stringResource(if (currentParent == null) R.string.vhome_root_current else R.string.vhome_root),
                        onClick = { vm.moveEntries(selection, null); moveDialog = false },
                    )
                    folders.forEach { (f, path) ->
                        MoveTargetRow(
                            if (f.id == currentParent) stringResource(R.string.vhome_path_current, path) else path,
                            onClick = { vm.moveEntries(selection, f.id); moveDialog = false },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveDialog = false }) { Text(stringResource(R.string.vhome_cancel)) } },
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
            confirmButton = { TextButton(onClick = { textPreview = null }) { Text(stringResource(R.string.vhome_close)) } },
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
                    InfoRow(stringResource(R.string.vhome_info_type), stringResource(when (entry.kind) {
                        MediaKind.IMAGE -> R.string.vhome_kind_image; MediaKind.VIDEO -> R.string.vhome_kind_video
                        MediaKind.AUDIO -> R.string.vhome_kind_audio; MediaKind.FOLDER -> R.string.vhome_kind_folder
                        MediaKind.OTHER -> R.string.vhome_kind_file
                    }))
                    if (entry.isFolder) {
                        // 直接子项数 + 递归子孙数与总大小——"这个文件夹占多少空间"是常见诉求
                        val desc = remember(entry.id, index) {
                            index?.let { i ->
                                i.descendantIds(entry.id).mapNotNull { i.find(it) }
                            } ?: emptyList()
                        }
                        InfoRow(stringResource(R.string.vhome_info_contains), stringResource(R.string.vhome_info_contains_value, folderCounts[entry.id] ?: 0, desc.size))
                        InfoRow(stringResource(R.string.vhome_info_total_size), formatBytes(desc.sumOf { it.sizeBytes }))
                    } else {
                        InfoRow(stringResource(R.string.vhome_info_size), formatBytes(entry.sizeBytes))
                    }
                    entry.mimeType?.let { InfoRow(stringResource(R.string.vhome_info_mime), it) }
                    InfoRow(stringResource(R.string.vhome_info_location), parentPathOf(entry, index, stringResource(R.string.vhome_root)))
                    InfoRow(stringResource(R.string.vhome_info_created), fmt.format(java.util.Date(entry.createdAt)))
                    InfoRow(stringResource(R.string.vhome_info_modified), fmt.format(java.util.Date(entry.updatedAt)))
                }
            },
            confirmButton = { TextButton(onClick = { infoTarget = null }) { Text(stringResource(R.string.vhome_close)) } },
        )
    }

    // ---------- .vlt 便携密文导入(逐个收密码,错密码留在队列可重试) ----------
    vltImportQueue.firstOrNull()?.let { src ->
        var pw by remember(src.name) { mutableStateOf("") }
        var err by remember(src.name) { mutableStateOf<String?>(null) }
        // 回调里用到的提示先在组合层捕获,协程回调不能直接调 stringResource
        val errTooShort = stringResource(R.string.vhome_password_min_length)
        AlertDialog(
            onDismissRequest = {
                // 关掉=放弃这个文件,继续队列下一个(最后一个则连目标一起清)
                if (vltImportQueue.size <= 1) vm.clearVltQueue() else vm.popVlt()
            },
            title = { Text(stringResource(R.string.vhome_vlt_import_title, src.name)) },
            text = {
                Column(Modifier.imePadding()) {
                    Text(stringResource(R.string.vhome_vlt_import_message))
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; err = null }, stringResource(R.string.vhome_vlt_import_password_hint), isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                // 解密中禁重入——否则会撞"已有传输"分支并误报"密码错误"
                TextButton(
                    enabled = transfer == null,
                    onClick = {
                        if (pw.length < 4) { err = errTooShort; return@TextButton }
                        vm.importVlt(src, pw.toCharArray(), vltImportTarget) { failure ->
                        // null = 成功/用户取消 → 弹下一个;非空 = 真实失败原因,留在队首可重试或跳过
                        if (failure == null) {
                            if (vltImportQueue.size <= 1) vm.clearVltQueue() else vm.popVlt()
                        } else {
                            err = failure
                        }
                    }
                }) { Text(stringResource(R.string.vhome_vlt_import_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (vltImportQueue.size <= 1) vm.clearVltQueue() else vm.popVlt()
                }) { Text(stringResource(R.string.vhome_skip)) }
            },
        )
    }

    // ---------- .vlt 加密导出(独立密码) ----------
    vltExportFor?.let { entry ->
        if (vltPassword == null) {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var err by remember { mutableStateOf<String?>(null) }
            val errTooShort = stringResource(R.string.vhome_password_min_length)
            val errMismatch = stringResource(R.string.vhome_password_mismatch)
            AlertDialog(
                onDismissRequest = { vltExportFor = null },
                title = { Text(stringResource(R.string.vhome_vlt_export_title, entry.name)) },
                text = {
                    Column(Modifier.imePadding()) {
                        Text(stringResource(R.string.vhome_vlt_export_message))
                        Spacer(Modifier.height(8.dp))
                        PasswordField(pw, { pw = it; err = null }, stringResource(R.string.vhome_vlt_export_password_hint))
                        Spacer(Modifier.height(4.dp))
                        PasswordField(pw2, { pw2 = it; err = null }, stringResource(R.string.vhome_vlt_export_password_confirm_hint), isError = err != null, supportingText = err)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        when {
                            pw.length < 4 -> err = errTooShort
                            pw != pw2 -> err = errMismatch
                            else -> {
                                vltPassword = pw
                                vltLauncher.launch("${entry.name}.vlt")
                            }
                        }
                    }) { Text(stringResource(R.string.vhome_choose_save_location)) }
                },
                dismissButton = { TextButton(onClick = { vltExportFor = null }) { Text(stringResource(R.string.vhome_cancel)) } },
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
            title = stringResource(R.string.vhome_delete_confirm_title, doomedCount),
            text = stringResource(if (doomedCount > selection.size) R.string.vhome_delete_confirm_cascade else R.string.vhome_delete_confirm_simple),
            confirmText = stringResource(R.string.vhome_delete),
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

/** 条目所在目录的路径标签(属性对话框用):"根目录 / a / b";根标签由调用方按语言传入。 */
private fun parentPathOf(
    entry: VaultEntry,
    index: io.vaultx.app.core.vault.VaultIndex?,
    rootLabel: String,
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
    return if (parts.isEmpty()) rootLabel else parts.joinToString(" / ")
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
    modifier: Modifier = Modifier,
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
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (entry.starred) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Star, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    buildString {
                        append(
                            subtitle ?: if (entry.isFolder) stringResource(R.string.vhome_item_count, childCount ?: 0)
                            else formatBytes(entry.sizeBytes),
                        )
                        if (entry.tags.isNotEmpty()) append(" · ${entry.tags.joinToString(" ")}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            } else if (!selectionMode) {
                IconButton(onClick = onOverflow, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.vhome_more), Modifier.size(18.dp))
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
    modifier: Modifier = Modifier,
    onDragAbort: () -> Unit = {},
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
    // 被拖格子滚出视口被回收时,手势协程随之取消但不走 onDragCancel——
    // 父级拖拽态会残留(幽灵影子 + 自动滚动循环不停)。离开组合即中止拖拽。
    androidx.compose.runtime.DisposableEffect(draggable) {
        onDispose { if (draggable) onDragAbort() }
    }
    Card(
        modifier = modifier
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
                    onDragCancel = { onDragAbort() },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.starred) {
                        Icon(
                            Icons.Filled.Star, null, Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    subtitle ?: if (entry.isFolder) stringResource(R.string.vhome_item_count, childCount ?: 0) else formatBytes(entry.sizeBytes),
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
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.vhome_more), Modifier.size(18.dp))
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
