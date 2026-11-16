package io.vaultx.app.ui.vault

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultx.app.R
import io.vaultx.app.core.vault.MediaKind
import io.vaultx.app.core.vault.VaultEntry
import io.vaultx.app.core.vault.VaultIndex
import io.vaultx.app.ui.components.ConfirmDialog
import io.vaultx.app.ui.components.EmptyState
import io.vaultx.app.ui.components.formatBytes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回收站:被删条目的顶层列表(文件夹连子孙一起恢复/彻底删除)。
 * 保留期 30 天,到期进库时自动清扫;也可手动清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    vm: VaultViewModel,
    onBack: () -> Unit,
) {
    val index by vm.index.collectAsState()
    val roots = remember(index) {
        index?.trashedRoots()?.sortedByDescending { it.trashedAt ?: 0 } ?: emptyList()
    }
    var rawSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 选择集合与现存条目保持同步(恢复/删除后失效 id 自动剔除),派生而非组合期写状态
    val rootIds = remember(roots) { roots.map { it.id }.toSet() }
    val selected = rawSelected.intersect(rootIds)

    var purgeConfirm by remember { mutableStateOf(false) }
    var emptyConfirm by remember { mutableStateOf(false) }
    val retentionDays = (VaultIndex.TRASH_RETENTION_MS / (24 * 3600 * 1000)).toInt()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) stringResource(R.string.trash_title)
                        else stringResource(R.string.trash_selected_count, selected.size),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.trash_back))
                    }
                },
                actions = {
                    if (roots.isNotEmpty()) {
                        if (selected.isEmpty()) {
                            TextButton(onClick = { rawSelected = rootIds }) {
                                Text(stringResource(R.string.trash_select_all))
                            }
                            TextButton(onClick = { emptyConfirm = true }) {
                                Text(stringResource(R.string.trash_empty_action), color = MaterialTheme.colorScheme.error)
                            }
                        } else {
                            TextButton(onClick = {
                                vm.restoreFromTrash(selected)
                                rawSelected = emptySet()
                            }) { Text(stringResource(R.string.trash_restore)) }
                            TextButton(onClick = { purgeConfirm = true }) {
                                Text(stringResource(R.string.trash_delete_forever), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (roots.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.trash_empty_title),
                    subtitle = stringResource(R.string.trash_empty_subtitle, retentionDays),
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    stringResource(R.string.trash_retention_hint, retentionDays),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(roots, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            descendantCount = index?.descendantIds(entry.id)?.size ?: 0,
                            checked = entry.id in selected,
                            selecting = selected.isNotEmpty(),
                            onToggle = {
                                rawSelected = if (entry.id in selected) selected - entry.id else selected + entry.id
                            },
                            onRestore = { vm.restoreFromTrash(setOf(entry.id)) },
                            onPurge = {
                                rawSelected = setOf(entry.id)
                                purgeConfirm = true
                            },
                        )
                    }
                }
            }
        }
    }

    if (purgeConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.trash_purge_confirm_title),
            text = stringResource(R.string.trash_purge_confirm_text, selected.size),
            confirmText = stringResource(R.string.trash_delete_forever),
            danger = true,
            onConfirm = {
                vm.purgeFromTrash(selected)
                rawSelected = emptySet()
                purgeConfirm = false
            },
            onDismiss = { purgeConfirm = false },
        )
    }
    if (emptyConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.trash_empty_confirm_title),
            text = stringResource(R.string.trash_empty_confirm_text, roots.size),
            confirmText = stringResource(R.string.trash_empty_action),
            danger = true,
            onConfirm = {
                vm.emptyTrash()
                rawSelected = emptySet()
                emptyConfirm = false
            },
            onDismiss = { emptyConfirm = false },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(
    entry: VaultEntry,
    descendantCount: Int,
    checked: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { if (selecting) onToggle() else onRestore() },
                onLongClick = onToggle,
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                when (entry.kind) {
                    MediaKind.IMAGE -> Icons.Filled.Image
                    MediaKind.VIDEO -> Icons.Filled.Movie
                    MediaKind.AUDIO -> Icons.Filled.AudioFile
                    MediaKind.FOLDER -> Icons.Filled.Folder
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                Text(
                    buildString {
                        append(stringResource(R.string.trash_deleted_at, fmt.format(Date(entry.trashedAt ?: 0))))
                        if (entry.isFolder) append(" · ${stringResource(R.string.trash_item_count, descendantCount)}")
                        else if (entry.sizeBytes > 0) append(" · ${formatBytes(entry.sizeBytes)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!selecting) {
                IconButton(onClick = onRestore) {
                    Icon(
                        Icons.Filled.RestoreFromTrash,
                        contentDescription = stringResource(R.string.trash_restore),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onPurge) {
                    Icon(
                        Icons.Filled.DeleteForever,
                        contentDescription = stringResource(R.string.trash_delete_forever),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}