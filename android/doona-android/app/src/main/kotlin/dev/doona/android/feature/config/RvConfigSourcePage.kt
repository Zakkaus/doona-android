package dev.doona.android.feature.config

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvFilterChip
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSourceDiff
import dev.doona.android.designsystem.rv.RvSourceField
import dev.doona.android.designsystem.rv.RvSourceMark
import dev.doona.android.designsystem.rv.rvKeepRuns
import dev.doona.android.designsystem.rv.rvKeepTail

/**
 * 原始碼 (config plan, source editor; `configDraft` → `configRead` → `configEdit` → `configCheck`): the large title over
 * the draft and its base revision, the file as a filter chip that picks the entry or an include, then the file in
 * [RvSourceField]. The caller owns the text ([value]); [draft] is the text `configCheck` last checked, and its
 * diagnostic is marked on its span only while [value] is still that text: an edit moves the spans, so after one the
 * mark goes and 檢視變更 runs the check again on the text as it is. 搜尋 sits in the bar; 檢視變更 floats at the foot
 * and runs the check, then the diff; it waits while the diagnostic stands.
 */
@Composable
internal fun RvConfigSourcePage(
    draft: RvSourceDraft,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    onPickFile: () -> Unit = {},
    onSearch: () -> Unit = {},
    onReview: () -> Unit = {},
    failure: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_source)
    val diagnostic = draft.diagnostic?.takeIf { value.text == draft.text }
    val marks = diagnostic?.let { listOf(RvSourceMark(it.start, it.end, stringResource(R.string.rv_config_unknown_group, it.name))) }.orEmpty()
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        actions = { glass -> RvGlassButton(glass, R.drawable.rv_sym_search, stringResource(R.string.rv_config_search), onSearch) },
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_template_review), onReview, enabled = diagnostic == null) },
        actionScrim = true,
    ) { titleBlock ->
        RvPageHeader(
            title,
            titleBlock,
            insets,
            caption = { RvCaption(stringResource(R.string.rv_config_draft_base, draft.baseRev ?: RV_NO_VALUE)) },
            control = { RvFilterChip(draft.path, onPickFile) },
        )
        RvGutter(banner = failure) { RvSourceField(value, onValueChange, draft.path, marks = marks) }
    }
}

/**
 * 檢視變更 before 套用 (config plan, diff before apply; `configCheck`'s diff, then `configApply` against the base): the
 * base revision under the large title, one [RvSourceDiff] card per changed file, a tap opening the file at that line,
 * and the line that only new connections follow it. When another apply made a newer head, the draft is stale: a
 * banner says which revisions, offers 重新載入, and 套用 waits; another failed 套用 shows [failure] there.
 */
@Composable
internal fun RvConfigDiffPage(
    diff: RvConfigDiff,
    onBack: () -> Unit,
    onApply: () -> Unit = {},
    onReload: () -> Unit = {},
    onOpen: (String, RvDiffAt) -> Unit = { _, _ -> },
    failure: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_template_review)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply, enabled = !diff.stale) },
        actionScrim = true,
    ) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_config_based_on, diff.baseRev ?: RV_NO_VALUE)) })
        val stale = if (diff.stale) {
            RvNotice(
                RvNoticeKind.Error,
                stringResource(R.string.rv_config_stale_title),
                rvKeepTail(rvKeepRuns(stringResource(R.string.rv_config_stale_reason, diff.baseRev ?: RV_NO_VALUE, diff.headRev ?: RV_NO_VALUE))),
                stringResource(R.string.rv_config_reload),
                onReload,
            )
        } else {
            null
        }
        RvGutter(banner = stale ?: failure) {
            val files = diff.files
            if (files == null) {
                RvFootnote(stringResource(R.string.rv_config_diff_limit))
            } else {
                files.forEach { file -> RvSourceDiff(file, onOpen = { onOpen(file.source, it) }) }
            }
        }
        RvFootnote(stringResource(R.string.rv_template_new_flows))
    }
}
