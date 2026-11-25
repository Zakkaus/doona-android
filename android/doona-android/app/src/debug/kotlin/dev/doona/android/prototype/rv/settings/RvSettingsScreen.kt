package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NAV_SETTINGS
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAction
import dev.doona.android.designsystem.rv.RvBanner
import dev.doona.android.designsystem.rv.RvBarSpacer
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvInfoSheet
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvActionRowFit
import dev.doona.android.designsystem.rv.rvBarHeight
import dev.doona.android.designsystem.rv.rvDotMap
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.prototype.rv.rvHeroArt

/**
 * The 設定 tab in Revolut's settings language (§13, with the Security page's hero), the owner's S1 (rv-fix-4e): the
 * doona mark beside the centred title on a faint dot map, two round actions under it, then a card for each group of
 * settings with a section title over it and, where it helps, a footer under it, all scrolling under the floating nav.
 * There is no search capsule; the title collapses into the bar as the page scrolls. The state is the caller's, so the
 * switches turn; the rows that open something are not wired, 主題 opens [onOpenTheme] and the nav's tabs [onTab].
 * [configRev] is a preview flag (PV-f): set, 進階 gains 設定檔 with the applied revision, which opens the configuration's
 * overview ([RvSettingsRoutes.config]). Just before 關於, the last group, 更新 holds 檢查更新 and the GeoIP/GeoSite row
 * ([RvUpdateGroup]; [geoData], whose 更新 runs [RvSettingsRoutes.geoUpdate]). Where the [update] check stands shows on
 * the hero's 檢查更新 action, on its row and, for a newer release, in the banner under the hero ([rvUpdateNotice]); a
 * failed check only on its row. Over the page, the sheet that tells how to add the quick settings tile by
 * hand while the state holds it open.
 */
@Composable
internal fun RvSettingsScreen(
    state: RvSettingsState,
    onChange: (RvSettingsState) -> Unit,
    onOpenTheme: () -> Unit,
    onTab: (Int) -> Unit = {},
    routes: RvSettingsRoutes = RvSettingsRoutes(),
    configRev: Int? = null,
    geoData: RvGeoData,
    update: RvUpdate = RvUpdate.Idle,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    RvScrollPage(
        stringResource(R.string.hub_settings),
        insets,
        scroll = scroll,
        overlay = { glass -> RvFloatingNav(RV_NAV_SETTINGS, onTab, glass, scroll, insets, Modifier.align(Alignment.BottomCenter)) },
        end = RvPageEnd.Nav,
    ) { titleBlock ->
        Hero(titleBlock, insets, routes, update)
        val version = stringResource(R.string.rv_settings_version_value)
        // The banner stands where the first section title would, which then keeps its own gap above it (as 回報問題's).
        rvUpdateNotice(update, version, routes.release)?.let {
            RvBanner(it, Modifier.padding(start = RvSpace.gutter, top = RvSpace.sectionAbove, end = RvSpace.gutter))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            ConnectionGroup(state, onChange, routes)
            SubscriptionGroup(state, onChange)
            AppearanceGroup(state, onOpenTheme, routes.language)
            AdvancedGroup(state, routes, configRev)
            RvUpdateGroup(version, update, routes.checkUpdate, geoData, routes.geoUpdate)
            RvAboutEntry(version, routes.about)
        }
    }
    val manual = stringResource(R.string.rv_settings_quick_tile_manual)
    RvInfoSheet(state.tileSheet, stringResource(R.string.rv_settings_quick_tile), manual, stringResource(R.string.rv_done)) { onChange(state.copy(tileSheet = false)) }
}

/**
 * The mark beside the centred title ([titleBlock]), on a faint dot texture that runs up behind the bar, and the two
 * round actions under it, their labels one size ([rvActionRowFit]); 匯出診斷 opens 回報問題, and 檢查更新 runs the
 * same check as 關於's row, drawn as [rvUpdateActionLabel], read as [rvUpdateLabel] (named by its [update]) and busy, deaf to a
 * second tap, while it asks.
 */
@Composable
private fun Hero(titleBlock: Modifier, insets: WindowInsets, routes: RvSettingsRoutes, update: RvUpdate) {
    val status = with(LocalDensity.current) { insets.getTop(this).toDp() }
    Box(Modifier.fillMaxWidth()) {
        // No dot on the status bar's clock (S8-08): the map starts under it, as 活動's does.
        val dots = Modifier.rvDotMap(null, Rv.colors.dots, status + rvBarHeight() + RvSettingsSpace.mapDrop, fadeTop = status)
        // After a hard-cut tab switch the dots fade in ([rvHeroArt]).
        val art = rvHeroArt()
        val mapTrim = RvSettingsSpace.titleToActions - RvSettingsSpace.mapTitleToActions
        Box(Modifier.matchParentSize().padding(bottom = mapTrim).graphicsLayer { alpha = RvSettingsAlpha.HERO_DOTS * art() }.then(dots))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RvBarSpacer(insets)
            Spacer(Modifier.height(RvSettingsSpace.heroTop))
            Row(titleBlock, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.markGap)) {
                Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvSettingsSize.mark))
                RvSettingsText(stringResource(R.string.hub_settings), Rv.type.largeTitle, Modifier.semantics { heading() })
            }
            Spacer(Modifier.height(RvSettingsSpace.titleToActions))
            val labels = listOf(stringResource(R.string.rv_settings_export_diagnostics), rvUpdateActionLabel(update))
            val style = rvActionRowFit(labels, RvSettingsSpace.actionColumn)
            val checking = update == RvUpdate.Checking
            Row(horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.actionGap)) {
                RvAction(R.drawable.rv_sym_ios_share, labels[0], routes.report, Modifier.width(RvSettingsSpace.actionColumn), labelStyle = style)
                val column = Modifier.width(RvSettingsSpace.actionColumn)
                RvAction(R.drawable.rv_settings_ic_update, labels[1], routes.checkUpdate, column, busy = checking, labelStyle = style, enabled = !checking, description = rvUpdateLabel(update))
            }
        }
    }
}

/**
 * Where 設定 leads beyond the theme: the system's VPN settings, where always-on is turned on (Android owns that switch, D14), 依 App, the DNS picker, the effective config, 回報問題 from 進階's row
 * and the hero's 匯出診斷, 關於 doona (which holds the licences), and the configuration's overview (with the preview flag); and 加到快速設定
 * ([addTile]), the system's dialog or the sheet that tells how; the GeoIP/GeoSite update ([geoUpdate], with its sample flag); and
 * the update check ([checkUpdate], the hero's action and 關於's row) and a newer release's GitHub page ([release]); and the
 * language page ([language]). The app downloads nothing: the release opens in the browser.
 */
internal class RvSettingsRoutes(
    val alwaysOn: () -> Unit = NOT_WIRED,
    val addTile: () -> Unit = NOT_WIRED,
    val perApp: () -> Unit = NOT_WIRED,
    val dns: () -> Unit = NOT_WIRED,
    val effectiveConfig: () -> Unit = NOT_WIRED,
    val report: () -> Unit = NOT_WIRED,
    val about: () -> Unit = NOT_WIRED,
    val config: () -> Unit = NOT_WIRED,
    val geoUpdate: () -> Unit = NOT_WIRED,
    val checkUpdate: () -> Unit = NOT_WIRED,
    val release: () -> Unit = NOT_WIRED,
    val language: () -> Unit = NOT_WIRED,
)

/**
 * 連線: always-on as a row that opens the system's VPN settings, since Android, not the app, turns it on; until the
 * quick settings tile is added, 加到快速設定 under it, the other row that hands over to the system, with 活動's power
 * glyph since the tile connects and disconnects as that action does; the drop notice; and the apps that use the tunnel,
 * which opens 依 App, kept last over the footnote that explains it. The LAN always stays outside the tunnel (D25), so
 * there is no switch for it.
 */
@Composable
private fun ConnectionGroup(state: RvSettingsState, onChange: (RvSettingsState) -> Unit, routes: RvSettingsRoutes) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_connection))
    RvSettingsCard {
        RvSettingsNavRow(
            stringResource(R.string.rv_settings_always_on),
            R.drawable.rv_sym_vpn_lock,
            routes.alwaysOn,
            subtitle = stringResource(R.string.rv_settings_always_on_sub),
        )
        if (!state.tileAdded) {
            RvSettingsNavRow(
                stringResource(R.string.rv_settings_quick_tile),
                R.drawable.rv_sym_power,
                routes.addTile,
                subtitle = stringResource(R.string.rv_settings_quick_tile_sub),
            )
        }
        RvSettingsSwitchRow(stringResource(R.string.rv_settings_notify_drop), R.drawable.rv_settings_ic_notifications, state.notifyOnDrop, { onChange(state.copy(notifyOnDrop = it)) })
        RvSettingsNavRow(stringResource(R.string.rv_settings_per_app), R.drawable.rv_sym_apps, routes.perApp, perAppValue(state.perApp))
    }
    RvFootnote(stringResource(R.string.rv_settings_connection_footer), inset = RvSpace.gutter - RvSettingsSpace.margin)
}

@Composable
private fun SubscriptionGroup(state: RvSettingsState, onChange: (RvSettingsState) -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_subscription))
    RvSettingsCard {
        RvSettingsSwitchRow(stringResource(R.string.rv_settings_auto_update), R.drawable.rv_sym_rss_feed, state.autoUpdate, { onChange(state.copy(autoUpdate = it)) })
        RvSettingsNavRow(
            stringResource(R.string.rv_settings_update_interval),
            R.drawable.rv_sym_schedule,
            NOT_WIRED,
            stringResource(R.string.rv_settings_update_interval_value, state.updateHours),
        )
    }
}

@Composable
private fun AppearanceGroup(state: RvSettingsState, onOpenTheme: () -> Unit, onOpenLanguage: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_appearance))
    RvSettingsCard {
        RvSettingsNavRow(stringResource(R.string.rv_settings_theme), R.drawable.rv_settings_ic_palette, onOpenTheme, stringResource(state.appearance.mode.label))
        RvSettingsNavRow(stringResource(R.string.rv_settings_language), R.drawable.rv_settings_ic_language, onOpenLanguage, stringResource(state.language.label))
    }
}

/**
 * 進階: the DNS server (the picker); 回報問題, the report page, with the hero's 匯出診斷
 * glyph; the effective config, read-only.
 */
@Composable
private fun AdvancedGroup(state: RvSettingsState, routes: RvSettingsRoutes, configRev: Int?) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_advanced))
    RvSettingsCard {
        RvSettingsNavRow(stringResource(R.string.rv_settings_dns), R.drawable.rv_sym_dns, routes.dns, stringResource(state.dns.label))
        RvSettingsNavRow(stringResource(R.string.rv_report_title), R.drawable.rv_sym_ios_share, routes.report)
        if (configRev != null) {
            RvSettingsNavRow(stringResource(R.string.rv_config_title), R.drawable.rv_sym_edit, routes.config, stringResource(R.string.rv_config_revision, configRev))
        }
        RvSettingsNavRow(stringResource(R.string.rv_effective_open), R.drawable.rv_sym_rule, routes.effectiveConfig)
    }
}

/** 依 App's policy as its row's second line: every app, or how many apps it takes or keeps out. */
@Composable
private fun perAppValue(perApp: RvPerApp): String = when (perApp.policy) {
    RvAppPolicy.All -> stringResource(R.string.perapp_policy_all)
    RvAppPolicy.Only -> stringResource(R.string.rv_settings_per_app_value, perApp.packages.size)
    RvAppPolicy.Except -> stringResource(R.string.rv_settings_per_app_except, perApp.packages.size)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvSettingsScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(rvSampleSettings()) }
    RvTheme(rvSystemScheme(dark), dark) { RvSettingsScreenSample(state, onChange = { state = it }, onOpenTheme = {}) }
}
