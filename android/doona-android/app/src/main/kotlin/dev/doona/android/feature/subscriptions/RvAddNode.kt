package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NODE_LINK_PLACEHOLDER
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.nodes.ui.RvNodePickRow

/** What 訂閱's 組態內 card and 新增節點's sheet ask for. */
internal class RvInlineActions(
    val add: () -> Unit = {},
    val close: () -> Unit = {},
    val edit: (String) -> Unit = {},
    val paste: () -> Unit = {},
    val confirmAdd: () -> Unit = {},
    val openNode: (String) -> Unit = {},
    val delete: (String?) -> Unit = {},
    val confirmDelete: (String) -> Unit = {},
    val viewGroups: () -> Unit = {},
)

/**
 * The card under the subscriptions (decision 2026-11-25 23:52, panel s1125f-addnode-pages). Before any node is added, one
 * row: 新增節點 over what it takes, which opens the sheet. Once one is, the card is the 組態內 source: the link disc over
 * its node count, its nodes (a tap, the info mark or the menu's 詳情 opens the node's page, 節點's `NodeKey` route, which
 * reads the same catalog; a long press opens the node menu, with 刪除節點), then 新增節點 over what it takes.
 */
@Composable
internal fun RvInlineCard(nodes: List<RvNode>, actions: RvInlineActions) {
    val add = stringResource(R.string.rv_addnode)
    // 新增節點 keeps its line under it in both forms of the card, so the row is one height.
    val line = stringResource(R.string.rv_addnode_line)
    RvCard {
        if (nodes.isEmpty()) return@RvCard RvAddRow(add, actions.add, subtitle = line)
        RvRow({ RvIconDisc(R.drawable.rv_sym_link) }, stringResource(R.string.rv_inline_source), pluralStringResource(R.plurals.rv_nodes_nodes, nodes.size, nodes.size))
        nodes.forEachIndexed { order, node ->
            RvNodePickRow(node, stringResource(node.place.label), current = false, order = order, onPick = actions.openNode, onDetail = actions.openNode, pickInMenu = false)
        }
        RvAddRow(add, actions.add, subtitle = line)
    }
}

/**
 * 新增節點's sheet (panel s1125f-addnode-sheet), the library's confirm sheet: what to paste, the clipboard's row while the
 * field is empty and the clipboard may hold a link, the link field (up to [LINK_LINES] lines that wrap, then it scrolls
 * inside; one link per line) with what the core will add or why it cannot, then 新增 and 取消. 新增 waits for the core's
 * preview and for an add already sent.
 */
@Composable
internal fun RvAddNodeSheet(sheet: RvAddNodeSheet?, actions: RvInlineActions) {
    val title = stringResource(R.string.rv_addnode)
    val check = sheet?.check ?: RvAddNodeCheck.None
    RvConfirmSheet(
        visible = sheet != null,
        title = title,
        body = stringResource(R.string.rv_addnode_help),
        confirm = stringResource(R.string.rv_add_import),
        dismiss = stringResource(R.string.rv_cancel),
        onConfirm = actions.confirmAdd,
        onDismiss = actions.close,
        confirmEnabled = check.addable && sheet?.adding != true,
        extra = {
            // As 新增訂閱's form: the clipboard's row, then the field a little below it.
            if (sheet?.clipboard == true && sheet.text.isEmpty()) {
                RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_content_paste) }, stringResource(R.string.rv_add_clipboard), null, onClick = actions.paste) }
            }
            Column(Modifier.padding(top = RvSpace.tight)) {
                RvTextField(
                    sheet?.text.orEmpty(),
                    actions.edit,
                    stringResource(R.string.rv_addnode_link),
                    placeholder = RV_NODE_LINK_PLACEHOLDER,
                    supporting = supporting(check),
                    error = error(check),
                    enabled = sheet?.adding != true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    lines = LINK_LINES,
                )
            }
        },
    )
}

/** What the field says under it while the paste can be added: the one node's name, or the count and the lines skipped. */
@Composable
private fun supporting(check: RvAddNodeCheck): String? = when (check) {
    is RvAddNodeCheck.One -> stringResource(R.string.rv_addnode_as, check.name)
    is RvAddNodeCheck.Many -> sentences(listOf(pluralStringResource(R.plurals.rv_addnode_count, check.count, check.count)) + skippedLines(check.skipped))
    else -> null
}

/** Why nothing can be added: no line is a share link (doona web's `nodes.linkInvalid`), the lines the core refused, or its error. */
@Composable
private fun error(check: RvAddNodeCheck): String? = when (check) {
    is RvAddNodeCheck.Refused ->
        if (check.skipped.duplicate.isEmpty() && check.skipped.other.isEmpty() && check.skipped.unread == 0) stringResource(R.string.rv_addnode_invalid) else sentences(skippedLines(check.skipped))

    is RvAddNodeCheck.Failed -> stringResource(check.code.messageRes())

    else -> null
}

/** One sentence per kind of skipped line: not a share link, already in 組態內, refused for another reason, past the cap. */
@Composable
private fun skippedLines(skipped: RvSkipped): List<String> = listOfNotNull(
    lines(R.plurals.rv_addnode_skip_link, skipped.notLink),
    lines(R.plurals.rv_addnode_skip_duplicate, skipped.duplicate),
    lines(R.plurals.rv_addnode_skip_other, skipped.other),
    if (skipped.unread == 0) null else pluralStringResource(R.plurals.rv_addnode_skip_unread, skipped.unread, skipped.unread, ADD_NODE_LINES),
)

@Composable
private fun lines(id: Int, lines: List<Int>): String? = if (lines.isEmpty()) null else pluralStringResource(id, lines.size, lines.size, listed(lines.map(Int::toString)))

/** [parts] as a list in a sentence: `日本、新加坡 與 AI`, `Japan, Singapore and AI`. */
@Composable
private fun listed(parts: List<String>): String {
    val sep = stringResource(R.string.rv_list_sep)
    return if (parts.size < 2) parts.joinToString() else stringResource(R.string.rv_list_last, parts.dropLast(1).joinToString(sep), parts.last())
}

/** Sentences one after another: Chinese sentences end in a full-width stop, which carries its own space; others take a space. */
@Composable
private fun sentences(parts: List<String>): String = parts.joinToString(if (LocalConfiguration.current.locales[0].language == "zh") "" else " ")

/**
 * After an add: how many nodes it added and which groups took them, with 查看策略群組; Info when every line was added, an
 * error that also counts the lines it did not add when some were not, as an action that partly failed (the sheet said why
 * before 新增; the reason keeps to the groups, so it stays within the banner's lines at 2.0×).
 */
@Composable
internal fun rvAddedBanner(added: RvAddedNodes, onViewGroups: () -> Unit): RvNotice {
    val names = added.groups.map { groupLabel(it) }
    val skipped = added.skipped.count + added.skipped.unread
    return RvNotice(
        if (skipped == 0) RvNoticeKind.Info else RvNoticeKind.Error,
        if (skipped == 0) {
            pluralStringResource(R.plurals.rv_addnode_added, added.count, added.count)
        } else {
            pluralStringResource(R.plurals.rv_addnode_added_partial, added.count, added.count, skipped)
        },
        if (names.isEmpty()) null else stringResource(R.string.rv_addnode_joined, listed(names)),
        stringResource(R.string.rv_addnode_view_groups),
        onViewGroups,
        reasonKeepWords = false,
    )
}

@Composable
private fun groupLabel(name: RvGroupName): String = when (name) {
    RvGroupName.Main -> stringResource(R.string.rv_main_node)
    RvGroupName.Auto -> stringResource(R.string.rv_policy_group_auto)
    RvGroupName.Streaming -> stringResource(R.string.rv_rule_streaming)
    RvGroupName.Ai -> stringResource(R.string.rv_rule_ai)
    is RvGroupName.Place -> stringResource(name.place.label)
    is RvGroupName.Named -> name.name
}

/** The banner of a node the core would not delete, an error: why (the engine's error text), and 重試 deletes it again. */
@Composable
internal fun rvNodeDeleteFailedBanner(failed: RvNodeDeleteFailed, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    stringResource(R.string.rv_sub_delete_failed_title, failed.name),
    stringResource(failed.code.messageRes()),
    stringResource(R.string.rv_retry),
    onRetry,
    titleKeepWords = false,
)

/** 刪除節點's question, the confirm sheet a subscription's delete asks in. */
@Composable
internal fun RvNodeDeleteSheet(node: RvNode?, actions: RvInlineActions) = RvConfirmSheet(
    visible = node != null,
    title = stringResource(R.string.rv_sub_delete_title, node?.name.orEmpty()),
    body = stringResource(R.string.rv_node_delete_body),
    confirm = stringResource(R.string.rv_menu_delete),
    dismiss = stringResource(R.string.rv_cancel),
    onConfirm = { node?.let { actions.confirmDelete(it.id) } },
    onDismiss = { actions.delete(null) },
    titleKeepWords = false,
)

/** The link field's lines before it scrolls. */
private const val LINK_LINES = 4
