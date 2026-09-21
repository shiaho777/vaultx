package io.vaultx.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultImageRef
import kotlin.math.min

/**
 * 图片查看器:原图(非缩略图)+ 双指缩放(带边界钳制)+ 拖动 + 双击定点缩放。
 * 解密走 Coil Fetcher,这里完全无感知。
 */
@Composable
fun ImageViewerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
    onBack: () -> Unit = {},
) {
    val unlocked = container.session.get(vaultId)
    val entry = unlocked?.let { container.vaultManager.loadIndex(it).find(entryId) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(Size.Unspecified) }

    /** 图片经 ContentScale.Fit 适配后的实际显示尺寸。 */
    fun fittedSize(): Size {
        val c = containerSize
        val i = imageSize
        if (c == IntSize.Zero || i == Size.Unspecified || i.width <= 0f || i.height <= 0f) return Size.Zero
        val f = min(c.width / i.width, c.height / i.height)
        return Size(i.width * f, i.height * f)
    }

    /** 拖动边界:缩放后图片边缘不许拖出屏幕露出空洞(短边方向自然为 0)。 */
    fun clamped(o: Offset, s: Float): Offset {
        val f = fittedSize()
        val c = containerSize
        val maxX = ((f.width * s - c.width) / 2f).coerceAtLeast(0f)
        val maxY = ((f.height * s - c.height) / 2f).coerceAtLeast(0f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        // 双击:1x ↔ 2.5x;放大时保持点击点下的内容不动
                        val target = if (scale > 1.01f) 1f else 2.5f
                        val newOffset = if (target == 1f) {
                            Offset.Zero
                        } else {
                            val factor = target / scale
                            val pivot = Offset(containerSize.width / 2f, containerSize.height / 2f)
                            offset * factor + (tap - pivot) * (1 - factor)
                        }
                        scale = target
                        offset = clamped(newOffset, scale)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = newScale
                    offset = clamped(if (scale > 1f) offset + pan else Offset.Zero, scale)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (entry?.blobId != null) {
            AsyncImage(
                model = VaultImageRef(vaultId, entry.blobId, preferThumb = false),
                contentDescription = entry.name,
                onSuccess = { imageSize = it.painter.intrinsicSize },
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
        } else {
            Text(
                "无法打开该文件",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        // 顶栏:返回 + 文件名(半透明遮罩保证可读)
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
                entry?.name ?: "",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
        }
    }
}
