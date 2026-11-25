package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBanner
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvButtonPair
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED

/** Where the report stands: being written, written with the [RvRedaction] it hides and ready to share, or not written. */
@Immutable
internal sealed interface RvReportBuild {
    data object Building : RvReportBuild

    data class Ready(val redaction: RvRedaction) : RvReportBuild

    data object Failed : RvReportBuild
}

/**
 * What the report hides on top of what it always removes (subscription links, tokens): node addresses and ports, the
 * phone's own IPs, the domains it visited, and which app opened each connection. The first three start hidden, the app
 * names shown (the owner, owner-fix2 item 11).
 */
@Immutable
internal data class RvRedaction(val nodes: Boolean = true, val ip: Boolean = true, val domains: Boolean = true, val apps: Boolean = false)

/** What the report holds, as its preview lists it: the app's version, the phone, the settings in counts, and the log. */
@Immutable
internal data class RvReportPreview(
    val app: String,
    val device: String,
    val nodes: Int,
    val subscriptions: Int,
    val rules: Int,
    val events: Int,
    val crashes: Int,
)

/** The sample's report: the app of [rvSampleVersions], the sample data's counts, the last 200 events, 2 crashes. */
internal val rvSampleReport = RvReportPreview("0.1.0", "Pixel 8 · Android 15", nodes = 12, subscriptions = 2, rules = 14, events = 200, crashes = 2)

/** What the report page's actions do: open the prefilled issue, hand the file to the share sheet, write it again. */
internal class RvReportActions(
    val github: () -> Unit = NOT_WIRED,
    val share: () -> Unit = NOT_WIRED,
    val retry: () -> Unit = NOT_WIRED,
)

/**
 * 回報問題, the one report page (設定's hero and 進階 row, 關於, and 活動's crash notice open it): the large title; after
 * a failed build the library's banner with 重試; while the report is written, a busy row on top, as 依 App shows 正在儲存;
 * either stands a section's gap above 分享的內容, the preview, a plain list of values as the licences are; then 遮蔽, a switch for each of the four
 * things it can hide with one line of what that is, and under them two footnotes: the more is hidden, the less likely
 * the problem can be answered; and subscription links and tokens always go. The two actions stand at the foot as the
 * library's pair, one size ([RvButtonPair]): 在 GitHub 回報 opens a prefilled issue the user attaches the file to,
 * 分享檔案 hands it to the share sheet; both wait for the report. A report hides what the switches said when it was
 * written ([RvReportBuild.Ready]): once they say otherwise it is not shared, and the page shows it being written again,
 * as the caller does on each [onRedaction].
 */
@Composable
internal fun RvReportScreen(
    build: RvReportBuild,
    preview: RvReportPreview,
    redaction: RvRedaction,
    onRedaction: (RvRedaction) -> Unit,
    onBack: () -> Unit,
    actions: RvReportActions = RvReportActions(),
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_report_title)
    val ready = build is RvReportBuild.Ready && build.redaction == redaction
    val building = build == RvReportBuild.Building || (build is RvReportBuild.Ready && !ready)
    RvScrollPage(title, insets, onBack = onBack, bottomAction = { Actions(ready, actions) }) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        val banner = failure(build, actions.retry)
        // The banner stands where the first section title stood, which then keeps its own gap above it.
        banner?.let { RvBanner(it, Modifier.padding(horizontal = RvSpace.gutter)) }
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
            if (building) {
                RvSettingsCard {
                    RvSettingsRow(stringResource(R.string.rv_report_building), icon = R.drawable.rv_stats_ic_sync) { RvBusy(Modifier.width(RvSize.busy)) }
                }
            }
            RvSettingsSection(stringResource(R.string.rv_report_shared), first = banner == null && !building)
            Contents(preview)
            RvSettingsSection(stringResource(R.string.rv_report_redact))
            Redaction(redaction, onRedaction)
            // One paragraph, so it runs to the cards' right edge as 設定's connection footer does (owner-fix3 item 29); what a
            // report never holds is never cut, however many lines it takes.
            RvFootnote(stringResource(R.string.rv_redact_footer), inset = RvSpace.gutter - RvSettingsSpace.margin, whole = true)
        }
        Spacer(Modifier.height(RvSettingsSpace.bottom))
    }
}

/** The preview: each part of the report over what it holds, with no glyph, as a list of values. */
@Composable
private fun Contents(preview: RvReportPreview) {
    val settings = listOf(
        pluralStringResource(R.plurals.rv_nodes_nodes, preview.nodes, preview.nodes),
        pluralStringResource(R.plurals.rv_stats_subscriptions, preview.subscriptions, preview.subscriptions),
        pluralStringResource(R.plurals.rv_report_rules, preview.rules, preview.rules),
    )
    val log = listOf(
        pluralStringResource(R.plurals.rv_report_events, preview.events, preview.events),
        pluralStringResource(R.plurals.rv_report_crashes, preview.crashes, preview.crashes),
    )
    RvSettingsCard {
        // The app's version alone: the core ships in the same APK, so its version is the app's.
        RvSettingsRow(stringResource(R.string.rv_settings_version), subtitle = preview.app, subtitleKeepWords = false)
        RvSettingsRow(stringResource(R.string.rv_report_device), subtitle = preview.device, subtitleKeepWords = false)
        RvSettingsRow(stringResource(R.string.rv_report_settings), subtitle = settings.joinToString(SEPARATOR))
        RvSettingsRow(stringResource(R.string.rv_report_log), subtitle = log.joinToString(SEPARATOR))
    }
}

@Composable
private fun Redaction(redaction: RvRedaction, onChange: (RvRedaction) -> Unit) {
    RvSettingsCard {
        RvSettingsSwitchRow(
            stringResource(R.string.rv_redact_nodes),
            R.drawable.rv_sym_dns,
            redaction.nodes,
            { onChange(redaction.copy(nodes = it)) },
            stringResource(R.string.rv_redact_nodes_sub),
        )
        RvSettingsSwitchRow(stringResource(R.string.rv_redact_ip), R.drawable.rv_sym_vpn_lock, redaction.ip, { onChange(redaction.copy(ip = it)) }, stringResource(R.string.rv_redact_ip_sub))
        RvSettingsSwitchRow(
            stringResource(R.string.rv_redact_domains),
            R.drawable.rv_settings_ic_language,
            redaction.domains,
            { onChange(redaction.copy(domains = it)) },
            stringResource(R.string.rv_redact_domains_sub),
        )
        RvSettingsSwitchRow(stringResource(R.string.rv_redact_apps), R.drawable.rv_sym_apps, redaction.apps, { onChange(redaction.copy(apps = it)) }, stringResource(R.string.rv_redact_apps_sub))
    }
}

/**
 * The two actions as the library's pair, one size, the main one over the other; neither takes a tap until the report is
 * [ready], written with the redaction the switches show. The pair floats over the page as it scrolls, so it is opaque
 * on the page's colour.
 */
@Composable
private fun Actions(ready: Boolean, actions: RvReportActions) {
    RvButtonPair(stringResource(R.string.rv_report_github), actions.github, stringResource(R.string.rv_report_share), actions.share, enabled = ready, over = Rv.colors.page)
}

/** The banner of a build that failed: the export's own words, and 重試. */
@Composable
private fun failure(build: RvReportBuild, onRetry: () -> Unit): RvNotice? = if (build == RvReportBuild.Failed) {
    RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_report_failed), stringResource(R.string.rv_report_failed_reason), stringResource(R.string.rv_retry), onRetry)
} else {
    null
}

/** The parts of one value line; the library keeps each part whole when the line wraps. */
private const val SEPARATOR = " · "

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvReportScreenPreview() {
    val dark = isSystemInDarkTheme()
    var redaction by remember { mutableStateOf(RvRedaction()) }
    RvTheme(rvSystemScheme(dark), dark) { RvReportScreen(RvReportBuild.Ready(redaction), rvSampleReport, redaction, { redaction = it }, onBack = {}) }
}
