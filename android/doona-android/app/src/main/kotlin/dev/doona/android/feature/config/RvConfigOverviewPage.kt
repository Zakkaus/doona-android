package dev.doona.android.feature.config

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRemoveStyle
import dev.doona.android.designsystem.rv.RvRowRemove
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvConfigLine

/**
 * 設定檔 (config plan, configuration overview; `configRead`), under 策略 and linked from 設定, in node detail's grammar:
 * the large title over the applied revision; 檔案, the entry and each include as a plain navigation row naming the file
 * that includes it, its lines and what was skipped in it; 內容, the summary's counts, ending in 檢視生效設定 (the running
 * projection); 相容性, the skipped and partly applied records with the way into their list; 版本, history and import.
 * With [edit] (the owner's add/remove question, style A) the bar holds 編輯: in edit mode each include starts with the
 * remove disc (the entry keeps its place empty: it cannot go), 新增檔案 ends the files' card, and 完成 floats; a removal
 * or a new file is a draft, shown as its change before it applies. [addFile] is the new file's sheet while it is open;
 * a [banner] (a removal the phone could not stage) heads the sections.
 * Without [edit] the page is the approved one.
 */
@Composable
internal fun RvConfigOverviewPage(
    state: RvConfigState,
    onBack: () -> Unit,
    onFile: (String) -> Unit = {},
    onSkipped: () -> Unit = {},
    onEffective: () -> Unit = {},
    onHistory: () -> Unit = {},
    onImport: () -> Unit = {},
    edit: RvListEdit? = null,
    addFile: RvConfigAddFile? = null,
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_title)
    val applied = rvConfigWhen(state.appliedAtEpochMs, state.nowEpochMs)
    val editing = edit?.takeIf { it.editing }
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        actions = edit?.takeIf { !it.editing }?.let { { glass -> RvGlassButton(glass, R.drawable.rv_sym_edit, stringResource(R.string.rv_rule_menu_edit)) { it.onEdit(true) } } },
        bottomAction = editing?.let { { RvPrimaryButton(stringResource(R.string.rv_rules_done), { it.onEdit(false) }) } },
    ) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_config_applied, state.rev, applied)) })
        // A refused edit's [banner] heads the sections; the files' title then keeps its gap above.
        RvBannerSlot(banner, RvSpace.none)
        RvSectionTitle(stringResource(R.string.rv_config_files), Modifier.padding(horizontal = RvSpace.gutter), first = banner == null)
        RvGutter { FilesCard(state, onFile, editing) }
        RvConfigSection(R.string.rv_config_contents) { ContentsCard(state.summary, onEffective) }
        RvConfigSection(R.string.rv_config_compat) { CompatCard(state, onSkipped) }
        RvConfigSection(R.string.rv_settings_version) {
            RvCard {
                RvSettingsNavRow(stringResource(R.string.rv_config_history), R.drawable.rv_sym_schedule, onHistory)
                RvSettingsNavRow(stringResource(R.string.rv_config_import), R.drawable.rv_sym_add, onImport)
            }
        }
    }
    AddFileSheet(state, addFile)
}

/** A section of the configuration pages: the outside title over its cards on the gutter. */
@Composable
internal fun RvConfigSection(title: Int, content: @Composable () -> Unit) {
    RvSectionTitle(stringResource(title), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter { content() }
}

/**
 * Each file of the tree: the entry first, then each include under the file that names it, as they load. In edit mode
 * ([editing]) an include starts with the remove disc, whose sheet says which line goes with it and which files it
 * includes go too; the entry keeps the disc's place empty; 新增檔案 ends the card.
 */
@Composable
private fun FilesCard(state: RvConfigState, onFile: (String) -> Unit, editing: RvListEdit?) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    RvCard {
        state.files.forEach { file ->
            val remove = editing?.let { RvRowRemove(RvRemoveStyle.Editing, if (file.parent == null) null else ({ deleting = file.path })) }
            val origin = file.parent?.let { stringResource(R.string.rv_config_included_by, it) } ?: stringResource(R.string.rv_config_entry)
            val skipped = state.skippedIn(file.path)
            val parts = listOfNotNull(
                origin,
                pluralStringResource(R.plurals.rv_config_lines, file.lines, file.lines),
                if (skipped > 0) pluralStringResource(R.plurals.rv_config_skipped_count, skipped, skipped) else null,
            )
            RvSettingsNavRow(
                file.path,
                null,
                { onFile(file.path) },
                subtitle = rvConfigLine(parts),
                titleKeepWords = false,
                remove = remove,
                removeLabel = stringResource(R.string.rv_list_remove, file.path),
            )
        }
        if (editing != null) RvAddRow(stringResource(R.string.rv_config_file_new), editing.onAdd)
    }
    val target = deleting?.let { path -> state.files.firstOrNull { it.path == path } }
    val nested = target?.let { state.includedUnder(it.path) }.orEmpty().joinToString(stringResource(R.string.rv_effective_list_separator))
    val parent = target?.parent.orEmpty()
    RvConfirmSheet(
        visible = target != null,
        title = stringResource(R.string.rv_list_delete_title, target?.path.orEmpty()),
        body = if (nested.isEmpty()) stringResource(R.string.rv_config_file_delete_body, parent) else stringResource(R.string.rv_config_file_delete_body_nested, parent, nested),
        confirm = stringResource(R.string.rv_rule_delete_confirm),
        dismiss = stringResource(R.string.rv_rule_delete_cancel),
        onConfirm = {
            deleting?.let { editing?.onRemove(it) }
            deleting = null
        },
        onDismiss = { deleting = null },
        titleKeepWords = false,
        bodyKeepWords = false,
    )
}

/**
 * The new file's sheet (style A's 新增檔案): its [path] relative to the entry, which gets the line that includes it.
 * [onPath] types into it and with null closes it; [onCreate] takes a path that is relative, names a `.dae` file and is
 * not in the tree yet.
 */
@Immutable
internal class RvConfigAddFile(val path: String, val onPath: (String?) -> Unit = {}, val onCreate: (String) -> Unit = {})

@Composable
private fun AddFileSheet(state: RvConfigState, add: RvConfigAddFile?) {
    val path = add?.path?.trim().orEmpty()
    val entry = state.files.firstOrNull { it.parent == null }?.path.orEmpty()
    val valid = path.endsWith(DAE) && !path.startsWith("/") && ".." !in path.split('/') && state.files.none { it.path == path }
    RvConfirmSheet(
        visible = add != null,
        title = stringResource(R.string.rv_config_file_new),
        body = stringResource(R.string.rv_config_file_new_body, entry),
        confirm = stringResource(R.string.rv_list_create),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = {
            add?.onCreate(path)
            add?.onPath(null)
        },
        onDismiss = { add?.onPath(null) },
        confirmEnabled = valid,
        bodyKeepWords = false,
    ) {
        add?.let {
            val name = stringResource(R.string.rv_config_file_new)
            RvTextField(it.path, { text -> it.onPath(text) }, label = null, name = name, placeholder = stringResource(R.string.rv_config_file_new_placeholder))
        }
    }
}

private const val DAE = ".dae"

/** The summary's counts, DNS request and response rules apart; then the running projection. */
@Composable
private fun ContentsCard(summary: RvConfigSummary, onEffective: () -> Unit) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_settings_group_subscription), summary.subscriptions.toString())
        RvStatRow(stringResource(R.string.rv_policy_nodes), summary.nodes.toString())
        RvStatRow(stringResource(R.string.rv_policy_groups), summary.groups.toString())
        RvStatRow(stringResource(R.string.rv_policy_rules), summary.trafficRules.toString())
        RvStatRow(stringResource(R.string.rv_config_dns_servers), summary.dnsUpstreams.toString())
        RvStatRow(stringResource(R.string.rv_config_dns_queries), summary.dnsRequestRules.toString())
        RvStatRow(stringResource(R.string.rv_config_dns_answers), summary.dnsResponseRules.toString())
        RvSeeAll(stringResource(R.string.rv_effective_open), onEffective)
    }
}

/** The support report's counts, skipped and applied in part, as 生效設定 counts rules; then the skipped list. */
@Composable
private fun CompatCard(state: RvConfigState, onSkipped: () -> Unit) {
    val skipped = state.skipped.size
    RvCard {
        RvStatRow(stringResource(R.string.rv_rules_skipped), pluralStringResource(R.plurals.rv_config_items, skipped, skipped))
        RvStatRow(stringResource(R.string.rv_config_constrained), pluralStringResource(R.plurals.rv_config_items, state.constrained, state.constrained))
        RvSeeAll(pluralStringResource(R.plurals.rv_config_see_all_items, skipped, skipped), onSkipped)
    }
}

/** When something happened: its time when it is [nowEpochMs]'s day, else its date and time. */
@Composable
internal fun rvConfigWhen(epochMs: Long, nowEpochMs: Long): String {
    val format = rememberRvFormat()
    return if (epochMs / MS_PER_DAY == nowEpochMs / MS_PER_DAY) format.time(epochMs) else format.date(epochMs) + " " + format.time(epochMs)
}

private const val MS_PER_DAY = 86_400_000L
