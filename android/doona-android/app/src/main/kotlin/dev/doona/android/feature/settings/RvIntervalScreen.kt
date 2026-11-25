package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvInputContent
import dev.doona.android.designsystem.rv.RvInputSheet
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSettingsColumn
import dev.doona.android.designsystem.rv.RvSettingsRadioCard
import dev.doona.android.designsystem.rv.RvSettingsRadioRow
import dev.doona.android.designsystem.rv.RvSettingsRow
import dev.doona.android.designsystem.rv.RvSettingsSpace
import dev.doona.android.designsystem.rv.rvRowPress

/**
 * 更新間隔, the page 設定's 訂閱 card opens from its 更新間隔 row, laid out as the DNS page is: the large title, then one card of radio rows, each
 * preset of [choices] (in hours: the store's, as doona web's `features/nodes/subscription.ts` offers them; its 僅手動 is
 * 設定's 自動更新 switch turned off, so the page leaves it out), and 自訂 last, which opens [RvIntervalCustomSheet]
 * ([onCustom]). A [selected] value that is no preset, one typed in 自訂, leaves every radio empty.
 */
@Composable
internal fun RvIntervalScreen(selected: Int, choices: List<Int>, onSelect: (Int) -> Unit, onBack: () -> Unit, onCustom: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_settings_update_interval)
    RvScrollPage(title, insets, onBack = onBack, end = RvPageEnd.Inset(RvSettingsSpace.bottom)) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvSettingsColumn {
            RvSettingsRadioCard {
                choices.forEach { hours ->
                    RvSettingsRadioRow(pluralStringResource(R.plurals.rv_interval_hours, hours, hours), hours == selected, { onSelect(hours) })
                }
                // As DNS's 自訂: no radio, a chevron, since it opens a field rather than choosing.
                RvSettingsRow(stringResource(R.string.rv_settings_dns_custom), Modifier.rvRowPress(Role.Button, onCustom)) { RvChevron() }
            }
        }
    }
}

/**
 * 自訂: a sheet for a whole count of hours in [range], holding the stored count when it is no preset, with 儲存 and
 * 取消. A count out of range or no number keeps the sheet open with why under the field.
 */
@Composable
internal fun RvIntervalCustomSheet(state: IntervalUiState, range: IntRange, onText: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val (title, hint, error) = customWords(state, range)
    RvInputSheet(state.customOpen, title, state.customText, onText, onSave, onDismiss, PLACEHOLDER, hint, error, KeyboardType.Number)
}

/** What [RvIntervalCustomSheet] shows in its card. */
@Composable
internal fun ColumnScope.RvIntervalCustomContent(state: IntervalUiState, range: IntRange, onText: (String) -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val (title, hint, error) = customWords(state, range)
    RvInputContent(title, state.customText, onText, onSave, onDismiss, PLACEHOLDER, hint, error, KeyboardType.Number)
}

/** 自訂's title, hint and, once a save found no count in [range], why. */
@Composable
private fun customWords(state: IntervalUiState, range: IntRange): Triple<String, String, String?> = Triple(
    stringResource(R.string.rv_settings_interval_custom_title),
    stringResource(R.string.rv_settings_interval_custom_hint, range.first, range.last),
    if (state.customInvalid) stringResource(R.string.rv_settings_interval_custom_invalid, range.first, range.last) else null,
)

/** 自訂's placeholder: a count no preset has. */
private const val PLACEHOLDER = "48"
