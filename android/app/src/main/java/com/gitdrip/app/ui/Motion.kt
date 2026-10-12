package com.gitdrip.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** False when the system "Remove animations" is on (ANIMATOR_DURATION_SCALE = 0). Provided once in MainActivity. */
val LocalAnimations = staticCompositionLocalOf { true }

/** Toggle for "Solid surfaces"; the current value is [LocalSolidSurfaces]. Provided in MainActivity. */
val LocalSetSolid = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** Persisted UI appearance (plain SharedPreferences in the UI layer; no new dependency, no permission). */
object Appearance {
    private const val PREFS = "gitdrip_prefs"
    private const val K_SOLID = "solid_surfaces"
    fun solid(c: Context): Boolean = try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_SOLID, false) } catch (_: Exception) { false }
    fun setSolid(c: Context, on: Boolean) { try { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_SOLID, on).apply() } catch (_: Exception) { } }
    fun animationsOn(c: Context): Boolean = DashboardLogic.animationsEnabled(Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
}

/** 0.98 scale while pressed (graphicsLayer only: no relayout, no recomposition of children). */
@Composable
fun Modifier.pressScale(source: InteractionSource): Modifier {
    val animate = LocalAnimations.current
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(MotionLogic.pressScale(pressed, animate), tween(MotionLogic.PressMs), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

/** Fade-in for the first rows of a list. Rows past [MotionLogic.StaggerMaxItems] and "Remove animations" render at full alpha at once. */
@Composable
fun Modifier.fadeInItem(index: Int): Modifier {
    val animate = MotionLogic.shouldAnimateEntry(index, LocalAnimations.current)
    val a = remember { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(Unit) { a.animateTo(1f, tween(MotionLogic.FadeMs, MotionLogic.enterDelayMs(index, true))) }
    return alpha(a.value)
}

/** Thin sweeping bar shown while syncing (decorative: the "Syncing…" text/live region carries the meaning). */
@Composable
fun SyncShimmer(modifier: Modifier = Modifier) {
    val animate = LocalAnimations.current
    val t = if (animate) {
        val inf = rememberInfiniteTransition(label = "shimmer")
        val v by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "pos")
        v
    } else 0.5f
    val pos = MotionLogic.shimmerPos(t, animate)
    val base = MaterialTheme.colorScheme.primary
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(base.copy(alpha = 0.18f)).drawBehind {
            val w = size.width * 0.35f; val x = (size.width + w) * pos - w
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, base, Color.Transparent), startX = x, endX = x + w), Offset(x.coerceAtLeast(0f), 0f), androidx.compose.ui.geometry.Size(w.coerceAtMost(size.width), size.height))
        },
    )
}
