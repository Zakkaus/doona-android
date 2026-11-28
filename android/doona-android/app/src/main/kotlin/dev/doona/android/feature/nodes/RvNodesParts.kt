package dev.doona.android.feature.nodes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRoundButton

/**
 * Revolut's floating 'Get started' with the round companion beside it ('Buy' and more): the library's [RvPrimaryButton]
 * and a 56 dp container disc with no shadow, both on one centre line (N06).
 */
@Composable
internal fun RvFloatingActions(label: String, onClick: () -> Unit, @DrawableRes icon: Int, iconLabel: String, onIcon: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RvNodesSize.ctaGap), verticalAlignment = Alignment.CenterVertically) {
        RvPrimaryButton(label, onClick, Modifier.weight(1f), enabled = enabled)
        RvRoundButton(icon, iconLabel, onIcon)
    }
}
