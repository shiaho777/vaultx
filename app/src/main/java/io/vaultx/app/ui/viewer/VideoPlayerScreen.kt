package io.vaultx.app.ui.viewer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.vaultx.app.AppContainer
import io.vaultx.app.R
import io.vaultx.app.core.media.VaultDataSource
import io.vaultx.app.core.vault.MediaKind
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 视频播放:同目录播放列表连播——ExoPlayer playlist + [VaultDataSource]
 * (密文按需分段解密,拖动进度条不整段落地;open 时从 uri 解析 blobId)。
 * 播放中保持亮屏;解码失败/条目缺失显示错误而非黑屏。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
                        .filter { it.kind == MediaKind.VIDEO && !it.isFolder && !it.isTrashed && it.parentId == me?.parentId && it.blobId != null }
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

    // 组合层捕获文案模板,错误回调里只做格式化
    val playErrorFmt = stringResource(R.string.vplay_error)
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
                            playError = playErrorFmt.format(error.errorCodeName)
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

    // 按 player 键控:首帧 player=null 时注册的 Unit 效应会把 null 闭包捕获到底,
    // 列表到位后创建的播放器退出时永不释放(泄漏/退出后继续播)
    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var chromeVisible by remember { mutableStateOf(true) }
    // 顶栏显示 3 秒后自动隐藏;点击/手势递增此值重置计时
    var chromeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(chromeVisible, chromeTick) {
        if (chromeVisible) {
            delay(3000)
            chromeVisible = false
        }
    }
    // 手势 HUD:亮度/音量百分比或 seek 预览文案
    var hudText by remember { mutableStateOf<String?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(hudTick) {
        if (hudText != null) {
            delay(900)
            hudText = null
        }
    }
    // seek 手势:累计位移换算目标位置,松手才真正 seek
    var seekAccumPx by remember { mutableFloatStateOf(0f) }
    var seekStartMs by remember { mutableLongStateOf(0L) }
    var seekTargetMs by remember { mutableLongStateOf(0L) }
    // 音量手势的小数累计(系统音量是整数档,直接取整会丢增量)
    var volumeAccum by remember { mutableFloatStateOf(0f) }
    val brightnessFmt = stringResource(R.string.viewer_brightness)
    val volumeFmt = stringResource(R.string.viewer_volume)
    val seekFmt = stringResource(R.string.viewer_seek)

    fun formatMs(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // 退出时恢复窗口默认亮度,避免手势调暗影响其他界面
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.apply { screenBrightness = -1f }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (player != null && playError == null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        // 自带控制器关闭:点击/手势统一走 Compose 层,避免互相抢事件
                        useController = false
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // 手势层:单击显隐顶栏;左半屏竖滑亮度,右半屏竖滑音量,横滑 seek(松手生效)
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            chromeVisible = !chromeVisible
                            chromeTick++
                        }
                    }
                    .pointerInput(player) {
                        detectVerticalDragGestures(
                            onDragStart = { pos ->
                                chromeVisible = true
                                chromeTick++
                                if (pos.x < size.width / 2f) {
                                    // 窗口亮度未设置过时先落到中间值,再跟随手势
                                    activity?.window?.let { w ->
                                        if (w.attributes.screenBrightness < 0f) {
                                            w.attributes = w.attributes.apply { screenBrightness = 0.5f }
                                        }
                                    }
                                } else {
                                    volumeAccum = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                }
                            },
                            onDragEnd = { hudTick++ },
                            onDragCancel = { hudTick++ },
                        ) { change, dragAmount ->
                            change.consume()
                            chromeTick++
                            val h = size.height.toFloat().coerceAtLeast(1f)
                            if (change.position.x < size.width / 2f) {
                                val w = activity?.window ?: return@detectVerticalDragGestures
                                val cur = w.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                                val next = (cur - dragAmount / h).coerceIn(0.01f, 1f)
                                w.attributes = w.attributes.apply { screenBrightness = next }
                                hudText = brightnessFmt.format((next * 100).roundToInt())
                            } else {
                                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                volumeAccum = (volumeAccum - dragAmount / h * max).coerceIn(0f, max.toFloat())
                                val next = volumeAccum.roundToInt()
                                if (next != audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                }
                                hudText = volumeFmt.format(if (max > 0) next * 100 / max else 0)
                            }
                        }
                    }
                    .pointerInput(player) {
                        detectHorizontalDragGestures(
                            onDragStart = {
                                seekAccumPx = 0f
                                seekStartMs = player.currentPosition.coerceAtLeast(0L)
                                seekTargetMs = seekStartMs
                                chromeVisible = true
                                chromeTick++
                            },
                            onDragEnd = {
                                player.seekTo(seekTargetMs)
                                hudTick++
                            },
                            onDragCancel = { hudTick++ },
                        ) { change, dragAmount ->
                            change.consume()
                            chromeTick++
                            seekAccumPx += dragAmount
                            val dur = player.duration
                            // 全屏宽 ≈ 整段时长(时长未知按 90 秒),目标位置钳制在 [0, duration]
                            val span = if (dur != C.TIME_UNSET && dur > 0) dur else 90_000L
                            val w = size.width.toFloat().coerceAtLeast(1f)
                            val maxMs = if (dur != C.TIME_UNSET && dur > 0) dur else Long.MAX_VALUE
                            seekTargetMs = (seekStartMs + (seekAccumPx / w * span).toLong()).coerceIn(0L, maxMs)
                            hudText = seekFmt.format(formatMs(seekTargetMs))
                        }
                    },
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
                        playError ?: stringResource(R.string.vplay_open_failed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        // 手势 HUD:亮度/音量/seek 预览,短暂显示后自动消失
        hudText?.let { text ->
            Text(
                text,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 顶栏:返回 + 片名 + 上一部/下一部(列表内连播时才有);点击画面显隐,3 秒自动隐
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.vplay_back), tint = Color.White)
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
                        Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.vplay_previous),
                        tint = if (hasPrev) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
                IconButton(
                    onClick = { player?.seekToNextMediaItem() },
                    enabled = hasNext,
                ) {
                    Icon(
                        Icons.Filled.SkipNext, contentDescription = stringResource(R.string.vplay_next),
                        tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.3f),
                    )
                }
            }
        }
        }
    }
}
