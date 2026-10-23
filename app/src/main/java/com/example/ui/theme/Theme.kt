package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Every edge in the app is square. */
val Sharp = RoundedCornerShape(0.dp)

private val DarkColorScheme = darkColorScheme(
    primary = Paper,
    onPrimary = Void,
    primaryContainer = Paper,
    onPrimaryContainer = Void,
    secondary = Ash,
    onSecondary = Void,
    secondaryContainer = Ink,
    onSecondaryContainer = Paper,
    tertiary = Ash,
    background = Void,
    onBackground = Paper,
    surface = Void,
    onSurface = Paper,
    surfaceVariant = Ink,
    onSurfaceVariant = Ash,
    surfaceContainerLowest = Void,
    surfaceContainerLow = Ink,
    surfaceContainer = Ink,
    surfaceContainerHigh = Ink,
    surfaceContainerHighest = Ink,
    surfaceTint = Color.Transparent,
    outline = Hairline,
    outlineVariant = Hairline,
    error = Paper,
    onError = Void,
    scrim = Color(0xE6000000)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false, // The palette is fixed monochrome
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = Sharp,
            small = Sharp,
            medium = Sharp,
            large = Sharp,
            extraLarge = Sharp
        ),
        content = content
    )
}
