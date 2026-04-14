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

@Composable
fun StealthCalcTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = StealthBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = StealthDarkColorScheme,
        typography = StealthTypography,
        content = content
    )
}
