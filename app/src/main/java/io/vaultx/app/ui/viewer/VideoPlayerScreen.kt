package io.vaultx.app.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultDataSource

/**
 * 视频播放:ExoPlayer + [VaultDataSource]——密文按需分段解密,拖动进度条不整段落地。
 */
@Composable
fun VideoPlayerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
) {
    val context = LocalContext.current
    val unlocked = container.session.get(vaultId)
    val entry = unlocked?.let { container.vaultManager.loadIndex(it).find(entryId) }

    val player = remember(entryId) {
        if (unlocked == null || entry?.blobId == null) {
            null
        } else {
            val factory = VaultDataSource.Factory(
                unlocked, container.vaultManager, entry.blobId!!,
                entry.sizeBytes,
            )
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri("vaultx://$vaultId/${entry.blobId}"))
                    prepare()
                    playWhenReady = true
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}
