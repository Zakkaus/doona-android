package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.R

/**
 * The duck mark at [size]: a full-colour drawable, so an Image rather than [RvGlyph], whose tint would flatten it.
 * Decorative: the title beside or under it names the page.
 */
@Composable
internal fun RvDuckMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_duck), contentDescription = null, modifier.size(size))
}
