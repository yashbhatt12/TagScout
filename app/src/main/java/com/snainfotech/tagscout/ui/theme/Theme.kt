package com.snainfotech.tagscout.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// TagScout uses a single, fixed light color scheme built from the brand palette.
// Dynamic color (Material You wallpaper tinting) and dark mode are intentionally
// disabled: this is a professional tool that should look identical on every device.
private val TagScoutColorScheme = lightColorScheme(
    primary = Primary,            // navy
    onPrimary = Color.White,
    secondary = Amber,            // amber accent
    onSecondary = Color.White,
    tertiary = SuccessGreen,
    background = LightGray,
    onBackground = DarkText,
    surface = CardBg,
    onSurface = DarkText,
    error = ErrorRed,
    onError = Color.White,
    outline = BorderGray
)

@Composable
fun TagScoutTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TagScoutColorScheme,
        typography = Typography,
        content = content
    )
}