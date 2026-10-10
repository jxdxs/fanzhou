package com.kevin.fanzhou.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E1116)
val Surface1 = Color(0xFF171B22)
val Surface2 = Color(0xFF212733)
val Accent = Color(0xFF4F8CFF)
val TextDim = Color(0xFF9AA4B2)

@Composable
fun FanzhouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            background = Bg,
            surface = Bg,
            surfaceVariant = Surface1,
            surfaceContainer = Surface1,
            surfaceContainerHigh = Surface2,
            surfaceContainerHighest = Surface2,
            surfaceContainerLow = Surface1,
            onSurface = Color(0xFFE8ECF2),
            onSurfaceVariant = TextDim,
            secondaryContainer = Color(0xFF223355),
        ),
        content = content,
    )
}
