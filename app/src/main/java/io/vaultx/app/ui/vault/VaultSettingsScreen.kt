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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.vaultx.app.AppContainer
import io.vaultx.app.core.vault.VaultMeta
import io.vaultx.app.ui.components.ConfirmDialog
import io.vaultx.app.ui.components.PasswordField
import io.vaultx.app.ui.components.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    var meta by remember { mutableStateOf(container.vaultManager.metaOf(vaultId)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var changePwDialog by remember { mutableStateOf(false) }
    var decoyDialog by remember { mutableStateOf(false) }
    var disableDecoyConfirm by remember { mutableStateOf(false) }
    var disableBioConfirm by remember { mutableStateOf(false) }

    val viaDecoy = unlocked?.viaDecoy == true
    val bioEnabled = container.vaultManager.readBioWrap(vaultId) != null
    val bioAvailable = container.biometrics.isAvailable(context)
    val diskUsage = remember(meta) { container.vaultManager.vaultDiskUsage(vaultId) }

    fun refreshMeta() {
        meta = container.vaultManager.metaOf(vaultId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("库设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            SettingRow("库名称", meta.name)
            SettingRow("磁盘占用", formatBytes(diskUsage))
            SettingRow("KDF 强度", "Argon2id ${meta.kdfParams.memoryKiB / 1024}MiB × ${meta.kdfParams.iterations}")
            if (viaDecoy) {
                SettingRow("当前会话", "诱骗库", tint = MaterialTheme.colorScheme.tertiary)
            }

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
            Text("密码", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { changePwDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (viaDecoy) "修改诱骗密码" else "修改密码(无需重加密文件)") }

            if (!viaDecoy) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("访问控制", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))

                // 总密码开关
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("总密码", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (meta.hasDecoy) "配置诱骗库后必须开启(否则泄露真实条目数)"
                            else "开启后索引加密存储,进库需解锁",
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
                                busy = "转换索引…"
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        val idx = container.vaultManager.loadIndex(u)
                                        container.vaultManager.setMasterGate(u, idx, wantOn)
                                    }
                                }.onSuccess { refreshMeta() }
                                    .onFailure { error = "切换失败:${it.message}" }
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
                        Text("诱骗库", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (meta.hasDecoy) "已配置:另一组密码打开独立空间"
                            else "被迫交出密码时,给出能解锁但内容不同的密码",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (meta.hasDecoy) {
                        TextButton(onClick = { disableDecoyConfirm = true }) {
                            Text("移除", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = { decoyDialog = true }) { Text("配置") }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 生物识别
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("生物识别解锁", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when {
                                !bioAvailable -> "设备不支持或未录入生物信息"
                                bioEnabled -> "已开启:指纹/面部可直接解锁(密码仍是兜底)"
                                else -> "用指纹/面部解锁;密钥不出安全芯片"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (bioEnabled) {
                        TextButton(onClick = { disableBioConfirm = true }) {
                            Text("关闭", color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(
                            enabled = bioAvailable && unlocked != null && !viaDecoy,
                            onClick = {
                                val u = unlocked ?: return@TextButton
                                // 包裹 VMK 写 bio.wrap(包裹动作不需要活体;解包才要)
                                runCatching {
                                    container.vaultManager.writeBioWrap(
                                        vaultId,
                                        container.biometrics.wrapVmk(vaultId, u.crypto.vmk),
                                    )
                                }.onSuccess {
                                    notice = "生物识别已开启"
                                    refreshMeta()
                                }.onFailure { error = "写入失败:${it.message}" }
                            },
                        ) { Text("开启") }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ---------- 改密码 ----------
    if (changePwDialog) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { changePwDialog = false },
            title = { Text(if (viaDecoy) "修改诱骗密码" else "修改密码") },
            text = {
                Column(Modifier.imePadding()) {
                    Text("只重包裹密钥,全部文件零重加密", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; err = null }, "新密码")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw2, { pw2 = it; err = null }, "确认新密码", isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pw.length < 4 -> err = "密码至少 4 位"
                        pw != pw2 -> err = "两次密码不一致"
                        else -> {
                            val u = unlocked ?: return@TextButton
                            scope.launch {
                                busy = "改密码…"
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        container.vaultManager.changePassword(u, pw.toCharArray())
                                    }
                                }.onSuccess { notice = "密码已更新"; changePwDialog = false; refreshMeta() }
                                    .onFailure { err = "失败:${it.message}" }
                                busy = null
                            }
                        }
                    }
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { changePwDialog = false }) { Text("取消") } },
        )
    }

    // ---------- 配置诱骗库 ----------
    if (decoyDialog) {
        var pw by remember { mutableStateOf("") }
        var pw2 by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { decoyDialog = false },
            title = { Text("配置诱骗库") },
            text = {
                Column(Modifier.imePadding()) {
                    Text(
                        "设一个能解锁但展示不同内容的密码。开启后:索引强制加密,条目数不再免密可见。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw, { pw = it; err = null }, "诱骗密码")
                    Spacer(Modifier.height(8.dp))
                    PasswordField(pw2, { pw2 = it; err = null }, "确认诱骗密码", isError = err != null, supportingText = err)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        pw.length < 4 -> err = "密码至少 4 位"
                        pw != pw2 -> err = "两次密码不一致"
                        else -> {
                            val u = unlocked ?: return@TextButton
                            scope.launch {
                                busy = "配置诱骗库…"
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        container.vaultManager.enableDecoy(u, pw.toCharArray())
                                    }
                                }.onSuccess { notice = "诱骗库已配置"; decoyDialog = false; refreshMeta() }
                                    .onFailure { err = "失败:${it.message}" }
                                busy = null
                            }
                        }
                    }
                }) { Text("开启") }
            },
            dismissButton = { TextButton(onClick = { decoyDialog = false }) { Text("取消") } },
        )
    }

    // ---------- 移除诱骗 ----------
    if (disableDecoyConfirm) {
        ConfirmDialog(
            title = "移除诱骗库?",
            text = "诱骗密码将失效,诱骗空间内的数据无法恢复。",
            confirmText = "移除",
            danger = true,
            onConfirm = {
                val u = unlocked ?: return@ConfirmDialog
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { container.vaultManager.disableDecoy(u) }
                    }.onSuccess { notice = "诱骗库已移除"; refreshMeta() }
                        .onFailure { error = "失败:${it.message}" }
                    disableDecoyConfirm = false
                }
            },
            onDismiss = { disableDecoyConfirm = false },
        )
    }

    // ---------- 关闭生物识别 ----------
    if (disableBioConfirm) {
        ConfirmDialog(
            title = "关闭生物识别?",
            text = "之后只能用密码解锁此库。",
            confirmText = "关闭",
            onConfirm = {
                container.vaultManager.deleteBioWrap(vaultId)
                container.biometrics.deleteKey(vaultId)
                disableBioConfirm = false
                notice = "生物识别已关闭"
                refreshMeta()
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
