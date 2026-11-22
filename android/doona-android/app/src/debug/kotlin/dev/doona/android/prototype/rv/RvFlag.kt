package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.R

/**
 * A country's flag as a disc of [size], wherever the sample draws one: HatScripts' circle-flags for [code] (ISO 3166
 * alpha-2, either case) with a 1 dp ring of black 8 % in light or white 10 % in dark (X-18). A code without artwork, or
 * none, gets the fallback disc with the code on it.
 */
@Composable
internal fun RvFlag(code: String?, size: Dp, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val art = code?.let(::flagArt)
    val disc = modifier.size(size).clip(CircleShape)
    if (art != null) {
        Image(painterResource(art), contentDescription = null, disc.border(RvChrome.ring, colors.flagRing, CircleShape), contentScale = ContentScale.Crop)
    } else {
        Box(disc.background(colors.flagFallback), contentAlignment = Alignment.Center) {
            RvText(code?.uppercase() ?: RV_NO_VALUE, Rv.type.flagCode, color = colors.textSecondary)
        }
    }
}

/** The circle-flags artwork the sample carries, by country code. */
@DrawableRes
private fun flagArt(code: String): Int? = when (code.uppercase()) {
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
