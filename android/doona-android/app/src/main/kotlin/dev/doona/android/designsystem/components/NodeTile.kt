package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

/**
 * Node tile (§7): 56 min high, padding 8 × 12, radius 8, the disc fill, 2 px border (accent and the selected fill
 * when current); row 1 name, badge, latency; row 2 the 12/16 source, 2 below.
 */
@Composable
fun DoonaNodeTile(
    name: String,
    latencyMs: Int?,
    current: Boolean,
    groupLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    source: String? = null,
) {
    val c = DoonaTheme.colors
    val space = DoonaTheme.spacing
    val shape = DoonaTheme.shape.control
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.nodeTileMin)
            .clip(shape)
            .background(if (current) c.navPill else c.discOnCard)
            .border(DoonaTheme.size.borderStrong, if (current) c.positive else Color.Transparent, shape)
            .selectable(current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = space.fillInset, vertical = space.item),
        verticalArrangement = Arrangement.spacedBy(space.lineGap, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DoonaNodeName(name, DoonaTheme.type.bodyStrong, modifier = Modifier.weight(1f))
            if (groupLabel != null) {
                Spacer(Modifier.width(space.item))
                DoonaBadge(groupLabel)
            }
            Spacer(Modifier.width(space.block))
            DoonaLatency(latencyMs)
        }
        source?.let { Text(scripted(it), style = DoonaTheme.type.footnote, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}
