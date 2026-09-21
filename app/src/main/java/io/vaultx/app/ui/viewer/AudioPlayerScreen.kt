package io.vaultx.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultDataSource
import kotlinx.coroutines.delay

/** 音频播放:图标 + 标题 + 进度条,播放走与视频相同的解密 DataSource;解码失败显式报错。 */
@Composable
fun AudioPlayerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val unlocked = container.session.get(vaultId)
    val entry = unlocked?.let { container.vaultManager.loadIndex(it).find(entryId) }

    var playError by remember { mutableStateOf<String?>(null) }

    val player = remember(entryId) {
        if (unlocked == null || entry?.blobId == null) {
            null
        } else {
            val factory = VaultDataSource.Factory(
                unlocked, container.vaultManager, entry.blobId,
                entry.sizeBytes,
            )
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playError = "播放失败:${error.errorCodeName}"
                        }
                    })
                    setMediaItem(MediaItem.fromUri("vaultx://$vaultId/${entry.blobId}"))
                    prepare()
                    playWhenReady = true
                }
        }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        while (player != null) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    Box(Modifier.fillMaxSize()) {
        if (player == null || playError != null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    playError ?: "无法打开该音频",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.AudioFile, null, Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    entry?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(32.dp))
                Slider(
                    value = if (duration > 0) position.toFloat() / duration else 0f,
                    onValueChange = { f ->
                        if (duration > 0) player.seekTo((f * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${fmt(position)} / ${fmt(duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                }) {
                    Text(if (player.isPlaying) "暂停" else "播放")
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
