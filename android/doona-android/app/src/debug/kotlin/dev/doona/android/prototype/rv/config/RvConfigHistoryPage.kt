package dev.doona.android.prototype.rv.config

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.RvCaption
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFootnote
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvPageHeader
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.nodes.RvStatusChip
import dev.doona.android.prototype.rv.rvKeepRuns
import dev.doona.android.prototype.rv.rvKeepTail
import dev.doona.android.prototype.rv.rvSystemScheme
import dev.doona.android.prototype.rv.settings.RvSettingsNavRow

/**
 * 版本紀錄 (config plan, history; `configHistory`): the applied revisions newest first, each a plain navigation row
 * with when and how it was applied, the head marked 使用中; a row opens that revision's diff against the head, from
 * which a restore makes a new revision. 匯出設定檔 follows in its own card (`configExport`, behind the picker and a
 * confirmation, since the files can carry credentials).
 */
@Composable
internal fun RvConfigHistoryPage(
    revisions: List<RvRevision>,
    headRev: Int,
    onBack: () -> Unit,
    onRevision: (Int) -> Unit = {},
    onExport: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_history)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter {
            RvCard { revisions.forEach { RevisionRow(it, it.rev == headRev, onRevision) } }
            RvCard { RvSettingsNavRow(stringResource(R.string.rv_config_export), R.drawable.rv_sym_ios_share, onExport) }
        }
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Composable
private fun RevisionRow(revision: RvRevision, head: Boolean, onRevision: (Int) -> Unit) {
    val origin = revision.restored?.let { stringResource(revision.origin.label, it) } ?: stringResource(revision.origin.label)
    val inUse: (@Composable () -> Unit)? = if (head) ({ RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_template_in_use), Rv.colors.positive) }) else null
    RvSettingsNavRow(
        stringResource(R.string.rv_config_revision, revision.rev),
        null,
        { onRevision(revision.rev) },
        subtitle = rvConfigLine(listOf(rvConfigWhen(revision.appliedAtEpochMs), origin)),
        mark = inUse,
    )
}

/**
 * 匯入設定檔 after the picker (config plan, import boundary): the picked file under the large title. Refused at the
 * budget, a banner says so with the limit and offers another file, and a footnote says the head is still in use.
 * Staged with an include outside the picked file's directory, the draft's files follow a banner naming the line and the
 * path, which offers the source; 檢視變更 waits until the include is fixed.
 */
@Composable
internal fun RvConfigImportPage(
    state: RvImportState,
    onBack: () -> Unit,
    onPickAgain: () -> Unit = {},
    onEditSource: () -> Unit = {},
    onFile: (String) -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_import)
    val review: (@Composable () -> Unit)? = if (state.refusedAtLimit) null else ({ RvPrimaryButton(stringResource(R.string.rv_template_review), {}, enabled = false) })
    RvScrollPage(title, insets, onBack = onBack, bottomAction = review) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(state.name) })
        if (state.refusedAtLimit) {
            RvGutter {
                RvBanner(
                    stringResource(R.string.rv_config_import_refused),
                    rvKeepTail(
                        rvKeepRuns(pluralStringResource(R.plurals.rv_config_import_limit, RvConfigHistoryData.LIMIT_FILES, RvConfigHistoryData.LIMIT_FILES, RvConfigHistoryData.LIMIT_SIZE)),
                    ),
                    stringResource(R.string.rv_config_pick_again),
                    onPickAgain,
                )
            }
            RvFootnote(stringResource(R.string.rv_config_still_in_use, state.headRev))
        } else {
            RvGutter {
                RvBanner(
                    rvKeepTail(stringResource(R.string.rv_config_escape_title)),
                    stringResource(R.string.rv_source_line, state.escapeLine, state.escapePath),
                    stringResource(R.string.rv_config_edit_source),
                    onEditSource,
                    icon = R.drawable.rv_sym_link,
                )
            }
            RvConfigSection(R.string.rv_config_files) {
                RvCard {
                    state.files.forEach { file ->
                        val origin = file.parent?.let { stringResource(R.string.rv_config_included_by, it) } ?: stringResource(R.string.rv_config_entry)
                        val lines = pluralStringResource(R.plurals.rv_config_lines, file.lines, file.lines)
                        RvSettingsNavRow(file.path, null, { onFile(file.path) }, subtitle = rvConfigLine(listOf(origin, lines)))
                    }
                }
            }
        }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigHistoryPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigHistoryPage(RvConfigHistoryData.revisions, RvConfigData.REV, onBack = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigImportPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigImportPage(RvConfigHistoryData.escape, onBack = {}) }
}
