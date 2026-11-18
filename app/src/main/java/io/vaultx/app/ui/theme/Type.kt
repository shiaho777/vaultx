package io.vaultx.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VaultX 排版层级:在 M3 基线上收紧字距、加重标题,
 * 让"保险库"界面有明确的视觉锚点而不是满屏同权重灰字。
 */
val VaultXTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(letterSpacing = 0.1.sp),
        bodyMedium = bodyMedium.copy(letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    )
}

/**
 * 间距令牌:4dp 基网格。界面层新代码一律引用这里,
 * 不再散落魔法 dp(存量代码逐步迁移)。
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 屏幕水平安全边距。 */
    val screenHorizontal = 16.dp

    /** 卡片/格子间距。 */
    val gridGap = 8.dp
}