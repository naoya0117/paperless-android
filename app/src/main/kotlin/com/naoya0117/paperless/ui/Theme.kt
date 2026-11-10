package com.naoya0117.paperless.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Paperless-ngx's primary green (its web interface's `--pngx-primary`). */
private val PaperlessGreen = Color(0xFF17541F)

/** A lighter green that stays readable on black. */
private val PaperlessGreenOnDark = Color(0xFF6FBF73)

/**
 * Like the Paperless-ngx web interface: white (light) or black (dark) surfaces with a green accent. The
 * device's dynamic colors are deliberately not used.
 */
private val LightColors =
    lightColorScheme(
        primary = PaperlessGreen,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD7EBD9),
        onPrimaryContainer = Color(0xFF0B2E10),
        secondary = Color(0xFF3B6E42),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFE6F2E7),
        onSecondaryContainer = Color(0xFF13331A),
        background = Color.White,
        onBackground = Color(0xFF111111),
        surface = Color.White,
        onSurface = Color(0xFF111111),
        surfaceVariant = Color(0xFFF2F4F2),
        onSurfaceVariant = Color(0xFF555B56),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF8F9F8),
        surfaceContainer = Color(0xFFF3F5F3),
        surfaceContainerHigh = Color(0xFFEDEFED),
        surfaceContainerHighest = Color(0xFFE7EAE7),
        outline = Color(0xFFC4C9C4),
        outlineVariant = Color(0xFFE1E4E1),
    )

private val DarkColors =
    darkColorScheme(
        primary = PaperlessGreenOnDark,
        onPrimary = Color(0xFF00320A),
        primaryContainer = PaperlessGreen,
        onPrimaryContainer = Color(0xFFD7EBD9),
        secondary = Color(0xFF9FCFA4),
        onSecondary = Color(0xFF0B2E10),
        secondaryContainer = Color(0xFF1E3A22),
        onSecondaryContainer = Color(0xFFD7EBD9),
        background = Color.Black,
        onBackground = Color(0xFFEDEDED),
        surface = Color.Black,
        onSurface = Color(0xFFEDEDED),
        surfaceVariant = Color(0xFF1C1E1C),
        onSurfaceVariant = Color(0xFFB4BAB4),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0E0F0E),
        surfaceContainer = Color(0xFF141614),
        surfaceContainerHigh = Color(0xFF1B1D1B),
        surfaceContainerHighest = Color(0xFF232523),
        outline = Color(0xFF5A605A),
        outlineVariant = Color(0xFF2C2F2C),
    )

@Composable
fun PaperlessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
