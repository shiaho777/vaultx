package io.vaultx.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.vaultx.app.BuildConfig
import io.vaultx.app.R

/**
 * 关于页:全部内容离线内置,应用自身绝不发起网络请求。
 * 外链一律通过 ACTION_VIEW 交给系统浏览器打开(浏览器是独立应用,不受本应用零网络约束)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showThirdPartyDialog by remember { mutableStateOf(false) }

    // 第三方许可清单:内置 raw 资源,离线读取一次
    val thirdPartyLicenses = remember {
        context.resources.openRawResource(R.raw.third_party_licenses)
            .bufferedReader().use { it.readText() }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            val message = if (it is ActivityNotFoundException) {
                context.getString(R.string.about_link_error)
            } else {
                it.message ?: context.getString(R.string.about_link_error)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back))
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
            Spacer(Modifier.height(16.dp))

            // 身份卡:图标 + 名称 + 版本 + 一句话介绍
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon = remember {
                    context.packageManager.getApplicationIcon(context.applicationInfo).toBitmap().asImageBitmap()
                }
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.about_section_license), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            AboutRow(
                icon = Icons.Filled.Gavel,
                title = stringResource(R.string.about_license_title),
                summary = stringResource(R.string.about_license_summary),
                onClick = { showLicenseDialog = true },
            )
            AboutRow(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.about_third_party_title),
                summary = stringResource(R.string.about_third_party_summary),
                onClick = { showThirdPartyDialog = true },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.about_section_community), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            AboutRow(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.about_github_title),
                summary = stringResource(R.string.about_github_summary),
                onClick = { openUrl(context.getString(R.string.about_url_repo)) },
            )
            AboutRow(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.about_issues_title),
                summary = stringResource(R.string.about_issues_summary),
                onClick = { openUrl(context.getString(R.string.about_url_issues)) },
            )
            AboutRow(
                icon = Icons.Filled.Group,
                title = stringResource(R.string.about_contributing_title),
                summary = stringResource(R.string.about_contributing_summary),
                onClick = { openUrl(context.getString(R.string.about_url_contributing)) },
            )
            AboutRow(
                icon = Icons.Filled.Group,
                title = stringResource(R.string.about_contributors_title),
                summary = stringResource(R.string.about_contributors_summary),
                onClick = { openUrl(context.getString(R.string.about_url_contributors)) },
            )
            AboutRow(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.about_changelog_title),
                summary = stringResource(R.string.about_changelog_summary),
                onClick = { openUrl(context.getString(R.string.about_url_changelog)) },
            )
            AboutRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.about_security_title),
                summary = stringResource(R.string.about_security_summary),
                onClick = { openUrl(context.getString(R.string.about_url_security)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.about_section_privacy), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            listOf(
                R.string.about_privacy_no_collection,
                R.string.about_privacy_no_network,
                R.string.about_privacy_local_storage,
                R.string.about_privacy_no_recovery,
                R.string.about_privacy_backup,
            ).forEach { resId ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(resId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.about_section_author), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            // 作者槽位:换维护者只需改 about_author_handle / about_url_author 两个字符串资源
            AboutRow(
                icon = Icons.Filled.Person,
                title = stringResource(R.string.about_author_handle),
                summary = stringResource(R.string.about_author_summary),
                onClick = { openUrl(context.getString(R.string.about_url_author)) },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLicenseDialog) {
        AlertDialog(
            onDismissRequest = { showLicenseDialog = false },
            title = { Text(stringResource(R.string.about_license_dialog_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.about_license_dialog_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLicenseDialog = false
                    openUrl(context.getString(R.string.about_url_license))
                }) { Text(stringResource(R.string.about_license_view_full)) }
            },
            dismissButton = {
                TextButton(onClick = { showLicenseDialog = false }) { Text(stringResource(R.string.about_close)) }
            },
        )
    }

    if (showThirdPartyDialog) {
        AlertDialog(
            onDismissRequest = { showThirdPartyDialog = false },
            title = { Text(stringResource(R.string.about_third_party_title)) },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        thirdPartyLicenses,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThirdPartyDialog = false }) { Text(stringResource(R.string.about_close)) }
            },
        )
    }
}

/** 通用入口行:图标 + 标题 + 一句话副文案。 */
@Composable
private fun AboutRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
