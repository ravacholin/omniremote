package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = BrutalistWhite,
    onPrimary = Color.Black,
    primaryContainer = RemoteBtnActive,
    onPrimaryContainer = BrutalistWhite,
    secondary = BrutalistOffWhite,
    onSecondary = Color.Black,
    secondaryContainer = RemoteSurfaceVariant,
    onSecondaryContainer = BrutalistWhite,
    tertiary = BrutalistSilver,
    background = RemoteDarkBg,
    onBackground = TextPrimary,
    surface = RemoteSurface,
    onSurface = TextPrimary,
    surfaceVariant = RemoteCardBg,
    onSurfaceVariant = TextSecondary,
    outline = RemoteBorder,
    outlineVariant = RemoteBorderStrong,
    error = PowerRedAccent,
    onError = Color.Black
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false, // Keep consistent cinema remote palette
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(2.dp),
            medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(4.dp),
            extraLarge = RoundedCornerShape(4.dp)
        ),
        content = content
    )
}
