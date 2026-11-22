package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** Inputs: the buttons in every style and state, and the check rows. */
@Composable
internal fun InputsGallery() = GalleryPage("Inputs") {
    val space = DoonaTheme.spacing
    val add = stringResource(R.string.group_new_group)
    val cancel = stringResource(R.string.ui_dismiss)
    val edit = stringResource(R.string.policy_edit)
    // The buttons wrap onto a new line when a larger font leaves no room for them side by side.
    Section("DoonaButton: primary, secondary, text") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item), verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton(add, onClick = {})
            DoonaButton(cancel, onClick = {}, style = DoonaButtonStyle.Secondary)
            DoonaButton(edit, onClick = {}, style = DoonaButtonStyle.Text)
        }
    }
    Section("With an icon") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item), verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton(add, onClick = {}, icon = R.drawable.ic_add_circle)
            DoonaButton(edit, onClick = {}, style = DoonaButtonStyle.Secondary, icon = R.drawable.ic_edit)
            DoonaButton(stringResource(R.string.ui_search), onClick = {}, style = DoonaButtonStyle.Text, icon = R.drawable.ic_search)
        }
    }
    Section("Disabled") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item), verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton(add, onClick = {}, enabled = false)
            DoonaButton(cancel, onClick = {}, style = DoonaButtonStyle.Secondary, enabled = false)
            DoonaButton(edit, onClick = {}, style = DoonaButtonStyle.Text, enabled = false)
        }
    }
    Section("Busy") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item), verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton(add, onClick = {}, busy = true)
            DoonaButton(cancel, onClick = {}, style = DoonaButtonStyle.Secondary, busy = true)
            DoonaButton(edit, onClick = {}, style = DoonaButtonStyle.Text, icon = R.drawable.ic_edit, busy = true)
        }
    }
    Section("Long label, full width") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton("Add the subscription and test every node in the group", onClick = {}, Modifier.fillMaxWidth())
            DoonaButton("新增訂閱並立即測試群組裡的每一個節點的延遲與可用性", onClick = {}, Modifier.fillMaxWidth(), style = DoonaButtonStyle.Secondary, icon = R.drawable.ic_add_circle)
        }
    }
}
