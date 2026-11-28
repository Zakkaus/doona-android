package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Revolut's round companion button ('Buy' beside 'Get started'; web `Button` icon-only): a 56 dp disc in
 * [RvColors.roundButton] with no shadow, [icon] in the text colour, [rvPress]'s layer; the screen reader hears [label].
 * The 節點 page's floating actions put one beside [RvPrimaryButton].
 */
@Composable
internal fun RvRoundButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Box(
        modifier
            .size(RvNodesSize.ctaCompanion)
            .clip(CircleShape)
            .background(colors.roundButton)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvNodesSize.ctaGlyph, colors.text) }
}
