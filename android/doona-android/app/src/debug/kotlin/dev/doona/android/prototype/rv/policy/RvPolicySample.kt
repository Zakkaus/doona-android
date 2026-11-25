package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRuleOutbound
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.settings.RvSettingsState

/** 策略's profile as [RvPolicyData] and [RvData] have it at 10:24. */
internal val rvSamplePolicyProfile = RvPolicyProfile(
    main = RvPolicyData.main,
    groups = RvPolicyData.groups,
    rules = RvPolicyData.rules,
    ruleCount = RvPolicyData.RULES,
    nodes = RvData.nodes,
    subscriptions = RvData.subscriptions,
    nowEpochMs = RvData.nowEpochMs,
)

/** 策略 on the sample: the page with [rvSamplePolicyProfile]. The page itself takes the profile as a parameter. */
@Composable
internal fun RvPolicyScreenSample(
    state: RvPolicyState,
    onChange: (RvPolicyState) -> Unit,
    routes: RvPolicyRoutes = RvPolicyRoutes(),
    noSubscription: (() -> Unit)? = null,
    configRev: Int? = null,
    loading: Boolean = false,
    banner: RvNotice? = null,
) = RvPolicyScreen(state, onChange, routes, noSubscription, configRev, loading, banner, rvSamplePolicyProfile)

/** Where the sample's quick rule and rule page can send: the main node's group, then every other group. */
internal val rvSampleTargets: List<RvPolicyGroup> = listOf(RvPolicyData.main) + RvPolicyData.groups

/** The effective configuration as the sample's profile has it: every rule, its template and both subscriptions. */
internal val rvSampleEffectiveConfig = RvEffectiveConfig(
    main = RvPolicyData.main,
    groups = RvPolicyData.groups,
    rules = RvPolicyData.allRules,
    template = RvTemplateData.cnDirect.name,
    subscriptions = RvData.subscriptions,
)

/** 生效設定 on the sample: the page with [rvSampleEffectiveConfig]. The page itself takes it as a parameter. */
@Composable
internal fun RvEffectiveConfigPageSample(
    policy: RvPolicyState,
    settings: RvSettingsState,
    onBack: () -> Unit,
    onRules: () -> Unit = {},
    onCopy: () -> Unit = {},
    onExport: () -> Unit = {},
    truncated: Boolean = false,
    banner: RvNotice? = null,
) = RvEffectiveConfigPage(policy, settings, onBack, onRules, onCopy, onExport, truncated, banner, rvSampleEffectiveConfig)

/** 範本 on the sample: the page with every template [RvTemplateData] has. The page itself takes the list as a parameter. */
@Composable
internal fun RvTemplatePageSample(current: RvTemplate, selected: RvTemplate, onSelect: (RvTemplate) -> Unit, onBack: () -> Unit, onReview: () -> Unit) =
    RvTemplatePage(current, selected, onSelect, onBack, onReview, RvTemplateData.all)

/**
 * 串流 (a selector) for the samples of choosing TCP and UDP apart: [shared] with the switch off, Taipei 04 on both
 * networks; [apart] with the switch on, Taipei 04 on TCP and Hong Kong 01 on UDP; [asked], the node whose network the
 * samples ask for; [rules], 活動's rule groups with 串流 apart; [rulesOneRegion], the same with UDP on another node in
 * TCP's region.
 */
internal object RvNetworkSample {
    val group: RvPolicyGroup = RvPolicyData.streaming
    val shared = RvNetworkChoice(group.selectedId, group.selectedId, apart = false)
    val apart = RvNetworkChoice(group.selectedId, UDP_NODE, apart = true)

    /** The switch on with both networks on one node, as after a tap that picked it for both. */
    val apartSame = RvNetworkChoice(group.selectedId, group.selectedId, apart = true)

    /** The fastest node neither network chose: the first such row on 節點's card. */
    val asked: RvNode = group.byLatency.first { !apart.chosen(it.id) }

    val rules: List<RvRuleOutbound> = rulesWithUdp(UDP_NODE)
    val rulesOneRegion: List<RvRuleOutbound> = rulesWithUdp(group.members.first { it.place == group.selected.place && it.id != group.selectedId }.id)

    private fun rulesWithUdp(udp: String) = RvData.ruleOutbounds.map {
        if (RvLabel.Res(it.group) == group.name) RvRuleOutbound(it.group, it.nodeId, it.bytes, udp) else it
    }
}

private const val UDP_NODE = "hk-01"
