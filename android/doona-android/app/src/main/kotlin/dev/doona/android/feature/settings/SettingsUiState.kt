package dev.doona.android.feature.settings

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Settings

/** What the 設定 screen shows of the engine: the policy of per-app routing, the one setting that opens a page of its own. */
@Immutable
data class SettingsUiState(val appPolicy: AppPolicy = AppPolicy.All)

fun settingsUiState(settings: Settings) = SettingsUiState(appPolicy = settings.perApp.policy)
