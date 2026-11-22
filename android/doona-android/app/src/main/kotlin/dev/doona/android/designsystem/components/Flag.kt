package dev.doona.android.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.flags.flagResource

/**
 * A country's flag as a disc of [size] (40 dp in a row): HatScripts' circle-flags for [code] (ISO 3166-1 alpha-2, or
 * another code circle-flags files a flag under, either case) with a 1 dp ring of black 8 % in light or white 10 % in
 * dark (X-18). A code without artwork, or none, gets the fallback disc with the code's first two letters on it. The
 * [current] node's flag carries a check badge at its bottom end, so the current node is never marked by tint alone; the
 * badge's ring takes [ring], the colour the flag sits on. The flag is decorative unless it has a [contentDescription].
 */
@Composable
fun DoonaFlag(
    code: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    current: Boolean = false,
    ring: Color = DoonaTheme.colors.card,
    contentDescription: String? = null,
) {
    val colors = DoonaTheme.colors
    val art = code?.let(::flagResource)
    val described = contentDescription?.let { label -> Modifier.semantics { this.contentDescription = label } } ?: Modifier
    Box(modifier.layoutRole(LayoutRole.Flag).size(size).then(described)) {
        val disc = Modifier.matchParentSize().clip(CircleShape)
        if (art != null) {
            Image(painterResource(art), contentDescription = null, disc.border(DoonaTheme.size.ring, colors.flagRing, CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(disc.background(colors.flagFallback), contentAlignment = Alignment.Center) {
                LineText(code?.take(2)?.uppercase() ?: NO_VALUE, DoonaTheme.type.flagCode, color = colors.textSecondary)
            }
        }
        if (current) {
            val overhang = DoonaTheme.size.badgeOverhang
            CurrentBadge(ring, Modifier.align(Alignment.BottomEnd).offset(overhang, overhang))
        }
    }
}

/** The current node's badge (measured §7 `avatar_badgeBottom`): a 16 dp accent disc with a check, ringed in [ring]. */
@Composable
private fun CurrentBadge(ring: Color, modifier: Modifier) {
    val colors = DoonaTheme.colors
    val badge = DoonaTheme.size
    Canvas(modifier.size(badge.badge)) {
        val r = size.minDimension / 2
        drawCircle(ring, r)
        drawCircle(colors.positive, r - badge.badgeRing.toPx())
        val check = Path().apply {
            moveTo(size.width * DoonaFlagDefaults.CHECK_START_X, size.height * DoonaFlagDefaults.CHECK_START_Y)
            lineTo(size.width * DoonaFlagDefaults.CHECK_KNEE_X, size.height * DoonaFlagDefaults.CHECK_KNEE_Y)
            lineTo(size.width * DoonaFlagDefaults.CHECK_END_X, size.height * DoonaFlagDefaults.CHECK_END_Y)
        }
        drawPath(check, colors.onPositive, style = Stroke(badge.badgeCheck.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The check's three points, as shares of the badge's box. */
internal object DoonaFlagDefaults {
    const val CHECK_START_X = 0.31f
    const val CHECK_START_Y = 0.52f
    const val CHECK_KNEE_X = 0.44f
    const val CHECK_KNEE_Y = 0.65f
    const val CHECK_END_X = 0.69f
    const val CHECK_END_Y = 0.38f
}
