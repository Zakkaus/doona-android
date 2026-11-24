package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.config.rvConfigLine

/**
 * The versions 關於 names: the app's, and the honk revision its proxy engine is built on, short. The core is not named:
 * it ships in the same APK, so its version is always the app's (owner-fix2 item 17).
 */
@Immutable
internal data class RvAboutVersions(val app: String, val honk: String)

/** The sample's versions: the app at the build files' 0.1.0, honk at the core's pinned daeuniverse/honk revision. */
internal val rvSampleVersions = RvAboutVersions(app = "0.1.0", honk = "8919784")

/** Where 關於 leads: the repository in the browser, 回報問題, the licences and the privacy page. */
internal class RvAboutRoutes(
    val github: () -> Unit = NOT_WIRED,
    val report: () -> Unit = NOT_WIRED,
    val licenses: () -> Unit = NOT_WIRED,
    val privacy: () -> Unit = NOT_WIRED,
)

/**
 * 設定's 關於 group: 關於 doona over the app's [version], which opens [RvAboutScreen], then 檢查更新 ([RvUpdateRow]),
 * which checks that same version, so the two rows about the app's version stand together (owner-fix2 item 18).
 */
@Composable
internal fun RvAboutEntry(version: String, onOpen: () -> Unit, update: RvUpdate = RvUpdate.Idle, onCheck: () -> Unit = NOT_WIRED) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_about))
    RvSettingsCard {
        RvSettingsNavRow(stringResource(R.string.rv_about_title), R.drawable.rv_settings_ic_info, onOpen, version)
        RvUpdateRow(update, version, onCheck)
    }
}

/**
 * 檢查更新 as a row, the whole row the target: the app's [version] as its second line, then where the check stands,
 * after the dot: nothing yet, 已是最新, the newer version, or 無法檢查更新 in the error hue (as 設定's GeoIP／GeoSite
 * row says its failure); the row checks again ([onCheck]). While it asks, the busy bar at its end and no tap, as
 * 回報問題's busy row while it writes. What needs the user (a newer release, no answer) is also 設定's banner.
 */
@Composable
internal fun RvUpdateRow(update: RvUpdate, version: String, onCheck: () -> Unit) {
    val label = stringResource(R.string.rv_settings_check_update)
    // Each part whole, so a line that wraps at a large font size breaks after the dot, never inside 已是最新.
    val line = rvConfigLine(listOfNotNull(version, updateState(update)))
    if (update == RvUpdate.Checking) {
        RvSettingsRow(label, icon = R.drawable.rv_settings_ic_update, subtitle = line) { RvBusy(Modifier.width(RvSize.busy)) }
    } else {
        val press = Modifier.rvRowPress(Role.Button, onCheck)
        RvSettingsRow(label, press, R.drawable.rv_settings_ic_update, subtitle = line, error = update == RvUpdate.Failed) { RvNavEnd(null) }
    }
}

/** What follows the version on 檢查更新's second line. */
@Composable
private fun updateState(update: RvUpdate): String? = when (update) {
    RvUpdate.Latest -> stringResource(R.string.rv_update_latest)
    is RvUpdate.Available -> stringResource(R.string.rv_update_new, update.version)
    RvUpdate.Failed -> stringResource(R.string.rv_update_failed)
    RvUpdate.Idle, RvUpdate.Checking -> null
}

/**
 * 關於 doona, a page of 設定 in the other pages' form: the large title, then three cards [RvSettingsSpace.cardGap] apart
 * with no section title, as Revolut's About us (§13.3): the [versions], doona over the app's and honk over what it is
 * and its revision, each with its glyph as the other rows; GitHub 專案 and 回報問題; the licences and the privacy page.
 * 檢查更新 is on 設定 itself ([RvAboutEntry]).
 */
@Composable
internal fun RvAboutScreen(versions: RvAboutVersions, onBack: () -> Unit, routes: RvAboutRoutes = RvAboutRoutes()) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_about_title)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin), verticalArrangement = Arrangement.spacedBy(RvSettingsSpace.cardGap)) {
            RvSettingsCard {
                RvSettingsRow(stringResource(R.string.app_name), icon = R.drawable.rv_settings_ic_info, subtitle = versions.app)
                RvSettingsRow(stringResource(R.string.rv_about_honk), icon = R.drawable.rv_sym_bolt, subtitle = stringResource(R.string.rv_about_honk_sub, versions.honk))
            }
            RvSettingsCard {
                RvSettingsNavRow(stringResource(R.string.rv_about_github), R.drawable.rv_sym_github, routes.github)
                RvSettingsNavRow(stringResource(R.string.rv_report_title), R.drawable.rv_sym_ios_share, routes.report)
            }
            RvSettingsCard {
                RvSettingsNavRow(stringResource(R.string.rv_settings_licenses), R.drawable.rv_sym_license, routes.licenses)
                RvSettingsNavRow(stringResource(R.string.rv_about_privacy), R.drawable.rv_sym_vpn_lock, routes.privacy)
            }
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
