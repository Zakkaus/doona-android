package dev.doona.android.feature.policies

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.app.nativeApi
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.text
import dev.doona.android.honk.HonkGroupConfig
import dev.doona.android.honk.HonkGroupOptions
import dev.doona.android.honk.HonkGroupPolicy

/**
 * The 策略 entry: builds the ViewModel and draws the RV page from its state while the screen is started. [onOpenGroup]
 * pushes a group's page and [onOpenSubscriptions] runs the empty state's 新增訂閱; [routes] carries the page's other
 * links (the main node's picker, all rules, the template, a new rule, the tabs, the search). The bar's two actions are the
 * ViewModel's, and a failed action shows as the page's banner with 重試 until an attempt succeeds (plan 8).
 */
@Composable
internal fun PoliciesRoute(engine: Engine, onOpenGroup: (String) -> Unit, onOpenSubscriptions: () -> Unit, routes: RvPolicyRoutes = RvPolicyRoutes()) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine, nativeApi()))
    PoliciesRoute(viewModel, onOpenGroup, onOpenSubscriptions, routes)
}

/** 策略 on a given [viewModel]: the page as [PoliciesRoute] draws it, for a caller (a test) that makes its own. */
@Composable
internal fun PoliciesRoute(viewModel: PoliciesViewModel, onOpenGroup: (String) -> Unit, onOpenSubscriptions: () -> Unit, routes: RvPolicyRoutes = RvPolicyRoutes()) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val profile = ui.profile
    if (creating && profile != null) {
        var name by rememberSaveable { mutableStateOf("") }
        var ids by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
        var kind by rememberSaveable { mutableStateOf(RvGroupPolicyKind.Selector.kind) }
        var default by rememberSaveable { mutableStateOf<String?>(null) }
        var final by rememberSaveable { mutableStateOf<String?>(null) }
        var picking by rememberSaveable { mutableStateOf<RvGroupOption?>(null) }
        val groups = listOfNotNull(profile.main) + profile.groups
        val all = groups.map { RvGroupMember(it.id, it.name.text(), true) } + profile.nodes.map { RvGroupMember(it.id, it.name, false, it.place.flag) }
        val byId = all.associateBy { it.id }
        val members = ids.mapNotNull(byId::get)
        val options = HonkGroupOptions(default, final)
        val draft = HonkGroupConfig(HonkGroupPolicy(kind, kind), options)
        val back = { if (picking != null) picking = null else creating = false }
        BackHandler(onBack = back)
        picking?.let { option ->
            return RvGroupOptionPage(option, members, draft, {
                default = it.config.defaultMemberId
                final = it.config.finalOutbound
            }, back)
        }
        return RvGroupEditPage(
            name, members, draft, draft, { kind = it.policy.kind }, back, {}, routes.config, { picking = it },
            onName = { name = it }, nameError = if (groups.any { it.name.text() == name.trim() }) stringResource(R.string.rv_group_name_taken) else null,
            available = all, onMembers = { selected ->
                ids = ArrayList(selected)
                default = default?.takeIf { it in selected }
                final = final?.takeIf { value -> value == "direct" || value == "block" || selected.any { byId[it]?.name == value } }
            },
        )
    }
    RvPolicyScreen(
        ui.state,
        viewModel::onChange,
        RvPolicyRoutes(
            group = onOpenGroup,
            mainNode = routes.mainNode,
            rules = routes.rules,
            template = routes.template,
            newRule = routes.newRule,
            tab = routes.tab,
            testAll = viewModel::testAll,
            update = viewModel::updateSubscriptions,
            search = routes.search,
            config = routes.config,
        ),
        noSubscription = onOpenSubscriptions.takeIf { ui.empty },
        loading = ui.loading,
        banner = ui.failure?.let { policyBanner(it, viewModel::retry) },
        profile = ui.profile,
        configRev = ui.configRev,
        groupsEdit = RvListEdit(onAdd = { creating = true }, onRemove = viewModel::removeGroup),
    )
}

/** The banner of a failed [failure]: what failed over the engine's reason, and 重試 ([onRetry]). */
@Composable
private fun policyBanner(failure: PolicyFailure, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    stringResource(failure.action.title),
    stringResource(failure.code.messageRes()),
    stringResource(R.string.rv_retry),
    onRetry,
)

/** What a failed action's banner is titled, as 活動's and the picker's failures are (pv-d M8). */
@get:StringRes
private val PolicyAction.title: Int
    get() = when (this) {
        PolicyAction.Read -> R.string.rv_policy_read_failed
        PolicyAction.Mode -> R.string.rv_failed_switch_mode
        PolicyAction.MainNode -> R.string.rv_failed_switch_node
        PolicyAction.TestAll -> R.string.rv_failed_test_latency
        PolicyAction.Ads -> R.string.rv_policy_failed_ads
        PolicyAction.Quic -> R.string.rv_policy_failed_quic
        PolicyAction.Update -> R.string.rv_policy_failed_update
        PolicyAction.RemoveGroup -> R.string.rv_config_apply_failed
    }
