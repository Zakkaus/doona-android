package dev.doona.android.designsystem.rv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/**
 * Whether 活動's hero swipes (brief rv-gestures H1): H0 it shows the traffic only (the default); H1 a horizontal swipe,
 * or the dots under it, turns it between 全部流量 and 主節點, the same caption, figure and sub line filled with the main node.
 */
internal enum class RvHeroSwipe(val code: String) { Off("H0"), On("H1") }

/** The hero art's alpha: from 0 up over [RvMotion.HERO_FADE_MS] on a page a hard-cut tab switch just showed, else 1. */
@Composable
internal fun rvHeroArt(): () -> Float {
    val motion = Rv.motion
    val entering = LocalRvTabEntry.current && !motion.instant
    val alpha = remember { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, motion.tween(RvMotion.HERO_FADE_MS, LinearEasing)) }
    return { alpha.value }
}
