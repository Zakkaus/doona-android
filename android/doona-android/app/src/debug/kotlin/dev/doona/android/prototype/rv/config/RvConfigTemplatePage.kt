package dev.doona.android.prototype.rv.config

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
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
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSourceDiff
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvConfigLine
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.config.RvConfigTemplateForm
import dev.doona.android.feature.model.text
import dev.doona.android.feature.policies.RvTemplateRow

/**
 * 範本 on the configuration side (doona's routing templates; `configDraft from: template`): doona's three templates that
 * add no group, 更多範本 with the four that do, the two options every template writes, and the groups the selected
 * template declares, each with how it picks (and the flag of a region group). With no configuration yet ([current]
 * null), 建立設定檔 makes the first revision; otherwise the one in use says so and 檢視變更 opens the diff once the choice
 * differs from what the routing holds, as doona judges it: another template, or [options] other than the [held] ones.
 */
@Composable
internal fun RvConfigTemplatePage(
    current: RvConfigTemplate?,
    selected: RvConfigTemplate,
    options: RvTemplateOptions,
    onSelect: (RvConfigTemplate) -> Unit,
    onOptions: (RvTemplateOptions) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit = {},
    held: RvTemplateOptions = RvTemplateOptions(),
) {
    val next = if (current == null) R.string.rv_config_create else R.string.rv_template_review
    val changed = current == null || selected.row.id != current.row.id || options != held
    RvConfigTemplateForm(
        onBack,
        next,
        onNext.takeIf { changed },
        primary = { TemplatesCard(RvConfigTemplateData.primary, current, selected, onSelect) },
        more = { TemplatesCard(RvConfigTemplateData.more, current, selected, onSelect) },
        ads = options.blockAds,
        quic = options.blockQuic,
        onAds = { onOptions(options.copy(blockAds = it)) },
        onQuic = { onOptions(options.copy(blockQuic = it)) },
    ) { RvCard { selected.written.forEach { GroupRow(it, null) } } }
}

/**
 * 檢視變更 before a template replaces the routing (doona's template dialog, drawn as the configuration's diff page): the
 * base revision under the large title; the template before and after (策略's 範本 row, so a long pair wraps under its
 * title rather than pushing a stat's label out) and the rules it adds and removes; the groups it
 * adds and those it reuses, each titled by its label over the change, its name in the file and how it picks; then the file's diff, a tap
 * opening the file at that line, and the line that only new connections follow it. 套用 floats over the scrim.
 */
@Composable
internal fun RvConfigTemplateApplyPage(apply: RvTemplateApply, onBack: () -> Unit, onApply: () -> Unit = {}, onOpen: (String, RvDiffAt) -> Unit = { _, _ -> }) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_template_review)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply) },
        actionScrim = true,
    ) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_config_based_on, apply.baseRev)) })
        RvGutter {
            RvCard {
                val change = stringResource(R.string.rv_template_from_to, apply.from.name.text(), apply.to.name.text())
                RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, stringResource(R.string.rv_policy_template), rvConfigLine(listOf(change)))
                RvStatRow(stringResource(R.string.rv_template_added), pluralStringResource(R.plurals.rv_rules_count, apply.added, apply.added))
                RvStatRow(stringResource(R.string.rv_template_removed), pluralStringResource(R.plurals.rv_rules_count, apply.removed, apply.removed))
            }
        }
        Section(R.string.rv_policy_groups)
        RvGutter { RvCard { apply.groups.forEach { GroupRow(it.group, stringResource(it.change.label)) } } }
        Section(R.string.rv_config_files)
        RvGutter { RvSourceDiff(apply.file, onOpen = { onOpen(apply.file.source, it) }) }
        RvFootnote(stringResource(R.string.rv_template_new_flows))
    }
}

/** A card of templates in the row form of 策略's 範本 page. */
@Composable
private fun TemplatesCard(templates: List<RvConfigTemplate>, current: RvConfigTemplate?, selected: RvConfigTemplate, onSelect: (RvConfigTemplate) -> Unit) {
    RvCard { templates.forEach { RvTemplateRow(it.row, it.row.id == selected.row.id, it.row.id == current?.row?.id, kept = true) { onSelect(it) } } }
}

@Composable
private fun Section(@StringRes title: Int) = RvSectionTitle(stringResource(title), Modifier.padding(horizontal = RvSpace.gutter))

/**
 * A template's group: a region's flag or the glyph of how it picks, titled by its label over how it picks. On the diff
 * page the line starts with [change] and the group's name in the file, which doona's apply dialog alone shows.
 */
@Composable
private fun GroupRow(group: RvTemplateGroup, change: String?) {
    val named = change?.let { listOf(it, group.name) }.orEmpty()
    val line = rvConfigLine(named + stringResource(group.policy.label))
    RvRow({ group.flag?.let { RvFlag(it, RvSize.rowDisc) } ?: RvIconDisc(group.policy.glyph) }, stringResource(group.label), line)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigTemplatePagePreview() {
    val dark = isSystemInDarkTheme()
    var selected by remember { mutableStateOf(RvConfigTemplateData.regions) }
    var options by remember { mutableStateOf(RvTemplateOptions()) }
    RvTheme(rvSystemScheme(dark), dark) { RvConfigTemplatePage(null, selected, options, { selected = it }, { options = it }, onBack = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigTemplateApplyPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigTemplateApplyPage(RvConfigTemplateData.apply, onBack = {}) }
}
