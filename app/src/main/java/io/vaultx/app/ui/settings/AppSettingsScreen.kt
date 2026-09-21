package io.vaultx.app.ui.settings

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.vaultx.app.AppContainer
import kotlinx.coroutines.launch

/** 应用设置:后台自动锁定时长 + 防截屏。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val autoLock by container.settings.autoLockSeconds.collectAsState()
    val flagSecure by container.settings.flagSecure.collectAsState()

    val lockOptions = listOf(
        0 to "立即",
        30 to "30 秒",
        60 to "1 分钟",
        300 to "5 分钟",
        900 to "15 分钟",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用设置") },
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
            Text("安全", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("防截屏", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "禁止截图/录屏与最近任务缩略图泄露内容",
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

            Text("进入后台后自动锁定", style = MaterialTheme.typography.bodyLarge)
            Text(
                "锁定时内存中的密钥与已解密内容立即清零",
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
                "VaultX 不联网、不收集数据、不要权限。\n所有密钥仅存于内存,进程退出即消失。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
