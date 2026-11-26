package dev.doona.android.prototype.rv.policy

import androidx.annotation.DrawableRes
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
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_ROW_TITLE_LINES
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
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.SEPARATOR

/**
 * A statement of a DNS list as the list shows it: its match in honk text ([source], null for the list's fallback, the
 * last row), what it does and the server it names, and whether it is paused (commented out).
 */
@Immutable
internal data class RvDnsRow(val source: String?, val action: RvDnsAction, val upstream: String? = null, val paused: Boolean = false)

/** The two DNS lists of the config, query and response, each in file order and ending with its fallback. */
@Immutable
internal data class RvDnsLists(val query: List<RvDnsRow>, val response: List<RvDnsRow>)

/**
 * DNS 規則 (config plan row 17; no prototype, sample only): the config's DNS query and response statements, each list
 * as its own section in file order, how they are tried under the title. A row is the statement's match in honk text
 * (what the user finds in dns.dae), over its action and the server it names, after its action's glyph; a paused one
 * says so. The last row is the list's fallback (其餘查詢, 其餘回應), which stays. A tap opens the statement's page
 * ([RvDnsRuleEditPage]). With [edit] (style A of the owner's add/remove question) the bar holds 編輯; in edit mode each
 * statement starts with the remove disc (the fallback keeps its place empty), 新增規則 ends each card and 完成 floats.
 * A [banner] (a removal the phone could not stage) heads the sections.
 */
@Composable
internal fun RvDnsRulesPage(
    lists: RvDnsLists,
    onBack: () -> Unit,
    onOpen: (RvDnsList, Int) -> Unit = { _, _ -> },
    onAdd: (RvDnsList) -> Unit = {},
    edit: RvListEdit? = null,
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_dns_rules_title)
    val editing = edit?.takeIf { it.editing }
    var deleting by rememberSaveable { mutableStateOf<Pair<RvDnsList, Int>?>(null) }
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        actions = edit?.takeIf { !it.editing }?.let { { glass -> RvGlassButton(glass, R.drawable.rv_sym_edit, stringResource(R.string.rv_rule_menu_edit)) { it.onEdit(true) } } },
        bottomAction = editing?.let { { RvPrimaryButton(stringResource(R.string.rv_rules_done), { it.onEdit(false) }) } },
    ) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_policy_rules_order)) })
        RvBannerSlot(banner, RvSpace.none)
        DnsSection(RvDnsList.Query, lists.query, first = banner == null, editing, onOpen, { onAdd(RvDnsList.Query) }) { deleting = RvDnsList.Query to it }
        DnsSection(RvDnsList.Response, lists.response, first = false, editing, onOpen, { onAdd(RvDnsList.Response) }) { deleting = RvDnsList.Response to it }
    }
    val target = deleting?.let { (list, i) -> (if (list == RvDnsList.Query) lists.query else lists.response).getOrNull(i) }
    RvConfirmSheet(
        visible = target != null,
        title = stringResource(R.string.rv_rule_delete_title),
        body = stringResource(R.string.rv_rule_delete_body, target?.source.orEmpty()),
        confirm = stringResource(R.string.rv_rule_delete_confirm),
        dismiss = stringResource(R.string.rv_rule_delete_cancel),
        onConfirm = {
            deleting?.let { (list, i) -> edit?.onRemove("${list.name}:$i") }
            deleting = null
        },
        onDismiss = { deleting = null },
        bodyKeepWords = false,
    )
}

/** One DNS list under its title: its statements, the fallback last; in edit mode the discs and 新增規則. */
@Composable
private fun DnsSection(
    list: RvDnsList,
    rows: List<RvDnsRow>,
    first: Boolean,
    editing: RvListEdit?,
    onOpen: (RvDnsList, Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    val heading = if (list == RvDnsList.Query) R.string.rv_config_dns_queries else R.string.rv_config_dns_answers
    RvSectionTitle(stringResource(heading), Modifier.padding(horizontal = RvSpace.gutter), first = first)
    RvGutter {
        RvCard {
            rows.forEachIndexed { i, row ->
                val title = row.source ?: stringResource(if (list == RvDnsList.Query) R.string.rv_dns_rest_query else R.string.rv_dns_rest_response)
                val remove = editing?.let { RvRowRemove(RvRemoveStyle.Editing, if (row.source == null) null else ({ onRemove(i) })) }
                RvSettingsNavRow(
                    title,
                    row.action.glyph,
                    { onOpen(list, i) },
                    subtitle = dnsRowLine(list, row),
                    code = row.source != null,
                    // Honk text is what the user looks for in dns.dae: never cut, as on the skipped list.
                    titleLines = if (row.source != null) Int.MAX_VALUE else RV_ROW_TITLE_LINES,
                    titleKeepWords = false,
                    remove = remove,
                    removeLabel = stringResource(R.string.rv_list_remove, title),
                )
            }
            if (editing != null) RvAddRow(stringResource(R.string.rv_policy_new_rule), onAdd)
        }
    }
}

/** A row's line: its action, the server it names, and 已停用 for a paused statement. */
@Composable
private fun dnsRowLine(list: RvDnsList, row: RvDnsRow): String = listOfNotNull(
    stringResource(rvDnsActionLabel(list, row.action)),
    row.upstream,
    if (row.paused) stringResource(R.string.rv_policy_rule_off) else null,
).joinToString(SEPARATOR)

/** An action's glyph: 拒絕's block, a server's DNS mark, 原伺服器's onward arrow, 接受's check. */
@get:DrawableRes
private val RvDnsAction.glyph: Int
    get() = when (this) {
        RvDnsAction.Reject -> R.drawable.rv_sym_block
        RvDnsAction.Upstream -> R.drawable.rv_sym_dns
        RvDnsAction.AsIs -> R.drawable.rv_sym_arrow_right_alt
        RvDnsAction.Accept -> R.drawable.rv_sym_check
    }
