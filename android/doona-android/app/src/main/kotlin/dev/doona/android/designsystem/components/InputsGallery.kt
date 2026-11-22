package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** Inputs: the buttons in every style and state, the app icon and the check rows. */
@Composable
internal fun InputsGallery() = GalleryPage("Inputs") {
    val space = DoonaTheme.spacing
    val add = stringResource(R.string.group_new_group)
    val cancel = stringResource(R.string.ui_dismiss)
    val edit = stringResource(R.string.policy_edit)
    val remove = "Remove"
    // The buttons wrap onto a new line when a larger font leaves no room for them side by side.
    Section("DoonaButton: primary, secondary, text, destructive") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item), verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton(add, onClick = {})
            DoonaButton(cancel, onClick = {}, style = DoonaButtonStyle.Secondary)
            DoonaButton(edit, onClick = {}, style = DoonaButtonStyle.Text)
            DoonaButton(remove, onClick = {}, style = DoonaButtonStyle.Destructive)
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
            DoonaButton(remove, onClick = {}, style = DoonaButtonStyle.Destructive, enabled = false)
        }
    }
    Section("Busy") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item), verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton(add, onClick = {}, busy = true)
            DoonaButton(cancel, onClick = {}, style = DoonaButtonStyle.Secondary, busy = true)
            DoonaButton(edit, onClick = {}, style = DoonaButtonStyle.Text, icon = R.drawable.ic_edit, busy = true)
            DoonaButton(remove, onClick = {}, style = DoonaButtonStyle.Destructive, busy = true)
        }
    }
    Section("Long label, full width") {
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaButton("Add the subscription and test every node in the group", onClick = {}, Modifier.fillMaxWidth())
            DoonaButton("新增訂閱並立即測試群組裡的每一個節點的延遲與可用性", onClick = {}, Modifier.fillMaxWidth(), style = DoonaButtonStyle.Secondary, icon = R.drawable.ic_add_circle)
        }
    }
    Section("DoonaCheckRow: checked, unchecked, no subtitle") {
        DoonaCheckRow(checked = true, onChange = {}, title = "Chrome", subtitle = "com.android.chrome", leading = { SampleAppIcon() })
        DoonaCheckRow(checked = false, onChange = {}, title = "Telegram", subtitle = "org.telegram.messenger", leading = { SampleAppIcon() })
        DoonaCheckRow(checked = true, onChange = {}, title = "計算機", leading = { SampleAppIcon() })
        DoonaCheckRow(checked = false, onChange = {}, title = "Without an icon")
    }
    // The screenshot tests run on a device with no apps, so this is the box every row shows until its icon has loaded.
    Section("DoonaAppIcon: the placeholder, for an icon still loading or a package that is not installed") {
        DoonaAppIcon("com.example.not.installed")
    }
    Section("DoonaCheckRow: long text") {
        DoonaCheckRow(
            checked = true,
            onChange = {},
            title = "A very long application name that cannot fit on a single line at any size",
            subtitle = "com.example.verylongapplicationidentifier.that.runs.past.the.edge",
            leading = { SampleAppIcon() },
        )
        DoonaCheckRow(checked = false, onChange = {}, title = "名稱很長很長很長很長很長很長很長很長很長很長的應用程式", subtitle = "com.example.長", leading = { SampleAppIcon() })
    }
}

/** A stand-in for an app's launcher icon: a rounded square the size of one. */
@Composable
private fun SampleAppIcon() {
    val c = DoonaTheme.colors
    Box(Modifier.size(DoonaTheme.size.appIcon).clip(DoonaTheme.shape.control).background(c.control), contentAlignment = Alignment.Center) {
        DoonaIcon(R.drawable.ic_link, c.icon)
    }
}
