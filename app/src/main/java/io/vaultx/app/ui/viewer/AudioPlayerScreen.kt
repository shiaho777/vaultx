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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.vaultx.app.core.vault.MediaKind
import kotlinx.coroutines.delay

/**
 * 音频播放:同目录播放列表 + 上一首/播放暂停/下一首,进度条可拖。
 * 播放走与视频相同的解密 DataSource;解码失败显式报错;切歌重置进度。
 */
@Composable
fun AudioPlayerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val unlocked = container.session.get(vaultId)
    val audios = remember(unlocked) {
        unlocked?.let { u ->
            runCatching {
                val idx = container.vaultManager.loadIndex(u)
                val me = idx.find(entryId)
                idx.entries
                    .filter { it.kind == MediaKind.AUDIO && !it.isFolder && it.parentId == me?.parentId && it.blobId != null }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }
    val sizes = remember(audios) {
        audios.mapNotNull { e -> e.blobId?.let { it to e.sizeBytes } }.toMap()
    }
    val startIndex = audios.indexOfFirst { it.id == entryId }.coerceAtLeast(0)

    var playError by remember { mutableStateOf<String?>(null) }
    var mediaIndex by remember { mutableIntStateOf(startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasPrev by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    // 拖动期间本地预览位置,松手才真 seek——拖动中逐帧 seek 会反复重开解密通道
    var seekDragging by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableFloatStateOf(0f) }

    val player = remember(unlocked) {
        if (unlocked == null || audios.isEmpty()) {
            null
        } else {
            val factory = VaultDataSource.Factory(unlocked, container.vaultManager, sizes)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playError = "播放失败:${error.errorCodeName}"
                        }

                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            mediaIndex = currentMediaItemIndex
                            position = 0L
                            duration = 0L
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        override fun onEvents(player: Player, events: Player.Events) {
                            hasPrev = player.hasPreviousMediaItem()
                            hasNext = player.hasNextMediaItem()
                        }
                    })
                    setMediaItems(
                        audios.map { MediaItem.fromUri("vaultx://$vaultId/${it.blobId}") },
                        startIndex, 0L,
                    )
                    prepare()
                    playWhenReady = true
                }
        }
    }

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
                // 有加密缩略图(内嵌封面/抽帧)时作封面,否则通用图标
                val cur = audios.getOrNull(mediaIndex)
                val artBlob = cur?.takeIf { it.hasThumb }?.blobId
                if (artBlob != null) {
                    coil3.compose.AsyncImage(
                        model = io.vaultx.app.core.media.VaultImageRef(vaultId, artBlob, preferThumb = true),
                        contentDescription = cur?.name,
                        modifier = Modifier
                            .size(160.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Icon(
                        Icons.Filled.AudioFile, null, Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    audios.getOrNull(mediaIndex)?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (audios.size > 1) {
                    Text(
                        "${mediaIndex + 1} / ${audios.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(32.dp))
                Slider(
                    value = if (seekDragging) {
                        seekPreview
                    } else if (duration > 0) {
                        position.toFloat() / duration
                    } else {
                        0f
                    },
                    onValueChange = { f -> seekDragging = true; seekPreview = f },
                    onValueChangeFinished = {
                        if (duration > 0) player.seekTo((seekPreview * duration).toLong())
                        seekDragging = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${fmt(if (seekDragging) (seekPreview * duration).toLong() else position)} / ${fmt(duration)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                // 播放控制条:上一首 / 播放暂停 / 下一首
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekToPreviousMediaItem() }, enabled = hasPrev) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                    }
                    IconButton(
                        onClick = { if (isPlaying) player.pause() else player.play() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = { player.seekToNextMediaItem() }, enabled = hasNext) {
                        Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                    }
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
