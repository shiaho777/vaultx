package io.vaultx.app.ui.session

import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.core.vault.MediaKind
import kotlinx.coroutines.delay

/** 无锁模式预览:会话文件是明文,图片走 Coil、音视频走 ExoPlayer 直接放。 */
@Composable
fun SessionPlayerScreen(
    container: AppContainer,
    storedName: String,
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val file = remember(storedName) { container.sessionManager.file(storedName) }
    val fileMissing = remember(storedName) { !file.isFile }
    val kind = remember(storedName) {
        container.sessionManager.listFiles().firstOrNull { it.storedName == storedName }?.kind
            ?: MediaKind.OTHER
    }
    var playError by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        when {
            fileMissing -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("文件已不存在", style = MaterialTheme.typography.bodyLarge)
                }
            }
            kind == MediaKind.IMAGE -> {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = file,
                        contentDescription = storedName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            kind == MediaKind.VIDEO -> {
                if (playError == null) {
                    val player = remember(storedName) {
                        ExoPlayer.Builder(context).build().apply {
                            addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) {
                                    playError = "播放失败:${error.errorCodeName}"
                                }
                            })
                            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                            prepare()
                            playWhenReady = true
                        }
                    }
                    DisposableEffect(Unit) { onDispose { player.release() } }
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = player
                                    keepScreenOn = true
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    PlayerError(playError!!)
                }
            }
            kind == MediaKind.AUDIO -> {
                if (playError == null) {
                    val player = remember(storedName) {
                        ExoPlayer.Builder(context).build().apply {
                            addListener(object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) {
                                    playError = "播放失败:${error.errorCodeName}"
                                }
                            })
                            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                            prepare()
                            playWhenReady = true
                        }
                    }
                    var position by remember { mutableLongStateOf(0L) }
                    var duration by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            position = player.currentPosition.coerceAtLeast(0)
                            duration = player.duration.coerceAtLeast(0)
                            delay(500)
                        }
                    }
                    DisposableEffect(Unit) { onDispose { player.release() } }
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.AudioFile, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(20.dp))
                        Text(storedName, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(24.dp))
                        Slider(
                            value = if (duration > 0) position.toFloat() / duration else 0f,
                            onValueChange = { if (duration > 0) player.seekTo((it * duration).toLong()) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                            Text(if (player.isPlaying) "暂停" else "播放")
                        }
                    }
                } else {
                    PlayerError(playError!!)
                }
            }
            else -> {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("该类型不支持预览", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        // 返回条(半透明遮罩)
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
                storedName,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun PlayerError(msg: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(msg, style = MaterialTheme.typography.bodyLarge)
    }
}
