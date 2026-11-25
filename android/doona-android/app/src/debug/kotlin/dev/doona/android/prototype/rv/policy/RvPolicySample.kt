package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import dev.doona.android.designsystem.rv.RvNotice
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
internal fun RvPolicyScreen(
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
internal fun RvEffectiveConfigPage(
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
internal fun RvTemplatePage(current: RvTemplate, selected: RvTemplate, onSelect: (RvTemplate) -> Unit, onBack: () -> Unit, onReview: () -> Unit) =
    RvTemplatePage(current, selected, onSelect, onBack, onReview, RvTemplateData.all)
