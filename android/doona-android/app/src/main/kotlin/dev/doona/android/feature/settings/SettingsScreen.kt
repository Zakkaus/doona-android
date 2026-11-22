package dev.doona.android.feature.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.ChevronDirection
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaChevron
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaRow
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.feature.labelRes

/** The 設定 entry: the version is read once, here, so the screen takes plain values. */
@Composable
fun SettingsRoute(engine: Engine, onOpenPerApp: () -> Unit, onOpenSubscriptions: () -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    SettingsScreen(state, version, onOpenPerApp, onOpenSubscriptions)
}

/** The app's version name, or a dash when the package manager does not give it. */
private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (_: PackageManager.NameNotFoundException) {
    null
} ?: NO_VALUE

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
            SettingCard(R.drawable.ic_filter, stringResource(R.string.perapp_title), stringResource(state.appPolicy.labelRes()), onOpenPerApp)
            SettingCard(R.drawable.ic_link, stringResource(R.string.settings_subscriptions), value = null, onOpenSubscriptions)
            SettingCard(R.drawable.ic_cpu, stringResource(R.string.settings_version), version, onClick = null)
        }
    }
}

/** An icon, the setting's name, its [value] and, when it opens a page ([onClick]), a chevron. */
@Composable
private fun SettingCard(@DrawableRes icon: Int, label: String, value: String?, onClick: (() -> Unit)?) {
    val c = DoonaTheme.colors
    val space = DoonaTheme.spacing
    DoonaCard(onClick = onClick) {
        DoonaRow {
            DoonaIcon(icon, c.icon)
            DoonaHGap(space.item)
            DoonaText(label, style = DoonaTheme.type.label, color = c.text.primary, maxLines = 1, modifier = Modifier.weight(1f))
            if (value != null) {
                DoonaHGap(space.block)
                DoonaText(value, style = DoonaTheme.type.body, color = c.text.secondary, maxLines = 1)
            }
            if (onClick != null) {
                DoonaHGap(space.block)
                DoonaChevron(ChevronDirection.Right, c.text.secondary)
            }
        }
    }
}
