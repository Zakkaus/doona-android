package dev.doona.android.prototype.rv.config

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.settings.RvSettingsNavRow

/**
 * 已略過 (config plan, "follow a skip to its reason"; `effectiveConfig`'s support records), from 設定檔's 相容性: the
 * count under the large title, then one card per file in load order, each record a plain navigation row: the
 * declaration or rule as the file spells it, over its line and every reason. A row opens the file at that line.
 */
@Composable
internal fun RvConfigSkippedPage(state: RvConfigState, onBack: () -> Unit, onOpen: (RvSkipped) -> Unit = {}) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_rules_skipped)
    val count = state.skipped.size
    val byFile = state.files.mapNotNull { file -> state.skipped.filter { it.source == file.path }.takeIf { it.isNotEmpty() }?.let { file.path to it } }
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(pluralStringResource(R.plurals.rv_config_items, count, count)) })
        byFile.forEachIndexed { i, (path, records) ->
            RvSectionTitle(path, Modifier.padding(horizontal = RvSpace.gutter), first = i == 0)
            RvGutter { RvCard { records.forEach { SkippedRow(it, onOpen) } } }
        }
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Composable
private fun SkippedRow(record: RvSkipped, onOpen: (RvSkipped) -> Unit) {
    val reasons = record.reasons.map { stringResource(it.label) }.joinToString(stringResource(R.string.rv_effective_list_separator))
    val line = rvConfigLine(listOf(stringResource(R.string.rv_config_line, record.line), reasons))
    RvSettingsNavRow(record.text, null, { onOpen(record) }, subtitle = line, code = true, titleLines = Int.MAX_VALUE)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigSkippedPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigSkippedPage(RvConfigData.skippedInclude, onBack = {}) }
}
