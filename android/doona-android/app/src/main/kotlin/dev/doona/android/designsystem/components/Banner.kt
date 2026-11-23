package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

/**
 * Error banner: a card with the error tone's dot and border, holding a message that may wrap. It is the one card that
 * keeps a border: the border says it is an error, which a card's fill does not. Tapping it dismisses it; screen
 * readers announce it when it appears.
 */
@Composable
fun DoonaErrorBanner(message: String, dismissLabel: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.card
    val style = DoonaTheme.type.body
    Row(
        modifier
            .layoutRole(LayoutRole.Card)
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.touch)
            .clip(shape)
            .background(c.card)
            .border(DoonaTheme.size.ring, c.slow, shape)
            .clickable(role = Role.Button, onClickLabel = dismissLabel, onClick = onDismiss)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(DoonaTheme.spacing.cardInset),
        verticalAlignment = Alignment.Top,
    ) {
        LineDot(Tone.Err, style, DoonaTheme.size.statusDot)
        Spacer(Modifier.width(DoonaTheme.spacing.item))
        Text(scripted(message), style = style, color = c.text)
    }
}
