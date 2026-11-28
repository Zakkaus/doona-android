package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRadioRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.feature.model.RV_BLOCK
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.text
import dev.doona.android.feature.policies.RvPolicyTarget

// The radio cards of the rule forms: where a rule sends, and the DNS server a DNS rule asks.

/**
 * The 送往 list of the quick rule and the rule page: the [groups] a rule can send to, then 直連, and with [block] 拒絕
 * (a native honk rule's `block`): the first [TARGETS] groups, then the rest in place. A group is drawn by
 * where its traffic ends, as on 活動 and in the rules card.
 */
@Composable
internal fun RvTargetCard(groups: List<RvPolicyGroup>, targetId: String, block: Boolean = false, onTarget: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val shown = if (all) groups else groups.take(TARGETS)
    RvCard {
        shown.forEach { Target(it, targetId == it.id) { onTarget(it.id) } }
        Target(null, targetId == RV_DIRECT) { onTarget(RV_DIRECT) }
        if (block) Block(targetId == RV_BLOCK) { onTarget(RV_BLOCK) }
        if (!all) {
            val more = groups.size - shown.size
            RvSeeAll(pluralStringResource(R.plurals.rv_policy_other_groups, more, more)) { all = true }
        }
    }
}

/** One destination: the group and the node it uses, or 直連; the whole row picks it. */
@Composable
private fun Target(group: RvPolicyGroup?, selected: Boolean, onSelect: () -> Unit) {
    RvRadioRow(
        { RvPolicyTarget(group) },
        group?.name?.text() ?: stringResource(R.string.mode_direct),
        selected,
        if (group == null) stringResource(R.string.rv_policy_no_proxy) else group.selected?.name ?: stringResource(group.kind.label),
        titleKeepWords = false,
        subtitleKeepWords = false,
        onSelect = onSelect,
    )
}

/** 拒絕: the flow is refused, as the rules card draws a rule that ends there. */
@Composable
private fun Block(selected: Boolean, onSelect: () -> Unit) {
    RvRadioRow(
        { RvIconDisc(R.drawable.rv_sym_block) },
        stringResource(R.string.rv_policy_reject),
        selected,
        stringResource(R.string.rv_policy_block_line),
        titleKeepWords = false,
        subtitleKeepWords = false,
        onSelect = onSelect,
    )
}

/**
 * The DNS servers a DNS rule can send to: each of the config's [upstreams] by its name over its address, [selected]
 * marked; the whole row picks it.
 */
@Composable
internal fun RvDnsServerCard(upstreams: List<RvDnsServer>, selected: String?, onSelect: (String) -> Unit) {
    RvCard {
        upstreams.forEach { upstream ->
            val chosen = upstream.name == selected
            RvRadioRow(
                { RvIconDisc(R.drawable.rv_sym_dns) },
                upstream.name,
                chosen,
                upstream.address,
                titleKeepWords = false,
                subtitleKeepWords = false,
                onSelect = { onSelect(upstream.name) },
            )
        }
    }
}

/** The groups listed before 其他 N 個策略組: the main node, 自動, 串流 and AI. */
private const val TARGETS = 4
