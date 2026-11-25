package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSettingsRadioRow
import dev.doona.android.designsystem.rv.RvSettingsSpace

/**
 * 日誌等級, a page of 設定, in DNS's language: the large title, then one card of the four levels as radio rows, 資訊
 * over 預設, and one footer line: 除錯 returns to 資訊 by itself after [RV_DEBUG_HOURS]. No row counts the time down
 * (the owner asked whether that state is needed; the footer says it once). After a choice the core refused, [retry] is
 * set: the library's banner says so under the title, and its 重試 runs [retry].
 */
@Composable
internal fun RvLogLevelScreen(level: RvLogLevel, onSelect: (RvLogLevel) -> Unit, onBack: () -> Unit, retry: (() -> Unit)? = null) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_log_level)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(retry?.let { refused(it) })
        RvSettingsColumn {
            RvSettingsRadioCard {
                RvLogLevel.entries.forEach {
                    RvSettingsRadioRow(stringResource(it.label), it == level, { onSelect(it) }, subtitle = if (it == RvLogLevel.Info) stringResource(R.string.rv_log_default) else null)
                }
            }
            RvFootnote(pluralStringResource(R.plurals.rv_log_debug_footer, RV_DEBUG_HOURS, RV_DEBUG_HOURS))
        }
    }
}

/** The banner of a level the core refused, with 重試. */
@Composable
private fun refused(onRetry: () -> Unit) =
    RvNotice(RvNoticeKind.Error, stringResource(R.string.rv_log_failed), stringResource(R.string.rv_log_failed_reason), stringResource(R.string.rv_retry), onRetry)
