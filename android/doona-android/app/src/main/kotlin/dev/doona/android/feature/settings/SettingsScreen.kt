package dev.doona.android.feature.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaNavRow
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaValueRow
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.feature.labelRes
import dev.doona.android.icons.R as IconsR

/** The 設定 entry; [version] is the app's version name, which the shell reads once and passes on. */
@Composable
fun SettingsRoute(engine: Engine, version: String, onOpenPerApp: () -> Unit, onOpenSubscriptions: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(state, version, onOpenPerApp, onOpenSubscriptions)
}

private const val NO_VALUE = "—"

/**
 * 設定: one card per setting, a block apart. 依 App and 訂閱 open their pages; the version is only shown. Nothing else
 * is set in v0.1.
 */
@Composable
fun SettingsScreen(state: SettingsUiState, version: String, onOpenPerApp: () -> Unit, onOpenSubscriptions: () -> Unit) {
    DoonaPage {
        DoonaPageTitle(stringResource(R.string.hub_settings))
        DoonaGap(DoonaTheme.spacing.afterTitle)
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
            SettingCard(IconsR.drawable.sym_filter_alt, stringResource(R.string.perapp_title), stringResource(state.appPolicy.labelRes()), onOpenPerApp)
            SettingCard(IconsR.drawable.sym_link, stringResource(R.string.settings_subscriptions), value = null, onOpenSubscriptions)
            SettingCard(IconsR.drawable.sym_memory, stringResource(R.string.settings_version), version.ifBlank { NO_VALUE }, onClick = null)
        }
    }
}

/** A card of one settings row: the setting's [icon] and name, its [value], and, when it opens a page ([onClick]), a chevron. */
@Composable
private fun SettingCard(@DrawableRes icon: Int, label: String, value: String?, onClick: (() -> Unit)?) {
    DoonaCard {
        if (onClick != null) DoonaNavRow(label, onClick, icon = icon, value = value) else DoonaValueRow(label, value ?: NO_VALUE, icon = icon)
    }
}
