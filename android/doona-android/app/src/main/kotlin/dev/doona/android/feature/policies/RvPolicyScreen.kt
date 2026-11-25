package dev.doona.android.feature.policies

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NAV_POLICY
import dev.doona.android.designsystem.rv.RV_ROW_TITLE_LINES
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvControlCard
import dev.doona.android.designsystem.rv.RvControlRow
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvRowRemove
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchPill
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonChips
import dev.doona.android.designsystem.rv.RvSkeletonControlCard
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonHeader
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvTabHeader
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvRaisedReach
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.designsystem.rv.rvSkeletonRowsToFill
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvNodeGroups
import dev.doona.android.feature.model.text
import dev.doona.android.feature.nodes.ui.RvNodeGroups
import dev.doona.android.feature.nodes.ui.RvNodeListControls
import dev.doona.android.feature.nodes.ui.rememberRvNodeTitles

/**
 * Where the 策略 tab leads: a group's page, the main node's picker, all rules, the template, a new rule, the other tabs,
 * its two bar actions, and the configuration's overview (with the preview flag [RvPolicyScreen]'s `configRev`).
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
    val search: () -> Unit = {},
    val config: () -> Unit = {},
)

/**
 * The 策略 tab, a control page in the language of 設定 and 節點 (rv-policy-STATE.md has the synthesis behind it): the
 * tabs' bar, its capsule opening the node search ([RvPolicyRoutes.search]) and its two glass actions after it; the mode as FlClash's three segments in a card with one line saying
 * what it does. 規則: the main node, which carries whatever no rule sends elsewhere (its card opens the main node's
 * picker); the groups, each with how it picks and the node it uses (a row pushes its page); the rules in order with
 * today's bytes, as Karing lists them; and the template and the switches that shape the rules. 全域 has one decision,
 * the node everything goes through, so under the mode the page is the node picker itself; 直連 shows the mode alone.
 * With no subscription there is nothing to route: [noSubscription], when set, puts the empty state with 新增訂閱 (which
 * runs it) in place of the cards, as 活動 does; the bar and the nav stay. [configRev] is a preview flag (PV-f): set, 設定檔
 * joins the card under 範本 with the applied revision and opens the configuration's overview ([RvPolicyRoutes.config]).
 * While [loading] (before the engine is ready) the page is a skeleton of what its mode lays out ([PolicySkeleton]), and
 * the content fades in over it in place. A [banner] (a failed action: a mode switch) heads the cards in [RvBannerSlot],
 * outside the fade. What the profile holds (the main node, the groups, the rules, the nodes and subscriptions) is [profile];
 * before the first read there is none, and the skeleton stands with bars in place of the profile's words. Not loading
 * with no profile (the first read failed) shows the banner alone. With [groupsEdit] the groups' card under 規則 is an
 * editable list ([GroupsCard]). Without it the page is the approved one.
 */
@Composable
internal fun RvPolicyScreen(
    state: RvPolicyState,
    onChange: (RvPolicyState) -> Unit,
    routes: RvPolicyRoutes = RvPolicyRoutes(),
    noSubscription: (() -> Unit)? = null,
    configRev: Int? = null,
    loading: Boolean = false,
    banner: RvNotice? = null,
    profile: RvPolicyProfile?,
    groupsEdit: RvListEdit? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val title = stringResource(R.string.nav_policies)
    val testAll = stringResource(R.string.rv_policy_test_all)
    val update = stringResource(R.string.rv_policy_update_subscriptions)
    // The groups can be edited where they show: under 規則, with a profile and a subscription.
    val groupsEditable = groupsEdit?.takeIf { state.mode == RvRouteMode.Rule && noSubscription == null && profile != null && !loading }
    RvScrollPage(
        title,
        insets,
        scroll = scroll,
        actions = { glass ->
            RvGlassButton(glass, R.drawable.rv_sym_speed, testAll, routes.testAll)
            RvGlassButton(glass, R.drawable.rv_stats_ic_sync, update, routes.update)
        },
        field = { glass, modifier -> RvSearchPill(routes.search, modifier, glass) },
        overlay = { glass -> RvFloatingNav(RV_NAV_POLICY, routes.tab, glass, scroll, insets, Modifier.align(Alignment.BottomCenter)) },
        end = RvPageEnd.Nav,
    ) {
        RvTabHeader(insets)
        if (noSubscription != null) {
            RvGutter(banner = banner) {
                RvEmptyState(
                    stringResource(R.string.rv_empty_subscription),
                    stringResource(R.string.rv_empty_subscription_line),
                    stringResource(R.string.rv_add_subscription),
                    noSubscription,
                )
            }
        } else {
            RvBannerSlot(banner)
            RvSkeletonFade(loading, { Column { PolicySkeleton(state, profile) } }) {
                Column { profile?.let { PolicyContent(state, onChange, routes, configRev, it, groupsEditable) } }
            }
        }
    }
}

/**
 * What 策略 shows of the profile: the main node's group (null when the profile has none), every other group, the rules the card lists and how many the
 * profile has, the nodes and subscriptions 全域 lists, and now, for the subscriptions' end dates. The lists are copied
 * on construction, so a caller's mutable list cannot change the profile afterwards, and two profiles that list the same
 * nodes and subscriptions are equal in those.
 */
@ConsistentCopyVisibility
@Immutable
internal data class RvPolicyProfile private constructor(
    val main: RvPolicyGroup?,
    val groups: List<RvPolicyGroup>,
    val rules: List<RvPolicyRule>,
    val ruleCount: Int,
    val nodes: List<RvNode>,
    val subscriptions: List<RvSubscription>,
    val nowEpochMs: Long,
    val source: RvRuleSource,
) {
    companion object {
        operator fun invoke(
            main: RvPolicyGroup?,
            groups: List<RvPolicyGroup>,
            rules: List<RvPolicyRule>,
            ruleCount: Int,
            nodes: List<RvNode>,
            subscriptions: List<RvSubscription>,
            nowEpochMs: Long,
            source: RvRuleSource,
        ) = RvPolicyProfile(main, groups.toList(), rules.toList(), ruleCount, nodes.toList(), subscriptions.toList(), nowEpochMs, source)
    }
}

/**
 * 規則 | 全域 | 直連, each after its glyph, in a card, as Revolut sets a segmented control, and one line under it saying what
 * the mode does.
 */
@Composable
private fun ModeCard(mode: RvRouteMode, onMode: (RvRouteMode) -> Unit) {
    RvControlCard(stringResource(mode.line)) {
        RvSegmented(MODES.map { stringResource(it.label) }, MODES.indexOf(mode), { onMode(MODES[it]) }, glyphs = MODES.map { it.glyph })
    }
}

/**
 * The page under the bar as [RvPolicyState.mode] lays it out. 全域 sends everything through the main node: no group or rule
 * applies, so the node picker follows the mode card. 直連 uses no node: the mode card alone.
 */
@Composable
private fun PolicyContent(
    state: RvPolicyState,
    onChange: (RvPolicyState) -> Unit,
    routes: RvPolicyRoutes,
    configRev: Int?,
    profile: RvPolicyProfile,
    editing: RvListEdit? = null,
) {
    RvGutter {
        ModeCard(state.mode) { onChange(state.copy(mode = it)) }
        if (state.mode == RvRouteMode.Rule) {
            profile.main?.let { MainCard(state.group(it), routes.mainNode) }
            if (profile.groups.isNotEmpty() || editing != null) GroupsCard(state, profile, routes.group, editing)
            RulesCard(profile, routes.rules)
            OptionsCard(state, onChange, routes, configRev, profile.source)
        }
    }
    if (state.mode == RvRouteMode.Global) GlobalPicker(state, profile, onChange)
}

/**
 * [PolicyContent]'s skeleton in [state]'s mode's layout, each part on the lines of what replaces it: the mode card with
 * its caption; under 規則 the main node's card (its header, the node over its group, protocol and place, and its latency
 * and change) and the groups' card (its header, each group over how it picks and its nodes, its node, and its latency
 * before the chevron); under 全域 the order chips and a subscription's card (its name over its quota, the data left;
 * then its nodes, each with its latency and change at the ends of its lines); under 直連 nothing more. The profile's
 * words (the groups, their nodes, the subscription) are known before the engine is ready and take their lines, so a name
 * that wraps keeps its row's height; the figures are bars, and so are 全域's nodes, which their latency orders. The
 * groups' and the nodes' cards run past the window's foot
 * ([rvSkeletonRowsToFill]), as their lists do, so the content keeps the edges in view where they stood.
 */
@Composable
private fun PolicySkeleton(state: RvPolicyState, profile: RvPolicyProfile?) {
    val rows = rvSkeletonRowsToFill()
    val mode = state.mode
    RvGutter {
        RvSkeletonControlCard(stringResource(mode.line))
        if (mode == RvRouteMode.Rule) {
            val main = profile?.main?.let(state::group)
            RvSkeletonCard {
                RvSkeletonHeader(stringResource(R.string.rv_main_node))
                RvSkeletonRow(0, RvSkeletonText.Mixed, RvSkeletonEnd.Latency, title = main?.selected?.name, subtitleText = main?.let { mainLine(it) }, titleKeepWords = false)
            }
            RvSkeletonCard {
                RvSkeletonHeader(stringResource(R.string.rv_policy_groups))
                repeat(rows) {
                    val group = profile?.groups?.getOrNull(it)?.let(state::group)
                    RvSkeletonRow(
                        it + 1,
                        RvSkeletonText.Mixed,
                        RvSkeletonEnd.Node,
                        chevron = true,
                        title = group?.name?.text(),
                        subtitleText = group?.let { groupLine(it) },
                        endText = group?.selected?.name,
                        titleKeepWords = false,
                    )
                }
            }
        }
    }
    if (mode == RvRouteMode.Global) {
        RvControlRow({ RvSkeletonChips(RvNodeSort.entries.map { stringResource(it.label) }, state.nodeSort.ordinal, Modifier.padding(bottom = rvRaisedReach)) })
        RvGutter {
            RvSkeletonCard {
                // The first card is the first subscription's, but under 地區 the country of the fastest node's.
                val sub = profile?.subscriptions?.firstOrNull()?.name?.takeIf { state.nodeSort != RvNodeSort.Region }
                RvSkeletonRow(0, RvSkeletonText.Mixed, RvSkeletonEnd.Value, title = sub, titleKeepWords = false)
                repeat(rows) { RvSkeletonRow(it + 1, RvSkeletonText.Mixed, RvSkeletonEnd.LatencyLines) }
            }
        }
    }
}

/**
 * The main node (規則): the final group's choice, flagged as the node in use; the card opens the main node's picker. A
 * group whose choice leads to no node stands in its place, under its kind's glyph.
 */
@Composable
private fun MainCard(main: RvPolicyGroup, onOpen: () -> Unit) {
    val node = main.selected
    RvCard {
        RvCardHeader(stringResource(R.string.rv_main_node), onClick = onOpen)
        if (node == null) {
            RvRow({ RvIconDisc(main.kind.glyph, current = true) }, main.name.text(), mainLine(main), onClick = onOpen, titleKeepWords = false)
        } else {
            RvRow(
                { RvFlag(node.place.flag, RvSize.rowDisc, current = true) },
                node.name,
                mainLine(main),
                onClick = onOpen,
                titleKeepWords = false,
            ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut) }
        }
    }
}

/** The main node's line under its name: the group's kind, the node's protocol and its place. */
@Composable
private fun mainLine(main: RvPolicyGroup): String {
    val node = main.selected ?: return stringResource(main.kind.label)
    return listOf(stringResource(main.kind.label), node.protocol, stringResource(node.place.label)).joinToString(SEPARATOR)
}

/**
 * 全域's node picker: the node list's order (the search is the tab bar's), then every subscription's nodes (or every
 * country's, under 地區); a tap makes the node the main node.
 */
@Composable
private fun GlobalPicker(state: RvPolicyState, profile: RvPolicyProfile, onChange: (RvPolicyState) -> Unit) {
    val main = profile.main?.let(state::group)
    val titles = rememberRvNodeTitles()
    val groups = remember(state.nodeSort, profile.nodes, profile.subscriptions, titles) { rvNodeGroups(profile.nodes, profile.subscriptions, state.nodeSort, titles) }
    RvControlRow({ RvNodeListControls(state.nodeSort, { onChange(state.copy(nodeSort = it)) }, onSearch = null) })
    RvNodeGroups(groups, main?.selected?.id.orEmpty(), state.nodeSort, profile.nowEpochMs) { id -> main?.let { onChange(state.choose(it.id, id)) } }
}

/**
 * Every other group: its kind's glyph, its name over how it picks and how many nodes it has, and the node it uses. In
 * an editable list ([editing]) each row ends with the remove control, which asks in
 * [RvConfirmSheet] (saying which groups hold it as a member, as it leaves them too), and 新增群組 ends the card; with no
 * group left the card holds the add row alone.
 */
@Composable
private fun GroupsCard(state: RvPolicyState, profile: RvPolicyProfile, onOpen: (String) -> Unit, editing: RvListEdit?) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_groups))
        profile.groups.forEach { group -> GroupRow(state.group(group), onOpen, editing?.let { RvRowRemove { deleting = group.id } }) }
        if (editing != null) RvAddRow(stringResource(R.string.rv_group_new), editing.onAdd)
    }
    val target = deleting?.let { id -> profile.groups.firstOrNull { it.id == id } }
    val holders = target?.let { t -> (listOfNotNull(profile.main) + profile.groups).filter { g -> g.groups.any { it.id == t.id } } }.orEmpty()
    val name = target?.name?.text().orEmpty()
    val holderNames = holders.map { it.name.text() }.joinToString(stringResource(R.string.rv_effective_list_separator))
    RvConfirmSheet(
        visible = target != null,
        title = stringResource(R.string.rv_list_delete_title, name),
        body = if (holders.isEmpty()) stringResource(R.string.rv_group_delete_body) else stringResource(R.string.rv_group_delete_body_member, holderNames),
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

@Composable
private fun GroupRow(group: RvPolicyGroup, onOpen: (String) -> Unit, remove: RvRowRemove?) {
    val name = group.name.text()
    RvGroupRow(
        group.kind.glyph,
        name,
        groupLine(group),
        // In edit mode the row is the group to keep or remove: its node and latency give way, so the disc leaves the
        // name and its line one width and the row one height.
        group.selected.takeIf { remove == null },
        Modifier.rvRowPress(Role.Button, { onOpen(group.id) }),
        trailing = { RvChevron() },
        remove = remove,
        removeLabel = stringResource(R.string.rv_list_remove, name),
    )
}

/**
 * A group's line under its name: how it picks ([kind]), how many nodes it has and, when groups are among its members,
 * how many. A group of groups alone leaves the nodes out.
 */
@Composable
internal fun groupLine(group: RvPolicyGroup, kind: RvGroupKind = group.kind): String {
    val nodes = group.members.size
    val groups = group.groups.size
    return listOfNotNull(
        stringResource(kind.label),
        pluralStringResource(R.plurals.rv_policy_node_count, nodes, nodes).takeIf { nodes > 0 || groups == 0 },
        pluralStringResource(R.plurals.rv_policy_group_count, groups, groups).takeIf { groups > 0 },
    ).joinToString(SEPARATOR)
}

/**
 * The rules (規則 only): how they are tried, then each one as what it matches over its kind, where the traffic ends
 * (the group's node, or 直連's arrow) and the group over today's bytes; then the rest of the list.
 */
@Composable
private fun RulesCard(profile: RvPolicyProfile, onAll: () -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_policy_rules))
        RvText(stringResource(R.string.rv_policy_rules_order), Rv.type.rowSub, Modifier.padding(horizontal = RvSpace.rowPadding), color = Rv.colors.textSecondary)
        profile.rules.forEach { RvRuleRow(it) }
        RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_rules, profile.ruleCount, profile.ruleCount), onClick = onAll)
    }
}

/**
 * What shapes the rules: where they come from ([source]: a template, a subscription or the configuration file), the
 * configuration file's applied revision when [configRev] is set, a new rule, and the two switches doona web offers:
 * 封鎖廣告, and 阻擋 QUIC, an option of a template that shows only when the rules come from one.
 */
@Composable
private fun OptionsCard(state: RvPolicyState, onChange: (RvPolicyState) -> Unit, routes: RvPolicyRoutes, configRev: Int?, source: RvRuleSource) {
    RvCard {
        ValueRow(R.drawable.rv_sym_rule, stringResource(R.string.rv_policy_template), source.text(), routes.template)
        if (configRev != null) {
            ValueRow(R.drawable.rv_sym_edit, stringResource(R.string.rv_config_title), stringResource(R.string.rv_config_revision, configRev), routes.config)
        }
        RvAddRow(stringResource(R.string.rv_policy_new_rule), routes.newRule)
        RvSwitchRow(R.drawable.rv_sym_ad_off, stringResource(R.string.rv_policy_block_ads), state.blockAds) { onChange(state.copy(blockAds = it)) }
        if (source is RvRuleSource.Template) {
            RvSwitchRow(R.drawable.rv_sym_block, stringResource(R.string.rv_policy_block_quic), state.blockQuic) { onChange(state.copy(blockQuic = it)) }
        }
    }
}

/** A row that opens a page, with its current value before the chevron. */
@Composable
private fun ValueRow(@DrawableRes icon: Int, title: String, value: String, onClick: () -> Unit) {
    RvRow({ RvIconDisc(icon) }, title, null, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RvText(value, Rv.type.rowSub, color = Rv.colors.textSecondary)
            Spacer(Modifier.width(RvSpace.trailingGap))
            RvChevron()
        }
    }
}

private val MODES = listOf(RvRouteMode.Rule, RvRouteMode.Global, RvRouteMode.Direct)

private val RvRouteMode.label: Int
    get() = when (this) {
        RvRouteMode.Rule -> R.string.rv_mode_rule
        RvRouteMode.Global -> R.string.mode_global
        RvRouteMode.Direct -> R.string.mode_direct
    }

/**
 * A mode's glyph on the slider (look E): 規則 the rule glyph, 直連 the arrow 活動 and 連線 draw for direct traffic; the
 * library has no 全域 glyph, so the globe of 設定's language row stands in.
 */
private val RvRouteMode.glyph: Int
    get() = when (this) {
        RvRouteMode.Rule -> R.drawable.rv_sym_rule
        RvRouteMode.Global -> R.drawable.rv_settings_ic_language
        RvRouteMode.Direct -> R.drawable.rv_sym_arrow_right_alt
    }

private val RvRouteMode.line: Int
    get() = when (this) {
        RvRouteMode.Rule -> R.string.rv_policy_mode_rule
        RvRouteMode.Global -> R.string.rv_policy_mode_global
        RvRouteMode.Direct -> R.string.rv_policy_mode_direct
    }
