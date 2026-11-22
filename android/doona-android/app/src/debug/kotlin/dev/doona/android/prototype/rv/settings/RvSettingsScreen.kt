package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvFloatingNav
import dev.doona.android.prototype.rv.RvNavItem
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rememberRvGlass
import dev.doona.android.prototype.rv.rvDotMap
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * The 設定 page in Revolut's settings language (§13, with the Security page's hero): the doona mark beside the title, two
 * round actions under it, then a card for each group of settings with a section title over it and, where it helps, a
 * footer under it. The large title collapses into the bar as the page scrolls. The state is the caller's, so the
 * switches turn; the rows that open something are not wired, and 主題 opens [onOpenTheme].
 */
@Composable
internal fun RvSettingsScreen(state: RvSettingsState, onChange: (RvSettingsState) -> Unit, onOpenTheme: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    Column(Modifier.fillMaxSize().background(Rv.colors.page)) {
        RvScrollPage(stringResource(R.string.hub_settings), insets, Modifier.weight(1f)) { titleBlock ->
            Hero(titleBlock, insets)
            Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
                ConnectionGroup(state, onChange)
                SubscriptionGroup(state, onChange)
                AppearanceGroup(state, onOpenTheme)
                AdvancedGroup()
                AboutGroup()
            }
            Spacer(Modifier.height(RvSettingsSpace.bottom))
        }
        RvFloatingNav(NAV, selected = NAV_SETTINGS, onSelect = { NOT_WIRED() }, rememberRvGlass(), collapsed = false, insets)
    }
}

/** The mark beside the title, centred on a faint dot texture that runs up behind the bar, and the two actions under it. */
@Composable
private fun Hero(titleBlock: Modifier, insets: WindowInsets) {
    val barAndStatus = with(LocalDensity.current) { insets.getTop(this).toDp() } + RvSettingsSize.bar
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = RvSettingsAlpha.HERO_DOTS }.rvDotMap(null, Rv.colors.dots, barAndStatus + RvSettingsSpace.mapDrop))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RvBarSpacer(insets)
            Spacer(Modifier.height(RvSettingsSpace.heroTop))
            Row(titleBlock, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.markGap)) {
                Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvSettingsSize.mark))
                RvSettingsText(stringResource(R.string.hub_settings), RvSettingsType.title, Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(RvSettingsSpace.titleToActions))
            Row(horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.actionGap)) {
                RvSettingsAction(R.drawable.rv_settings_ic_export, stringResource(R.string.rv_settings_export_diagnostics), NOT_WIRED, Modifier.width(RvSettingsSpace.actionColumn))
                RvSettingsAction(R.drawable.rv_settings_ic_update, stringResource(R.string.rv_settings_check_update), NOT_WIRED, Modifier.width(RvSettingsSpace.actionColumn))
            }
            Spacer(Modifier.height(RvSettingsSpace.heroBottom))
        }
    }
}

@Composable
private fun ConnectionGroup(state: RvSettingsState, onChange: (RvSettingsState) -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_connection))
    RvSettingsCard {
        RvSettingsSwitchRow(stringResource(R.string.rv_settings_auto_connect), R.drawable.rv_settings_ic_power, state.connectAtBoot, { onChange(state.copy(connectAtBoot = it)) })
        RvSettingsSwitchRow(stringResource(R.string.rv_settings_notify_drop), R.drawable.rv_settings_ic_notifications, state.notifyOnDrop, { onChange(state.copy(notifyOnDrop = it)) })
        RvSettingsNavRow(
            stringResource(R.string.rv_settings_per_app),
            R.drawable.rv_settings_ic_apps,
            NOT_WIRED,
            stringResource(R.string.rv_settings_per_app_value, state.proxiedApps),
        )
        RvSettingsSwitchRow(
            stringResource(R.string.rv_settings_bypass_lan),
            R.drawable.rv_settings_ic_lan,
            state.bypassLan,
            { onChange(state.copy(bypassLan = it)) },
            subtitle = stringResource(R.string.rv_settings_bypass_lan_sub),
        )
    }
    RvSettingsFooter(stringResource(R.string.rv_settings_connection_footer))
}

@Composable
private fun SubscriptionGroup(state: RvSettingsState, onChange: (RvSettingsState) -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_subscription))
    RvSettingsCard {
        RvSettingsSwitchRow(stringResource(R.string.rv_settings_auto_update), R.drawable.rv_settings_ic_autorenew, state.autoUpdate, { onChange(state.copy(autoUpdate = it)) })
        RvSettingsNavRow(
            stringResource(R.string.rv_settings_update_interval),
            R.drawable.rv_settings_ic_schedule,
            NOT_WIRED,
            stringResource(R.string.rv_settings_update_interval_value, state.updateHours),
        )
    }
}

@Composable
private fun AppearanceGroup(state: RvSettingsState, onOpenTheme: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_appearance))
    RvSettingsCard {
        RvSettingsNavRow(stringResource(R.string.rv_settings_theme), R.drawable.rv_settings_ic_palette, onOpenTheme, stringResource(state.appearance.mode.label))
        RvSettingsNavRow(stringResource(R.string.rv_settings_language), R.drawable.rv_settings_ic_language, NOT_WIRED, stringResource(R.string.rv_settings_language_value))
    }
}

@Composable
private fun AdvancedGroup() {
    RvSettingsSection(stringResource(R.string.rv_settings_group_advanced))
    RvSettingsCard {
        RvSettingsNavRow(stringResource(R.string.rv_settings_dns), R.drawable.rv_settings_ic_dns, NOT_WIRED, stringResource(R.string.rv_settings_dns_value))
        RvSettingsNavRow(stringResource(R.string.rv_settings_log_level), R.drawable.rv_settings_ic_description, NOT_WIRED, stringResource(R.string.rv_settings_log_level_value))
    }
}

@Composable
private fun AboutGroup() {
    RvSettingsSection(stringResource(R.string.rv_settings_group_about))
    RvSettingsCard {
        RvSettingsValueRow(stringResource(R.string.rv_settings_version), R.drawable.rv_settings_ic_info, stringResource(R.string.rv_settings_version_value))
        RvSettingsNavRow(stringResource(R.string.rv_settings_licenses), R.drawable.rv_settings_ic_copyright, NOT_WIRED)
    }
}

private val NAV = listOf(
    RvNavItem(R.drawable.rv_sym_space_dashboard, R.string.hub_activity),
    RvNavItem(R.drawable.rv_sym_account_tree, R.string.nav_policies),
    RvNavItem(R.drawable.rv_sym_settings, R.string.hub_settings),
)
private const val NAV_SETTINGS = 2

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvSettingsScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(rvSampleSettings()) }
    RvTheme(rvSystemScheme(dark), dark) { RvSettingsScreen(state, onChange = { state = it }, onOpenTheme = {}) }
}
