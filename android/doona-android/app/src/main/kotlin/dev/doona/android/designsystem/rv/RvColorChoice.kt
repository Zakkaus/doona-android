package dev.doona.android.designsystem.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A colour to pick, as 主題's 強調色 discs: a 36 dp disc of [color] in a 48 dp target; the [selected] one has a ring of
 * the text colour round it. The screen reader hears [name]; while not [enabled] it takes no press (the row dims it).
 */
@Composable
internal fun RvColorChoice(color: Color, name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(RvSize.touch)
            .rvPressSelect(CircleShape, selected, onClick, enabled)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        val ring = if (selected) Modifier.border(RvSettingsSize.accentRingStroke, Rv.colors.text, CircleShape) else Modifier
        Box(Modifier.size(RvSettingsSize.accentRing).then(ring), contentAlignment = Alignment.Center) {
            Box(Modifier.size(RvSettingsSize.accentDisc).clip(CircleShape).background(color))
        }
    }
}
