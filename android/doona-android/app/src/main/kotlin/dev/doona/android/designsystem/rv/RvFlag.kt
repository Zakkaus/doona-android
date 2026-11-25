package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.flags.flagResource

/**
 * A country's flag as a disc of [size], wherever the sample draws one: HatScripts' circle-flags for [code] (ISO 3166
 * alpha-2, either case; [rvFlagArt]) with a 1 dp ring of black 8 % in light or white 10 % in dark (X-18). A code without artwork, or
 * none, gets the fallback disc with the code on it. The [current] node's flag carries a check badge at its bottom end,
 * so the current node is never marked by tint alone; its ring takes [ring], the colour the flag sits on. It grows in and
 * shrinks out over [RvMotion.FADE_MS] as the current node changes.
 */
@Composable
internal fun RvFlag(code: String?, size: Dp, modifier: Modifier = Modifier, current: Boolean = false, ring: Color = Rv.colors.card) {
    val colors = Rv.colors
    val art = code?.let(::rvFlagArt)
    Box(modifier.size(size).testTag(RvRowPart.FLAG)) {
        val disc = Modifier.matchParentSize().clip(CircleShape)
        if (art != null) {
            Image(painterResource(art), contentDescription = null, disc.border(RvChrome.ring, colors.flagRing, CircleShape), contentScale = ContentScale.Crop)
        } else {
            Box(disc.background(colors.flagFallback), contentAlignment = Alignment.Center) {
                RvText(code?.uppercase() ?: RV_NO_VALUE, Rv.type.flagCode, color = colors.textSecondary)
            }
        }
        // The check leaves the old node and comes to the new one by scale and fade as the current node changes.
        val shown by animateFloatAsState(if (current) 1f else 0f, Rv.motion.tween(RvMotion.FADE_MS), label = "check")
        if (shown > 0f) {
            val grow = if (shown < 1f) {
                Modifier.graphicsLayer {
                    scaleX = shown
                    scaleY = shown
                    alpha = shown
                }
            } else {
                Modifier
            }
            RvCurrentBadge(ring, Modifier.align(Alignment.BottomEnd).offset(RvSize.badgeOverhang, RvSize.badgeOverhang).then(grow))
        }
    }
}

/** The current node's badge (measured §7 `avatar_badgeBottom`): a 16 dp accent disc with a check, ringed in [ring]. */
@Composable
internal fun RvCurrentBadge(ring: Color, modifier: Modifier) {
    val colors = Rv.colors
    Canvas(modifier.size(RvSize.badge)) {
        val r = size.minDimension / 2
        drawCircle(ring, r)
        drawCircle(colors.positive, r - RvSize.badgeRing.toPx())
        val check = Path().apply {
            moveTo(size.width * CHECK_START.first, size.height * CHECK_START.second)
            lineTo(size.width * CHECK_KNEE.first, size.height * CHECK_KNEE.second)
            lineTo(size.width * CHECK_END.first, size.height * CHECK_END.second)
        }
        drawPath(check, colors.onPositive, style = Stroke(RvSize.badgeCheck.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The check's three points, as shares of the badge's box. */
private val CHECK_START = 0.31f to 0.52f
private val CHECK_KNEE = 0.44f to 0.65f
private val CHECK_END = 0.69f to 0.38f

/**
 * The circle-flags artwork for [code]: the library's own for the first twelve places, whose approved pages were drawn
 * with it, else the `:flags` module's copy of the same set at the same revision (every place doona names has one).
 */
@DrawableRes
internal fun rvFlagArt(code: String): Int? = libraryFlag(code) ?: flagResource(code)

@DrawableRes
private fun libraryFlag(code: String): Int? = when (code.uppercase()) {
    "AU" -> R.drawable.rv_flag_au
    "CA" -> R.drawable.rv_flag_ca
    "DE" -> R.drawable.rv_flag_de
    "FR" -> R.drawable.rv_flag_fr
    "GB" -> R.drawable.rv_flag_gb
    "HK" -> R.drawable.rv_flag_hk
    "JP" -> R.drawable.rv_flag_jp
    "KR" -> R.drawable.rv_flag_kr
    "NL" -> R.drawable.rv_flag_nl
    "SG" -> R.drawable.rv_flag_sg
    "TW" -> R.drawable.rv_flag_tw
    "US" -> R.drawable.rv_flag_us
    else -> null
}
