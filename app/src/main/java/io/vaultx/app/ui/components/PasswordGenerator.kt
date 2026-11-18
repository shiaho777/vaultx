package io.vaultx.app.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.vaultx.app.R
import io.vaultx.app.core.crypto.PasswordStrength
import java.security.SecureRandom

/**
 * 密码生成器:SecureRandom 取字符,保证每个启用的字符类至少出现一次,
 * 再 Fisher–Yates 洗牌消除"前几位固定按类排列"的位置偏置。
 * 纯生成逻辑,不触碰加密核心。
 */
object PasswordGenerator {

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    // 不含引号/反斜杠/空格:避免归档命令行、CSV 等场景的转义麻烦
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?"

    private val rng = SecureRandom()

    /** [length] ≥ 启用类数(调用侧最短 12、最多 4 类,恒成立);至少启用一类。 */
    fun generate(length: Int, lower: Boolean, upper: Boolean, digits: Boolean, symbols: Boolean): String {
        val pools = buildList {
            if (lower) add(LOWER)
            if (upper) add(UPPER)
            if (digits) add(DIGITS)
            if (symbols) add(SYMBOLS)
        }
        require(pools.isNotEmpty()) { "at least one charset must be enabled" }
        val all = pools.joinToString("")
        val chars = ArrayList<Char>(length)
        // 每个启用类先保底一个
        pools.forEach { chars.add(it[rng.nextInt(it.length)]) }
        repeat(length - chars.size) { chars.add(all[rng.nextInt(all.length)]) }
        for (i in chars.indices.reversed()) {
            val j = rng.nextInt(i + 1)
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        return String(chars.toCharArray())
    }
}

/**
 * 生成器对话框:长度(12/16/20/24,默认 16)+ 字符集开关(至少保留一类)
 * + 强度条(复用 [PasswordStrength])+ 重新生成 + 一键复制(带剪贴板安全提示)。
 * [onUse] 把当前结果回填给调用方(通常同时填两个密码框)。
 */
@Composable
fun PasswordGeneratorDialog(
    onDismiss: () -> Unit,
    onUse: (String) -> Unit,
) {
    val context = LocalContext.current
    var length by remember { mutableIntStateOf(16) }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var regenTick by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    // 选项变化/点重新生成 → 立即出新结果
    LaunchedEffect(length, lower, upper, digits, symbols, regenTick) {
        password = PasswordGenerator.generate(length, lower, upper, digits, symbols)
    }
    val enabledCount = listOf(lower, upper, digits, symbols).count { it }
    val score = PasswordStrength.score(password)
    val copiedText = stringResource(R.string.pwgen_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pwgen_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = password,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.pwgen_result)) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java) ?: return@IconButton
                            val clip = ClipData.newPlainText("VaultX", password)
                            // API 33+:标记敏感,系统剪贴板预览打码
                            if (Build.VERSION.SDK_INT >= 33) {
                                clip.description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            cm.setPrimaryClip(clip)
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.pwgen_copy))
                        }
                    },
                )
                if (password.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
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
                        stringResource(R.string.pwgen_strength, stringResource(PasswordStrength.labelRes(score))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pwgen_length), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12, 16, 20, 24).forEach { n ->
                        FilterChip(
                            selected = length == n,
                            onClick = { length = n },
                            label = { Text("$n") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pwgen_charset), style = MaterialTheme.typography.labelLarge)
                CharsetSwitch(stringResource(R.string.pwgen_lowercase), lower, enabledCount) { lower = it }
                CharsetSwitch(stringResource(R.string.pwgen_uppercase), upper, enabledCount) { upper = it }
                CharsetSwitch(stringResource(R.string.pwgen_digits), digits, enabledCount) { digits = it }
                CharsetSwitch(stringResource(R.string.pwgen_symbols), symbols, enabledCount) { symbols = it }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { regenTick++ },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pwgen_regenerate))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUse(password) }, enabled = password.isNotEmpty()) {
                Text(stringResource(R.string.pwgen_use))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.widget_cancel)) } },
    )
}

/** 字符集开关行;最后一个启用类不允许关闭(至少保留一类)。 */
@Composable
private fun CharsetSwitch(label: String, checked: Boolean, enabledCount: Int, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = !(checked && enabledCount <= 1),
        )
    }
}
