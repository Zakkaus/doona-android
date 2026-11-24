package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.RV_ROW_SUBTITLE_LINES
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAction
import dev.doona.android.prototype.rv.RvBannerSlot
import dev.doona.android.prototype.rv.RvBarSpacer
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvActionRowFit
import dev.doona.android.prototype.rv.rvSystemScheme

/** The versions 關於 names: the app's, the core's, and the honk revision the core is built on, short. */
@Immutable
internal data class RvAboutVersions(val app: String, val core: String, val honk: String)

/** The sample's versions: the app and the core at the build files' 0.1.0, honk at the core's pinned daeuniverse/honk revision. */
internal val rvSampleVersions = RvAboutVersions(app = "0.1.0", core = "0.1.0", honk = "8919784")

/**
 * Where 關於 leads: the repository in the browser, 回報問題, the update check and a newer release's GitHub page, the
 * licences and the privacy page. None downloads anything; the repository and the release open in the browser.
 */
internal class RvAboutRoutes(
    val github: () -> Unit = NOT_WIRED,
    val report: () -> Unit = NOT_WIRED,
    val checkUpdate: () -> Unit = NOT_WIRED,
    val release: () -> Unit = NOT_WIRED,
    val licenses: () -> Unit = NOT_WIRED,
    val privacy: () -> Unit = NOT_WIRED,
)

/**
 * Layout A: 設定's 關於 group as it is, 版本 over its [version] and the licences, with GitHub 專案 after them, a row that
 * opens the repository in the browser ([onGithub]).
 */
@Composable
internal fun RvAboutGroup(version: String, onLicenses: () -> Unit, onGithub: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_about))
    RvSettingsCard {
        RvSettingsValueRow(stringResource(R.string.rv_settings_version), R.drawable.rv_settings_ic_info, version)
        RvSettingsNavRow(stringResource(R.string.rv_settings_licenses), R.drawable.rv_sym_license, onLicenses)
        RvSettingsNavRow(stringResource(R.string.rv_about_github), R.drawable.rv_sym_link, onGithub)
    }
}

/** Layout B: 設定's 關於 group as one row, 關於 doona over the app's [version], that opens [RvAboutScreen]. */
@Composable
internal fun RvAboutEntry(version: String, onOpen: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_about))
    RvSettingsCard { RvSettingsNavRow(stringResource(R.string.rv_about_title), R.drawable.rv_settings_ic_info, onOpen, version) }
}

/**
 * 關於 doona, the page layout B's row opens: 設定's hero form on a pushed page (the mark beside the centred name, its
 * title collapsing into the bar beside the back disc), with the [versions] under the name, then three round actions,
 * GitHub, 回報問題 and 檢查更新, and one card of the licences and the privacy page, as Revolut's Security page sets its
 * mark, title, round actions and cards (§13.3). Where the [update] check stands shows on its action and, when it needs
 * the user, in the page's banner over the card ([rvUpdateNotice]).
 */
@Composable
internal fun RvAboutScreen(versions: RvAboutVersions, onBack: () -> Unit, routes: RvAboutRoutes = RvAboutRoutes(), update: RvUpdate = RvUpdate.Idle) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(stringResource(R.string.rv_about_title), insets, onBack = onBack) { titleBlock ->
        Hero(titleBlock, insets, versions, routes, update)
        // No title over the card: it follows the round actions as Revolut's first card follows its hero's (§5).
        Spacer(Modifier.height(RvSpace.actionsToCard))
        RvBannerSlot(rvUpdateNotice(update, versions.app, routes.release, routes.checkUpdate))
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            RvSettingsCard {
                RvSettingsNavRow(stringResource(R.string.rv_settings_licenses), R.drawable.rv_sym_license, routes.licenses)
                RvSettingsNavRow(stringResource(R.string.rv_about_privacy), R.drawable.rv_sym_vpn_lock, routes.privacy)
            }
        }
        Spacer(Modifier.height(RvSettingsSpace.bottom))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/**
 * 設定's hero without its dot map: the mark beside the centred name ([titleBlock]), the version line under it as a
 * page header's caption, and the round actions as far under that block as 設定's are under its title, their labels one
 * size ([rvActionRowFit]). While the [update] check runs, its disc holds the busy bar.
 */
@Composable
private fun Hero(titleBlock: Modifier, insets: WindowInsets, versions: RvAboutVersions, routes: RvAboutRoutes, update: RvUpdate) {
    Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        RvBarSpacer(insets)
        Spacer(Modifier.height(RvSettingsSpace.heroTop))
        Row(titleBlock, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.markGap)) {
            Image(painterResource(R.drawable.ic_duck), contentDescription = null, Modifier.size(RvSettingsSize.mark))
            RvSettingsText(stringResource(R.string.app_name), Rv.type.largeTitle, Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(RvSpace.captionGap))
        RvText(
            stringResource(R.string.rv_about_versions, versions.app, versions.core, versions.honk),
            Rv.type.rowSub,
            color = Rv.colors.textSecondary,
            align = TextAlign.Center,
            maxLines = RV_ROW_SUBTITLE_LINES,
        )
        Spacer(Modifier.height(RvSettingsSpace.titleToActions))
        val labels = listOf(
            stringResource(R.string.rv_about_github_action),
            stringResource(R.string.rv_about_report_action),
            rvUpdateLabel(update),
        )
        val style = rvActionRowFit(labels, RvSettingsSpace.actionColumn)
        val column = Modifier.width(RvSettingsSpace.actionColumn)
        Row(horizontalArrangement = Arrangement.spacedBy(RvSettingsSpace.actionGap)) {
            RvAction(R.drawable.rv_sym_link, labels[0], routes.github, column, labelStyle = style)
            RvAction(R.drawable.rv_sym_ios_share, labels[1], routes.report, column, labelStyle = style)
            RvAction(R.drawable.rv_settings_ic_update, labels[2], routes.checkUpdate, column, busy = update == RvUpdate.Checking, labelStyle = style)
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvAboutScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvAboutScreen(rvSampleVersions, onBack = {}) }
}
