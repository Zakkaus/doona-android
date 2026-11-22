package dev.doona.android.prototype.rv.policy

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvRadio
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSeeAll
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.rememberRvFormat

/**
 * Where a rule or a picked target sends traffic, as 活動's rule rows draw it: the flag of the node the group uses, or
 * 直連's straight arrow.
 */
@Composable
internal fun RvPolicyTarget(group: RvPolicyGroup?) {
    if (group == null) RvIconDisc(R.drawable.rv_sym_arrow_right_alt) else RvFlag(group.selected.place.name, RvSize.rowDisc)
}

/** What a rule matches, as its row's title. */
@Composable
internal fun RvRuleMatch.title(): String = when (this) {
    is RvRuleMatch.Domains -> pluralStringResource(R.plurals.rv_policy_domains, count, first, count)
    is RvRuleMatch.GeoIp -> code
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest)
    is RvRuleMatch.Value -> value
    is RvRuleMatch.GeoSite -> category
}

/** The kind of match, under the title. */
@Composable
internal fun RvRuleMatch.kind(): String = when (this) {
    is RvRuleMatch.Domains -> stringResource(R.string.rv_policy_kind_domain)
    is RvRuleMatch.GeoIp -> GEOIP
    RvRuleMatch.Lan -> stringResource(R.string.rv_policy_lan_kind)
    RvRuleMatch.Rest -> stringResource(R.string.rv_policy_rest_kind)
    is RvRuleMatch.Value -> stringResource(kind.label)
    is RvRuleMatch.GeoSite -> GEOSITE
}

/**
 * A rule as the 策略 card and the list of all rules draw it, after 活動's rule rows: where the traffic ends, what it
 * matches over its kind, and the target over [end] (today's bytes by default). A rule that is off or skipped says so
 * after its kind and shows no bytes.
 */
@Composable
internal fun RvRuleRow(rule: RvPolicyRule, modifier: Modifier = Modifier, end: String? = null) {
    val format = rememberRvFormat()
    val subtitle = when (rule.state) {
        RvRuleState.On -> rule.match.kind()
        RvRuleState.Off -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_off)
        RvRuleState.Skipped -> rule.match.kind() + SEPARATOR + stringResource(R.string.rv_policy_rule_skipped)
    }
    val bytes = if (rule.state == RvRuleState.On) format.bytes(rule.bytes).toString() else RV_NO_VALUE
    RvRow(
        { RvPolicyTarget(rule.group) },
        rule.match.title(),
        subtitle,
        modifier,
        titleEnd = { RvText(rule.targetName(), Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = { RvText(end ?: bytes, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.End) },
    )
}

/**
 * A note under a card in secondary text, 12 dp below it and starting on the card's content line, 32 dp from the
 * screen's edge, where the settings pages start their footers.
 */
@Composable
internal fun RvCardNote(text: String, modifier: Modifier = Modifier) {
    val inset = RvSpace.gutter + RvSpace.rowPadding
    RvText(text, Rv.type.rowSub, modifier.padding(start = inset, end = inset, top = RvSpace.sectionBelow), color = Rv.colors.textSecondary, maxLines = NOTE_LINES)
}

private const val NOTE_LINES = 3

/** Where a rule sends: its group's name, or 直連. */
@Composable
internal fun RvPolicyRule.targetName(): String = group?.let { stringResource(it.name) } ?: stringResource(R.string.mode_direct)

/**
 * The 送往 list of the quick rule and the rule page: the groups a rule can send to, then 直連: the first [TARGETS]
 * groups, then the rest in place. A group is drawn by
 * where its traffic ends, as on 活動 and in the rules card.
 */
@Composable
internal fun RvTargetCard(targetId: String, onTarget: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val groups = listOf(RvPolicyData.main) + RvPolicyData.groups
    val shown = if (all) groups else groups.take(TARGETS)
    RvCard {
        shown.forEach { Target(it, targetId == it.id) { onTarget(it.id) } }
        Target(null, targetId == RV_DIRECT) { onTarget(RV_DIRECT) }
        if (!all) {
            val more = groups.size - shown.size
            RvSeeAll(pluralStringResource(R.plurals.rv_policy_other_groups, more, more)) { all = true }
        }
    }
}

/** One destination: the group and the node it uses, or 直連; the whole row picks it. */
@Composable
private fun Target(group: RvPolicyGroup?, selected: Boolean, onSelect: () -> Unit) {
    val pick = Modifier.selectable(selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.RadioButton, onClick = onSelect)
    RvRow(
        { RvPolicyTarget(group) },
        group?.let { stringResource(it.name) } ?: stringResource(R.string.mode_direct),
        group?.selected?.name ?: stringResource(R.string.rv_policy_no_proxy),
        pick,
    ) { RvRadio(selected) }
}

/** The groups listed before 其他 N 個策略組: the main node, 自動, 串流 and AI. */
private const val TARGETS = 4

private const val GEOIP = "GEOIP"
private const val GEOSITE = "GEOSITE"
