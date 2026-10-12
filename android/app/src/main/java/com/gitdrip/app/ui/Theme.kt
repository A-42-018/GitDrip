package com.gitdrip.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun c(v: Long) = Color(v)

/**
 * Neo-Tactile is one look: frosted glass on a dark slate backdrop with light raised controls. The system dark/light setting does not change it.
 * Every M3 slot is mapped so stock components (dialog text, chips, progress) stay coherent; [NeoRaisedScheme] is used inside light raised surfaces.
 */
private val NeoDarkScheme: ColorScheme = darkColorScheme(
    primary = c(Neo.AccentOnDark), onPrimary = c(Neo.TextOnLight),
    primaryContainer = c(Neo.ActiveEnd), onPrimaryContainer = c(Neo.OnActive),
    secondary = c(Neo.RingStroke), onSecondary = c(Neo.TextOnLight),
    secondaryContainer = c(Neo.SurfMid), onSecondaryContainer = c(Neo.TextOnDark),
    tertiary = c(Neo.WarningOnDark), onTertiary = c(Neo.TextOnLight),
    tertiaryContainer = c(Neo.SurfMid), onTertiaryContainer = c(Neo.WarningOnDark),
    background = c(Neo.BgEnd), onBackground = c(Neo.TextOnDark),
    surface = c(Neo.SurfHigh), onSurface = c(Neo.TextOnDark),
    surfaceVariant = c(Neo.SurfMid), onSurfaceVariant = c(Neo.Text2OnDark),
    surfaceTint = c(Neo.AccentOnDark),
    inverseSurface = c(Neo.RaisedCard), inverseOnSurface = c(Neo.TextOnLight), inversePrimary = c(Neo.AccentOnLight),
    error = c(Neo.DangerOnDark), onError = c(Neo.TextOnLight),
    errorContainer = c(Neo.SurfMid), onErrorContainer = c(Neo.DangerOnDark),
    outline = c(Neo.GlassRim), outlineVariant = c(Neo.Track),
    scrim = c(Neo.BgEnd),
    surfaceContainerLowest = c(Neo.BgEnd), surfaceContainerLow = c(Neo.SurfLow),
    surfaceContainer = c(Neo.SurfMid), surfaceContainerHigh = c(Neo.SurfHigh),
    surfaceContainerHighest = c(Neo.SurfHighest),
)

/** Colours for content drawn on the light raised surfaces (cards, buttons, modals): dark text, `background` is light so [isDarkTheme] reads false. */
private val NeoRaisedScheme: ColorScheme = lightColorScheme(
    primary = c(Neo.AccentOnLight), onPrimary = c(Neo.OnActive),
    primaryContainer = c(Neo.RaisedModal), onPrimaryContainer = c(Neo.TextOnLight),
    secondary = c(Neo.TealOnLight), onSecondary = c(Neo.OnActive),
    secondaryContainer = c(Neo.RaisedModal), onSecondaryContainer = c(Neo.TextOnLight),
    tertiary = c(Neo.WarningOnLight), onTertiary = c(Neo.OnActive),
    tertiaryContainer = c(Neo.RaisedModal), onTertiaryContainer = c(Neo.WarningOnLight),
    background = c(Neo.RaisedCard), onBackground = c(Neo.TextOnLight),
    surface = c(Neo.RaisedCard), onSurface = c(Neo.TextOnLight),
    surfaceVariant = c(Neo.RaisedModal), onSurfaceVariant = c(Neo.Text2OnLight),
    surfaceTint = c(Neo.AccentOnLight),
    inverseSurface = c(Neo.InsetFill), inverseOnSurface = c(Neo.TextOnDark), inversePrimary = c(Neo.AccentOnDark),
    error = c(Neo.DangerOnLight), onError = c(Neo.OnActive),
    errorContainer = c(Neo.RaisedModal), onErrorContainer = c(Neo.DangerOnLight),
    outline = c(Neo.OutlineOnLight), outlineVariant = c(Neo.OutlineOnLight),
    scrim = c(Neo.BgEnd),
    surfaceContainerLowest = c(Neo.RaisedButton), surfaceContainerLow = c(Neo.RaisedCard),
    surfaceContainer = c(Neo.RaisedCard), surfaceContainerHigh = c(Neo.RaisedModal),
    surfaceContainerHighest = c(Neo.RaisedModal),
)

/** System sans, no font download. sp units scale with the user's font size. */
private val GitDripTypography = Typography(
    displaySmall = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
)

private val GitDripShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(Radius.Control.dp),
    medium = RoundedCornerShape(Radius.Card.dp),
    large = RoundedCornerShape(Radius.Panel.dp),
    extraLarge = RoundedCornerShape(Radius.Sheet.dp),
)

/** [darkTheme] is kept for source compatibility and ignored: Neo-Tactile has a single (dark backdrop) look. */
@Composable
fun GitDripTheme(@Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeoDarkScheme, typography = GitDripTypography, shapes = GitDripShapes, content = content)
}

/**
 * Content on a light raised surface (card, button, modal): switches the colour scheme to dark-on-light and sets the default content colour,
 * so existing `MaterialTheme.colorScheme.*` and plain `Text` calls pick the right colours with no per-screen changes.
 */
@Composable
fun NeoRaisedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeoRaisedScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides c(Neo.TextOnLight), content = content)
    }
}
