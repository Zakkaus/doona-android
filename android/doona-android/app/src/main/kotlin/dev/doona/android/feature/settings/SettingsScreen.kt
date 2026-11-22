package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.PageTitle

/** Placeholder for the 設定 tab; the settings lane fills it. */
@Composable
fun SettingsScreen() {
    DoonaPage {
        PageTitle(stringResource(R.string.hub_settings))
    }
}
