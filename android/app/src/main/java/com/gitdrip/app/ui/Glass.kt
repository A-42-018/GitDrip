package com.gitdrip.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/** Surface depth of a card: L1 card, L2 raised, L3 modal. Mapped to Neo raised colours in GlassComponents. */
enum class GlassLevel { L1, L2, L3 }

/** "Solid surfaces" switch (Settings > Appearance, persisted by [Appearance]). When true every glass panel is opaque. */
val LocalSolidSurfaces = compositionLocalOf { false }

/** True on the dark Neo scheme; false inside [NeoRaisedTheme] (content on light raised surfaces). */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

val CardShape = RoundedCornerShape(Radius.Card.dp)
val ControlShape = RoundedCornerShape(Radius.Control.dp)
val SheetShape = RoundedCornerShape(Radius.Sheet.dp)
