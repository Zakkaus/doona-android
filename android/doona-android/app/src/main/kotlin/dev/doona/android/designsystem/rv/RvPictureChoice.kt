package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * One of a few choices shown as pictures side by side, after Revolut's Appearance modes: [picture] (an [RvSwatch]), its
 * [label] and its radio, centred in a column; the whole column picks it with [rvPressSelect]'s layer in
 * [Rv.highlightRadius] corners. 主題's 模式 card puts three in a row, each `Modifier.weight(1f)`.
 */
@Composable
internal fun RvPictureChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, picture: @Composable () -> Unit) {
    Column(
        modifier
            .rvPressSelect(RoundedCornerShape(Rv.highlightRadius), selected, onClick)
            .padding(vertical = RvSettingsSpace.modePad),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        picture()
        Spacer(Modifier.height(RvSettingsSpace.modeGap))
        RvSettingsText(label, Rv.type.action, align = TextAlign.Center)
        Spacer(Modifier.height(RvSettingsSpace.modeGap))
        RvRadio(selected)
    }
}
