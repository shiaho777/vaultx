package io.vaultx.app.ui.viewer

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultImageRef

/**
 * 图片查看器:原图(非缩略图)+ 双指缩放/拖动。
 * 解密走 Coil Fetcher,这里完全无感知。
 */
@Composable
fun ImageViewerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
) {
    val unlocked = container.session.get(vaultId)
    val entry = unlocked?.let { container.vaultManager.loadIndex(it).find(entryId) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (entry?.blobId != null) {
            AsyncImage(
                model = VaultImageRef(vaultId, entry.blobId!!, preferThumb = false),
                contentDescription = entry.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}
