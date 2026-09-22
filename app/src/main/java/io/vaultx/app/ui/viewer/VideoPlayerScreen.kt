package io.vaultx.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultDataSource
import io.vaultx.app.core.vault.MediaKind

/**
 * 视频播放:同目录播放列表连播——ExoPlayer playlist + [VaultDataSource]
 * (密文按需分段解密,拖动进度条不整段落地;open 时从 uri 解析 blobId)。
 * 播放中保持亮屏;解码失败/条目缺失显示错误而非黑屏。
 */
@Composable
fun VideoPlayerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val unlocked = container.session.get(vaultId)
    // 同目录全部视频(名称排序),构成播放列表;null=加载中——索引读盘走 IO 线程
    val videos by androidx.compose.runtime.produceState<List<io.vaultx.app.core.vault.VaultEntry>?>(
        initialValue = null, unlocked,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            unlocked?.let { u ->
                runCatching {
                    val idx = container.vaultManager.loadIndex(u)
                    val me = idx.find(entryId)
                    idx.entries
                        .filter { it.kind == MediaKind.VIDEO && !it.isFolder && it.parentId == me?.parentId && it.blobId != null }
                        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }
    }
    val vids = videos
    val sizes = remember(vids) {
        vids?.mapNotNull { e -> e.blobId?.let { it to e.sizeBytes } }?.toMap() ?: emptyMap()
    }
    val startIndex = vids?.indexOfFirst { it.id == entryId }?.coerceAtLeast(0) ?: 0

    var playError by remember { mutableStateOf<String?>(null) }
    var mediaIndex by remember { mutableIntStateOf(startIndex) }
    var hasPrev by remember { mutableStateOf(false) }
    var hasNext by remember { mutableStateOf(false) }

    // 列表到位才建播放器——startIndex 必须一开始就指向被点的那条
    val player = remember(vids) {
        if (unlocked == null || vids.isNullOrEmpty()) {
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
                        }

                        override fun onEvents(player: Player, events: Player.Events) {
                            hasPrev = player.hasPreviousMediaItem()
                            hasNext = player.hasNextMediaItem()
                        }
                    })
                    setMediaItems(
                        vids.map { MediaItem.fromUri("vaultx://$vaultId/${it.blobId}") },
                        startIndex, 0L,
                    )
                    prepare()
                    playWhenReady = true
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null && playError == null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                if (playError == null && vids == null) {
                    androidx.compose.material3.CircularProgressIndicator()
                } else {
                    Text(
                        playError ?: "无法打开该视频",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        // 顶栏:返回 + 片名 + 上一部/下一部(列表内连播时才有)
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text(
                vids?.getOrNull(mediaIndex)?.name ?: "",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if ((vids?.size ?: 0) > 1) {
                IconButton(
                    onClick = { player?.seekToPreviousMediaItem() },
                    enabled = hasPrev,
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious, contentDescription = "上一部",
                        tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
                IconButton(
                    onClick = { player?.seekToNextMediaItem() },
                    enabled = hasNext,
                ) {
                    Icon(
                        Icons.Filled.SkipNext, contentDescription = "下一部",
                        tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }
    }
}
