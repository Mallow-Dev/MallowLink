package com.mallowlink.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─────────────────────────────────────────────────────────────────────────────
// MallowLink palette
// Inspired by mallow flowers: soft lavender + warm petal hues
// ─────────────────────────────────────────────────────────────────────────────

private val MallowPurple = Color(0xFF7B5EA7)
private val MallowPurpleLight = Color(0xFFB08DD4)
private val MallowPink = Color(0xFFE8A0BF)
private val MallowSurface = Color(0xFF1A1625)
private val MallowSurfaceVariant = Color(0xFF2A2035)
private val MallowOnSurface = Color(0xFFEDE8F5)

private val DarkColorScheme = darkColorScheme(
    primary = MallowPurpleLight,
    onPrimary = Color(0xFF1A0D2E),
    primaryContainer = Color(0xFF4A3275),
    onPrimaryContainer = MallowOnSurface,
    secondary = MallowPink,
    onSecondary = Color(0xFF2D1025),
    secondaryContainer = Color(0xFF5C2C4A),
    onSecondaryContainer = Color(0xFFFFD7EC),
    tertiary = Color(0xFF8ACFCC),
    onTertiary = Color(0xFF003736),
    background = Color(0xFF120E1C),
    onBackground = MallowOnSurface,
    surface = MallowSurface,
    onSurface = MallowOnSurface,
    surfaceVariant = MallowSurfaceVariant,
    onSurfaceVariant = Color(0xFFCBC4D9),
    outline = Color(0xFF968EA4),
    error = Color(0xFFFFB4AB),
)

private val LightColorScheme = lightColorScheme(
    primary = MallowPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF24005A),
    secondary = Color(0xFF9C4070),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD7EC),
    onSecondaryContainer = Color(0xFF3E0028),
    tertiary = Color(0xFF006A69),
    onTertiary = Color.White,
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B23),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B23),
    surfaceVariant = Color(0xFFE7DFEE),
    onSurfaceVariant = Color(0xFF4A4551),
    outline = Color(0xFF7A7481),
)

@Composable
fun MallowLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MallowTypography,
        content = content,
    )
}
