package io.vaultx.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * VaultX 主题:深色为主的"保险库"气质——深底 + 冷青强调色。
 * 支持动态取色(Android 12+)与静态品牌色兜底。
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF00585E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF1F6),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF4A6365),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8EA),
    onSecondaryContainer = Color(0xFF051F21),
    tertiary = Color(0xFF4E5F7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E3FF),
    onTertiaryContainer = Color(0xFF081C36),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF4FAFA),
    onBackground = Color(0xFF161D1D),
    surface = Color(0xFFF4FAFA),
    onSurface = Color(0xFF161D1D),
    surfaceVariant = Color(0xFFDAE4E5),
    onSurfaceVariant = Color(0xFF3F494A),
    outline = Color(0xFF6F797A),
    inverseSurface = Color(0xFF2B3232),
    inverseOnSurface = Color(0xFFECF2F2),
    inversePrimary = Color(0xFF80D4DA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D4DA),
    onPrimary = Color(0xFF00363A),
    primaryContainer = Color(0xFF004F54),
    onPrimaryContainer = Color(0xFF9CF1F6),
    secondary = Color(0xFFB0CBCD),
    onSecondary = Color(0xFF1B3436),
    secondaryContainer = Color(0xFF324B4D),
    onSecondaryContainer = Color(0xFFCCE8EA),
    tertiary = Color(0xFFB6C7E9),
    onTertiary = Color(0xFF20314C),
    tertiaryContainer = Color(0xFF374764),
    onTertiaryContainer = Color(0xFFD5E3FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1515),
    onBackground = Color(0xFFDDE4E4),
    surface = Color(0xFF0E1515),
    onSurface = Color(0xFFDDE4E4),
    surfaceVariant = Color(0xFF3F494A),
    onSurfaceVariant = Color(0xFFBEC8C9),
    outline = Color(0xFF899393),
    inverseSurface = Color(0xFFDDE4E4),
    inverseOnSurface = Color(0xFF2B3232),
    inversePrimary = Color(0xFF00585E),
)

@Composable
fun VaultXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
