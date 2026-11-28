package dev.doona.android.feature.activity

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvChoiceLine
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode

/**
 * The outbound card (item 24), in the 最快節點 card's rows, four at most: the mode, then in
 * 規則 the main node and the two rule groups that sent the most traffic today, and 查看全部 for the rest; in 全域 the one
 * node; in 直連 a line that no node is in use.
 */
@Composable
internal fun OutboundCard(state: RvActivityState, mode: RvRouteMode, routes: RvActivityRoutes, nodes: List<RvNode>, rules: List<RvActivityRule>?, cards: RvActivityCards) {
    RvCard {
        RvRow({ RvIconDisc(R.drawable.rv_sym_directions) }, stringResource(R.string.act_mode), stringResource(mode.label), onClick = routes.mode) {
            if (routes.mode != null) RvChevron()
        }
        if (mode == RvRouteMode.Direct) {
            RvChoiceLine(stringResource(R.string.rv_direct_note), Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding))
            return@RvCard
        }
        // A node switch cross-fades the row to the new flag and name.
        Crossfade(state.node, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "main node") { node ->
            val listed = node?.let { at -> nodes.firstOrNull { it.id == at.id } }
            RvRow(
                // The node in use: its flag carries the current node's check.
                { RvFlag(listed?.place?.flag, RvSize.rowDisc, current = node != null) },
                node?.name ?: RV_NO_VALUE,
                stringResource(if (mode == RvRouteMode.Rule) R.string.rv_main_node else R.string.nav_nodes),
                onClick = node?.let { at -> { routes.node(at.id) } },
                titleKeepWords = false,
                // Its latency over the change since the test before, as 節點 and the 最快節點 rows show it (L-12).
            ) { RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed }) }
        }
        if (mode != RvRouteMode.Rule) return@RvCard
        if (rules == null) {
            RvRow({ RvIconDisc(groupGlyph(null)) }, RV_NO_VALUE, null)
        } else {
            rules.take(RULE_ROWS).forEach { RuleRow(it, cards, routes) }
        }
        RvSeeAll(pluralStringResource(R.plurals.rv_see_all_rules, cards.ruleCount, cards.ruleCount), onClick = routes.rules)
    }
}

/** The glyph of how a group picks ([kind]), as 策略 draws it; a selector's for a group 策略 does not list. */
private fun groupGlyph(kind: RvGroupKind?): Int = (kind ?: RvGroupKind.Select).glyph

/**
 * A rule group that sent traffic today: the node's flag, or for 直連 its own straight arrow (the diamond is the mode's), the
 * group (直連 when the rules send there themselves, then with no subtitle: [ruleRowText]), its node, its bytes (「—」 while
 * honk counts none per rule); a tap opens the group, or the rules for 直連. A group whose UDP uses another node (a sample) names both, a line each, each
 * after its network (`TCP Taipei 04`, `UDP Hong Kong 01`); when the two are in different places no one flag stands for
 * them, and the group's glyph (how it picks, as 策略 draws the group) leads the row ([ruleLead]).
 */
@Composable
private fun RuleRow(rule: RvActivityRule, cards: RvActivityCards, routes: RvActivityRoutes) {
    val format = rememberRvFormat()
    val node = rule.nodeId?.let(cards.node)
    val udp = rule.udpNodeId?.takeIf { it != rule.nodeId }?.let(cards.node)
    val direct = stringResource(R.string.mode_direct)
    val line = when {
        rule.groupId == null -> direct

        node == null -> udp?.let { stringResource(R.string.rv_network_node, stringResource(R.string.ui_udp), it.name) } ?: RV_NO_VALUE

        udp == null -> node.name

        else -> stringResource(R.string.rv_network_node, stringResource(R.string.ui_tcp), node.name) + "\n" +
            stringResource(R.string.rv_network_node, stringResource(R.string.ui_udp), udp.name)
    }
    val text = ruleRowText(rule.label, line, direct)
    RvRow(
        {
            when (ruleLead(rule.groupId, node, udp)) {
                RuleLead.Direct -> RvIconDisc(R.drawable.rv_sym_arrow_right_alt)
                RuleLead.GroupGlyph -> RvIconDisc(groupGlyph(rule.groupId?.let(cards.groupKind)))
                RuleLead.Flag -> RvFlag((node ?: udp)?.place?.flag, RvSize.rowDisc)
            }
        },
        text.title,
        text.subtitle,
        onClick = { rule.groupId?.let(routes.rule) ?: routes.rules() },
        titleKeepWords = false,
        subtitleKeepWords = false,
        // honk-gap:rule-bytes
    ) { RvText(rule.bytes?.let { format.bytes(it).toString() } ?: RV_NO_VALUE, Rv.type.rowValue, align = TextAlign.End) }
}

/** The outbound card lists two rule groups after the main node. */
internal const val RULE_ROWS = 2
