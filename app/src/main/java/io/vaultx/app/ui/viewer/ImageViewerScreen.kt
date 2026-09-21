package io.vaultx.app.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.vaultx.app.AppContainer
import io.vaultx.app.core.media.VaultImageRef
import io.vaultx.app.core.vault.MediaKind
import kotlin.math.min

/**
 * 图片查看器:同目录图片左右滑页 + 双指缩放/拖移(边界钳制)+ 双击定点缩放
 * + 单击显隐顶栏。解密走 Coil Fetcher,这里完全无感知。
 *
 * 手势分工(关键):单指且未放大时手势**不消费**,交给 Pager 翻页;
 * 双指或已放大(1x 以上)时本层接管——Pager 的 userScrollEnabled 同步关闭。
 */
@Composable
fun ImageViewerScreen(
    container: AppContainer,
    vaultId: String,
    entryId: String,
    onBack: () -> Unit = {},
) {
    val unlocked = container.session.get(vaultId)
    // 同目录全部图片(与网格一致的名称排序),翻页范围
    val images = remember(unlocked) {
        unlocked?.let { u ->
            runCatching {
                val idx = container.vaultManager.loadIndex(u)
                val me = idx.find(entryId)
                idx.entries
                    .filter { it.kind == MediaKind.IMAGE && !it.isFolder && it.parentId == me?.parentId && it.blobId != null }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
            }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    val pagerState = rememberPagerState(
        initialPage = images.indexOfFirst { it.id == entryId }.coerceAtLeast(0),
    ) { images.size }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(Size.Unspecified) }
    var chromeVisible by remember { mutableStateOf(true) }

    // 翻页时重置缩放与适配尺寸
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
        imageSize = Size.Unspecified
    }

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
                    onTap = { chromeVisible = !chromeVisible },
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
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        // 单指未放大 → 让位 Pager;双指或已放大 → 本层接管
                        val ours = pressed.size >= 2 || scale > 1.01f
                        if (!ours) continue
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = newScale
                            offset = clamped(if (newScale > 1f) offset + pan else Offset.Zero, newScale)
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (images.isEmpty()) {
            Text(
                "无法打开该文件",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            HorizontalPager(
                state = pagerState,
                // 已放大时禁用翻页,位移全部归图片拖动
                userScrollEnabled = scale <= 1.01f,
                modifier = Modifier.fillMaxSize(),
                key = { images[it].id },
            ) { page ->
                val e = images[page]
                AsyncImage(
                    model = VaultImageRef(vaultId, e.blobId!!, preferThumb = false),
                    contentDescription = e.name,
                    onSuccess = {
                        if (pagerState.currentPage == page) imageSize = it.painter.intrinsicSize
                    },
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

        // 顶栏:返回 + 文件名 + 页码(单击显隐,半透明遮罩保证可读)
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    images.getOrNull(pagerState.currentPage)?.name ?: "",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (images.size > 1) {
                    Text(
                        "${pagerState.currentPage + 1} / ${images.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                }
            }
        }
    }
}
