package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED

/**
 * The versions 關於 names: the app's, and the honk revision its proxy engine is built on, short. The core is not named:
 * it ships in the same APK, so its version is always the app's (owner-fix2 item 17).
 */
@Immutable
internal data class RvAboutVersions(val app: String, val honk: String)

/** Where 關於 leads: the repository in the browser, 回報問題, the licences and the privacy page. */
internal class RvAboutRoutes(
    val github: () -> Unit = NOT_WIRED,
    val report: () -> Unit = NOT_WIRED,
    val licenses: () -> Unit = NOT_WIRED,
    val privacy: () -> Unit = NOT_WIRED,
)

/** 設定's 關於 group, its last: 關於 doona over the app's [version], which opens [RvAboutScreen]. */
@Composable
internal fun RvAboutEntry(version: String, onOpen: () -> Unit) {
    RvSettingsSection(stringResource(R.string.rv_settings_group_about))
    RvSettingsCard {
        RvSettingsNavRow(stringResource(R.string.rv_about_title), R.drawable.rv_settings_ic_info, onOpen, version, subtitleKeepWords = false)
    }
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
                RvSettingsRow(stringResource(R.string.app_name), icon = R.drawable.rv_settings_ic_info, subtitle = versions.app, subtitleKeepWords = false)
                RvSettingsRow(stringResource(R.string.rv_about_honk), icon = R.drawable.rv_sym_memory, subtitle = stringResource(R.string.rv_about_honk_sub, versions.honk))
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
