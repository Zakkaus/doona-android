package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvChoiceLine
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvInkStart
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.feature.policies.RvDnsServerCard

/** The two DNS lists of honk's `dns.routing` (schema 4 §8): `request` (查詢) and `response` (回應). */
internal enum class RvDnsList { Query, Response }

/**
 * A DNS statement's action: `reject`, `asis` (to the server the query was addressed to) and an upstream for a query;
 * `accept`, `reject` and an upstream (asked again) for a response.
 */
internal enum class RvDnsAction { Reject, AsIs, Accept, Upstream }

/** A DNS rule being edited: its match and 啟用 as a traffic rule's (no `must`), its [action] and the [upstream] it names. */
@Immutable
internal data class RvDnsRuleDraft(val list: RvDnsList, val match: RvRuleDraft, val action: RvDnsAction, val upstream: String? = null)

/**
 * A DNS rule's page, in the rule page's grammar ([RvRuleFormPage]): 依據 as there (a query matches its name, a response
 * its addresses; a match the form cannot represent opens as its source lines), then 動作 as a slider over the list's
 * three actions with one line on what the chosen one does, and for an upstream the config's DNS servers to pick from
 * ([upstreams]); the card of 啟用 and 刪除規則, then 位置.
 */
@Composable
internal fun RvDnsRuleEditPage(
    draft: RvDnsRuleDraft,
    onChange: (RvDnsRuleDraft) -> Unit,
    upstreams: List<RvDnsServer>,
    at: RvNativeRuleAt,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit = {},
    onOpenSource: (RvDiffAt) -> Unit = {},
) {
    val title = stringResource(if (draft.list == RvDnsList.Query) R.string.rv_dns_query_title else R.string.rv_dns_response_title)
    val form = RvRuleForm(title, draft.match, { onChange(draft.copy(match = it)) }, at, must = false, privateLine = R.string.rv_dns_private_line)
    RvRuleFormPage(form, onBack, onApply, onDelete, onMove, onOpenSource) { toggles ->
        RvSectionTitle(stringResource(R.string.rv_dns_action), Modifier.padding(horizontal = RvSpace.gutter))
        ActionField(draft) { action -> onChange(draft.copy(action = action, upstream = draft.upstream ?: upstreams.firstOrNull()?.name)) }
        if (draft.action == RvDnsAction.Upstream) {
            RvSectionTitle(stringResource(R.string.rv_config_dns_servers), Modifier.padding(horizontal = RvSpace.gutter))
            RvGutter {
                RvDnsServerCard(upstreams, draft.upstream) { onChange(draft.copy(upstream = it)) }
                toggles()
            }
        } else {
            // No list of servers between: the card keeps the gap a section's title would open above it.
            Spacer(Modifier.height(RvSpace.sectionAbove))
            RvGutter { toggles() }
        }
    }
}

/** The list's three actions on the slider, and the line under it on what the chosen one does. */
@Composable
private fun ActionField(draft: RvDnsRuleDraft, onAction: (RvDnsAction) -> Unit) {
    val actions = draft.list.actions
    Column(Modifier.padding(horizontal = RvSpace.gutter)) {
        RvSegmented(actions.map { stringResource(label(draft.list, it)) }, actions.indexOf(draft.action), { onAction(actions[it]) })
        Spacer(Modifier.height(RvSpace.controlStack))
        val line = stringResource(line(draft.list, draft.action))
        RvChoiceLine(line, Modifier.rvInkStart(line, Rv.type.rowSub))
    }
}

private val RvDnsList.actions: List<RvDnsAction>
    get() = when (this) {
        RvDnsList.Query -> listOf(RvDnsAction.Reject, RvDnsAction.AsIs, RvDnsAction.Upstream)
        RvDnsList.Response -> listOf(RvDnsAction.Accept, RvDnsAction.Reject, RvDnsAction.Upstream)
    }

@StringRes
private fun label(list: RvDnsList, action: RvDnsAction): Int = when (action) {
    RvDnsAction.Reject -> R.string.rv_policy_reject
    RvDnsAction.AsIs -> R.string.rv_dns_action_original
    RvDnsAction.Accept -> R.string.rv_dns_action_accept
    RvDnsAction.Upstream -> if (list == RvDnsList.Query) R.string.rv_dns_action_server else R.string.rv_dns_action_requery
}

@StringRes
private fun line(list: RvDnsList, action: RvDnsAction): Int = when (action) {
    RvDnsAction.Reject -> if (list == RvDnsList.Query) R.string.rv_dns_query_reject_line else R.string.rv_dns_response_reject_line
    RvDnsAction.AsIs -> R.string.rv_dns_query_original_line
    RvDnsAction.Accept -> R.string.rv_dns_response_accept_line
    RvDnsAction.Upstream -> if (list == RvDnsList.Query) R.string.rv_dns_query_server_line else R.string.rv_dns_response_requery_line
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvDnsRuleEditPagePreview() {
    val dark = isSystemInDarkTheme()
    var draft by remember { mutableStateOf(RvNativeSample.dnsQuery) }
    RvTheme(rvSystemScheme(dark), dark) { RvDnsRuleEditPage(draft, { draft = it }, RvNativeSample.upstreams, RvNativeRuleAt(3, 4), {}, {}, {}) }
}
