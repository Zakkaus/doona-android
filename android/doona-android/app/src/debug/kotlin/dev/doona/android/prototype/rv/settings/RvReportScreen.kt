package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBusy
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvMenuAnchor
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme

/** A level the core logs at (diagnostics `setLogLevel`): its name, and its glyph in the level menu. */
internal enum class RvLogLevel(@StringRes val label: Int, @DrawableRes val icon: Int) {
    Error(R.string.rv_log_error, R.drawable.rv_sym_error),
    Warn(R.string.rv_log_warn, R.drawable.rv_nodes_ic_warning),
    Info(R.string.rv_log_info, R.drawable.rv_sym_info),
    Debug(R.string.rv_log_debug, R.drawable.rv_settings_ic_description),
}

/** Where an export stands: none running, writing the report, or the last one failed. */
internal enum class RvExport { Idle, Exporting, Failed }

/**
 * 回報問題, a page of 設定, in DNS's language: the large title under the back bar; after a failed export the library's
 * notice card, with 重試; then one card of two rows. 日誌等級 shows its [level] as the row's second line and opens the
 * library's menu of the four levels under the row's end; 匯出診斷報告 writes the report and hands it to the share sheet.
 * While it writes, the busy bar stands where the chevron was and the row takes no tap, as a subscription row that is
 * updating.
 */
@Composable
internal fun RvReportScreen(level: RvLogLevel, export: RvExport, onLevel: (RvLogLevel) -> Unit, onExport: () -> Unit, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_report_title)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        val failed = if (export == RvExport.Failed) {
            RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_report_failed), stringResource(R.string.rv_report_failed_reason), stringResource(R.string.rv_retry), onExport)
        } else {
            null
        }
        RvGutter(banner = failed) {
            RvSettingsCard {
                LevelRow(level, onLevel)
                ExportRow(export, onExport)
            }
        }
    }
}

/** 日誌等級: a tap opens the four levels under the row's end; TalkBack offers each as an action on the row. */
@Composable
private fun LevelRow(level: RvLogLevel, onLevel: (RvLogLevel) -> Unit) {
    val items = RvLogLevel.entries.map { RvMenuItem(stringResource(it.label), it.icon) { onLevel(it) } }
    RvMenuAnchor(items) { open, actions ->
        Box(actions) {
            RvSettingsNavRow(stringResource(R.string.rv_settings_log_level), R.drawable.rv_settings_ic_description, open ?: {}, stringResource(level.label))
        }
    }
}

@Composable
private fun ExportRow(export: RvExport, onExport: () -> Unit) {
    val label = stringResource(R.string.rv_report_export)
    if (export == RvExport.Exporting) {
        RvSettingsRow(label, icon = R.drawable.rv_sym_ios_share) { RvBusy(Modifier.width(RvSize.busy)) }
    } else {
        RvSettingsNavRow(label, R.drawable.rv_sym_ios_share, onExport)
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvReportScreenPreview() {
    val dark = isSystemInDarkTheme()
    var level by remember { mutableStateOf(RvLogLevel.Info) }
    RvTheme(rvSystemScheme(dark), dark) { RvReportScreen(level, RvExport.Failed, onLevel = { level = it }, onExport = {}, onBack = {}) }
}
