package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RV_NAV_POLICY
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvCardHeader
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvControlCard
import dev.doona.android.prototype.rv.RvControlRow
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvFloatingNav
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLatencyChange
import dev.doona.android.prototype.rv.RvPageHeader
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSeeAll
import dev.doona.android.prototype.rv.RvSegmented
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvSwitch
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.data.rvNodeGroups
import dev.doona.android.prototype.rv.nodes.RvNodeGroups
import dev.doona.android.prototype.rv.nodes.RvNodeListControls
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.nodes.latencyColor
import dev.doona.android.prototype.rv.nodes.latencyText
import dev.doona.android.prototype.rv.rvNavClearance
import dev.doona.android.prototype.rv.rvRowIndication
import dev.doona.android.prototype.rv.rvRowPress
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * Where the 策略 tab leads: a group's page, the main node's picker, all rules, the template, a new rule, the other tabs,
 * its two bar actions.
 */
internal class RvPolicyRoutes(
    val group: (String) -> Unit = {},
    val mainNode: () -> Unit = {},
    val rules: () -> Unit = {},
    val template: () -> Unit = {},
    val newRule: () -> Unit = {},
    val tab: (Int) -> Unit = {},
    val testAll: () -> Unit = {},
    val update: () -> Unit = {},
)

/**
 * The 策略 tab, a control page in the language of 設定 and 節點 (rv-policy-STATE.md has the synthesis behind it): the
 * large title with two glass actions in the bar; the mode as FlClash's three segments alone in a card. 規則: the main
 * node, which carries whatever no rule sends elsewhere (its card opens the main node's picker); the groups, each with
 * how it picks and the node it uses (a row pushes its page); the rules in order with today's bytes, as Karing lists
 * them; and the template and the switches that shape the rules. 全域 has one decision,
 * the node everything goes through, so under the mode the page is the node picker itself; 直連 shows the mode alone.
 */
@Composable
internal fun RvPolicyScreen(state: RvPolicyState, onChange: (RvPolicyState) -> Unit, routes: RvPolicyRoutes = RvPolicyRoutes()) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val title = stringResource(R.string.nav_policies)
    val testAll = stringResource(R.string.rv_policy_test_all)
    val update = stringResource(R.string.rv_policy_update_subscriptions)
    RvScrollPage(
        title,
        insets,
        scroll = scroll,
        actions = { glass ->
            RvGlassButton(glass, R.drawable.rv_sym_speed, testAll, routes.testAll)
            RvGlassButton(glass, R.drawable.rv_stats_ic_sync, update, routes.update)
        },
        overlay = { glass ->
            RvFloatingNav(RV_NAV_POLICY, routes.tab, glass, scroll, insets, Modifier.align(Alignment.BottomCenter))
        },
    ) { titleBlock ->
        RvPageHeader(title, titleBlock, insets)
        // 全域 sends everything through the main node: no group or rule applies, so only that card stays. 直連 uses no node.
        RvGutter {
            ModeCard(state.mode) { onChange(state.copy(mode = it)) }
            if (state.mode == RvRouteMode.Rule) {
                MainCard(state.group(RvPolicyData.main), routes.mainNode)
                GroupsCard(state, routes.group)
                RulesCard(routes.rules)
                OptionsCard(state, onChange, routes)
            }
        }
        if (state.mode == RvRouteMode.Global) GlobalPicker(state, onChange)
        // The last card clears the floating nav, as on 設定.
        Spacer(Modifier.height(rvNavClearance(insets)))
    }
}

/** 規則 | 全域 | 直連 alone in a card, as Revolut sets a segmented control. */
@Composable
private fun ModeCard(mode: RvRouteMode, onMode: (RvRouteMode) -> Unit) {
    RvControlCard(null) {
        RvSegmented(MODES.map { stringResource(it.label) }, MODES.indexOf(mode), { onMode(MODES[it]) })
    }
}

/** The main node (規則): the final group's choice, flagged as the node in use; the card opens the main node's picker. */
@Composable
private fun MainCard(main: RvPolicyGroup, onOpen: () -> Unit) {
    val node = main.selected
    RvCard {
        RvCardHeader(stringResource(R.string.rv_main_node), onClick = onOpen)
        RvRow(
            { RvFlag(node.place.name, RvSize.rowDisc, current = true) },
            node.name,
            listOf(stringResource(main.kind.label), node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR),
            onClick = onOpen,
        ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut) }
    }
}

/**
 * 全域's node picker: the node list's search and order, then every subscription's nodes (or every country's, under 地區);
 * a tap makes the node the main node.
 */
@Composable
private fun GlobalPicker(state: RvPolicyState, onChange: (RvPolicyState) -> Unit) {
    val main = state.group(RvPolicyData.main)
    val groups = remember(state.nodeSort) { rvNodeGroups(RvData.nodes, RvData.subscriptions, state.nodeSort) }
    RvControlRow { RvNodeListControls(state.nodeSort) { onChange(state.copy(nodeSort = it)) } }
    RvNodeGroups(groups, main.selectedId, state.nodeSort) { onChange(state.choose(main.id, it)) }
}

/** Every other group: its kind's glyph, its name over how it picks and how many nodes it has, and the node it uses. */
@Composable
private fun GroupsCard(state: RvPolicyState, onOpen: (String) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_groups))
        RvPolicyData.groups.forEach { GroupRow(state.group(it), onOpen) }
    }
}

@Composable
private fun GroupRow(group: RvPolicyGroup, onOpen: (String) -> Unit) {
    val node = group.selected
    val count = pluralStringResource(R.plurals.rv_policy_node_count, group.members.size, group.members.size)
    RvRow(
        { RvIconDisc(group.kind.glyph) },
        stringResource(group.name),
        stringResource(group.kind.label) + SEPARATOR + count,
        Modifier.rvRowPress(Role.Button, { onOpen(group.id) }),
        titleEnd = { RvText(node.name, Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = { RvText(latencyText(node.latencyMs), Rv.type.rowSub, color = latencyColor(node.latencyMs), align = TextAlign.End) },
        trailing = { RvChevron() },
    )
}

/**
 * The rules (規則 only): each one as what it matches over its kind, where the traffic ends (the group's node, or 直連's
 * arrow) and the group over today's bytes; then the rest of the list.
 */
@Composable
private fun RulesCard(onAll: () -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_rules))
        RvPolicyData.rules.forEach { RvRuleRow(it) }
        RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_rules, RvPolicyData.RULES, RvPolicyData.RULES), onAll)
    }
}

/** What shapes the rules: the template they come from, a new rule, and the two switches doona web offers with it. */
@Composable
private fun OptionsCard(state: RvPolicyState, onChange: (RvPolicyState) -> Unit, routes: RvPolicyRoutes) {
    RvCard {
        RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, stringResource(R.string.rv_policy_template), null, onClick = routes.template) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RvText(stringResource(R.string.rv_policy_template_value), Rv.type.rowSub, color = Rv.colors.textSecondary)
                Spacer(Modifier.width(RvSpace.tight))
                RvChevron()
            }
        }
        RvRow({ RvIconDisc(R.drawable.rv_sym_add) }, stringResource(R.string.rv_policy_new_rule), null, onClick = routes.newRule) { RvChevron() }
        SwitchRow(R.drawable.rv_sym_ad_off, stringResource(R.string.rv_policy_block_ads), state.blockAds) { onChange(state.copy(blockAds = it)) }
        SwitchRow(R.drawable.rv_sym_block, stringResource(R.string.rv_policy_block_quic), state.blockQuic) { onChange(state.copy(blockQuic = it)) }
    }
}

/** A row whose whole width toggles its switch, as on 設定. */
@Composable
private fun SwitchRow(@DrawableRes icon: Int, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val toggle = Modifier.toggleable(checked, interactionSource = null, indication = rvRowIndication(), role = Role.Switch, onValueChange = onChange)
    RvRow({ RvIconDisc(icon) }, title, null, toggle) { RvSwitch(checked) }
}

private val MODES = listOf(RvRouteMode.Rule, RvRouteMode.Global, RvRouteMode.Direct)

private val RvRouteMode.label: Int
    get() = when (this) {
        RvRouteMode.Rule -> R.string.rv_mode_rule
        RvRouteMode.Global -> R.string.mode_global
        RvRouteMode.Direct -> R.string.mode_direct
    }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPolicyScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(RvPolicyState()) }
    RvTheme(rvSystemScheme(dark), dark) { RvPolicyScreen(state, { state = it }) }
}
