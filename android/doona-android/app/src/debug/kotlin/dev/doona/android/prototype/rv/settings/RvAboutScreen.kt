package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED

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

/** 設定's 關於 group: one row, 關於 doona over the app's [version], that opens [RvAboutScreen]. */
@Composable
internal fun RvAboutEntry(version: String, onOpen: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_about))
    RvSettingsCard { RvSettingsNavRow(stringResource(R.string.rv_about_title), R.drawable.rv_settings_ic_info, onOpen, version) }
}

/**
 * 關於 doona, a page of 設定 in the other pages' form: the large title, then three cards [RvSettingsSpace.cardGap] apart
 * with no section title, as Revolut's About us (§13.3): the [versions] as a list of values; GitHub 專案, 回報問題 and
 * 檢查更新; the licences and the privacy page. Where the [update] check stands shows on its row and, when it needs the
 * user, in the page's banner under the title ([rvUpdateNotice]).
 */
@Composable
internal fun RvAboutScreen(versions: RvAboutVersions, onBack: () -> Unit, routes: RvAboutRoutes = RvAboutRoutes(), update: RvUpdate = RvUpdate.Idle) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_about_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(rvUpdateNotice(update, versions.app, routes.release, routes.checkUpdate))
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin), verticalArrangement = Arrangement.spacedBy(RvSettingsSpace.cardGap)) {
            // No glyph: a list of values, as the licences and 回報問題's 分享的內容.
            RvSettingsCard {
                RvSettingsRow(stringResource(R.string.rv_settings_version), subtitle = versions.app)
                RvSettingsRow(stringResource(R.string.rv_about_core), subtitle = versions.core)
                RvSettingsRow(stringResource(R.string.rv_about_honk), subtitle = versions.honk)
            }
            RvSettingsCard {
                RvSettingsNavRow(stringResource(R.string.rv_about_github), R.drawable.rv_sym_github, routes.github)
                RvSettingsNavRow(stringResource(R.string.rv_report_title), R.drawable.rv_sym_ios_share, routes.report)
                UpdateRow(update, routes.checkUpdate)
            }
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
 * 檢查更新 as a row: while it asks, the busy bar at its end and no tap, as 回報問題's 匯出診斷報告 while it exports; once
 * nothing is newer, 已是最新 as its second line; otherwise a row that starts the check ([onCheck]).
 */
@Composable
private fun UpdateRow(update: RvUpdate, onCheck: () -> Unit) {
    val label = stringResource(R.string.rv_settings_check_update)
    when (update) {
        RvUpdate.Checking -> RvSettingsRow(label, icon = R.drawable.rv_settings_ic_update) { RvBusy(Modifier.width(RvSize.busy)) }
        RvUpdate.Latest -> RvSettingsNavRow(label, R.drawable.rv_settings_ic_update, onCheck, stringResource(R.string.rv_update_latest))
        else -> RvSettingsNavRow(label, R.drawable.rv_settings_ic_update, onCheck)
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvAboutScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvAboutScreen(rvSampleVersions, onBack = {}) }
}
