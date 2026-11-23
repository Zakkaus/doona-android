package dev.doona.android.feature.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.AppPolicy

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsScreenPreview() {
    DoonaTheme { SettingsScreen(SettingsUiState(AppPolicy.OnlyListed), version = "0.1", onOpenPerApp = {}, onOpenSubscriptions = {}) }
}
