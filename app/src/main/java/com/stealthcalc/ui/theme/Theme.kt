package com.stealthcalc.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val StealthDarkColorScheme = darkColorScheme(
    primary = StealthPrimary,
    onPrimary = StealthOnPrimary,
    primaryContainer = StealthPrimaryContainer,
    onPrimaryContainer = StealthOnPrimaryContainer,
    secondary = StealthSecondary,
    tertiary = StealthTertiary,
    surface = StealthSurface,
    onSurface = StealthOnSurface,
    surfaceVariant = StealthSurfaceVariant,
    background = StealthBackground,
    error = StealthError,
)

private val AmoledColorScheme = darkColorScheme(
    primary = StealthPrimary,
    onPrimary = StealthOnPrimary,
    primaryContainer = StealthPrimaryContainer,
    onPrimaryContainer = StealthOnPrimaryContainer,
    secondary = StealthSecondary,
    tertiary = StealthTertiary,
    surface = AmoledSurface,
    onSurface = StealthOnSurface,
    surfaceVariant = AmoledSurfaceVariant,
    background = AmoledBackground,
    error = StealthError,
)

@Composable
fun StealthCalcTheme(
    useAmoled: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (useAmoled) AmoledColorScheme else StealthDarkColorScheme
    val bgColor = if (useAmoled) AmoledBackground else StealthBackground
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = bgColor.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = StealthTypography,
        content = content
    )
}
