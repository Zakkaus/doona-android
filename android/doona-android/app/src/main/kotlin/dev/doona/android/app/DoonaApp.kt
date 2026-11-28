package dev.doona.android.app

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.SubscriptionUpdate
import dev.doona.android.data.settings.UpdateCheck
import dev.doona.android.designsystem.components.DoonaHubShell
import dev.doona.android.designsystem.components.DoonaHubTab
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvOverDoona
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvNavClearance
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityRoute
import dev.doona.android.feature.activity.ActivityRoutes
import dev.doona.android.feature.activity.ConnectRefusals
import dev.doona.android.feature.connections.ConnectionRoute
import dev.doona.android.feature.connections.ConnectionsRoute
import dev.doona.android.feature.connections.ConnectionsViewModel
import dev.doona.android.feature.nodes.MainNodeRoute
import dev.doona.android.feature.nodes.NodeRoute
import dev.doona.android.feature.nodes.NodeSearchRoute
import dev.doona.android.feature.nodes.NodesPull
import dev.doona.android.feature.nodes.NodesRoute
import dev.doona.android.feature.nodes.NodesViewModel
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.perapp.PerAppRoute
import dev.doona.android.feature.policies.EffectiveConfigRoute
import dev.doona.android.feature.policies.GroupEditRoute
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.policies.RouteTraceRoute
import dev.doona.android.feature.policies.RuleEditRoute
import dev.doona.android.feature.policies.RulesRoute
import dev.doona.android.feature.policies.RvPolicyRoutes
import dev.doona.android.feature.policies.RvRulesRoutes
import dev.doona.android.feature.policies.ServiceRulesRoute
import dev.doona.android.feature.policies.TemplateImpactRoute
import dev.doona.android.feature.policies.TemplateRoute
import dev.doona.android.feature.stats.StatsRoute
import dev.doona.android.feature.subscriptions.SubAddRoute
import dev.doona.android.feature.subscriptions.SubDetailRoute
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.feature.subscriptions.SubscriptionsRoute
import dev.doona.android.feature.subscriptions.rememberSubscriptionImports
import dev.doona.android.feature.traffic.TrafficRoute
import dev.doona.android.report.CrashOffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The old hub bar's frame (see [DoonaHubShell]), which the old pages' screenshot cases still draw them in until the page
 * lanes delete those goldens (plan §2.5). The app itself frames its tab roots with RV's floating nav ([RvNavHost]).
 */
@Composable
fun DoonaAppShell(current: Tab, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    DoonaHubShell(
        tabs = Tab.entries.map { DoonaHubTab(stringResource(it.label), it.icon) },
        selected = current.ordinal,
        onSelect = { onSelect(Tab.entries[it]) },
        content = content,
    )
}

/**
 * The app shell: one back stack shown by [RvNavHost], RV's NavDisplay. The back stack is saved with the activity,
 * so it survives configuration changes and process death; each entry gets its own ViewModels and saved state.
 * [start] must begin with [ActivityKey]. [imports] is the app-wide subscription import in flight; the default lives
 * as long as the composition. [version] is the app's version name for the settings, read by the activity. The sheets
 * over every page are the activity's ([AppSheets]). [crashOffer] puts the crash notice on 活動; [network] (the
 * engine's by default) and [refusals] (`AppContainer.connectRefusals`) put its offline and refusal notices there;
 * [onSystemSettings] opens one of the system's settings pages, which the activity starts; [lanAsking] is whether the
 * local network prompt is up (one of [AppSheets]), so 新增訂閱 waits for it. A host without them refuses nothing, opens
 * no settings and never asks.
 */
@Composable
internal fun DoonaApp(
    engine: Engine,
    installedApps: InstalledApps,
    onConnect: () -> Unit,
    start: List<NavKey> = listOf(ActivityKey),
    imports: SubscriptionImports = rememberSubscriptionImports(nativeApi()),
    version: String = "",
    crashOffer: CrashOffer? = null,
    network: Flow<NetworkState> = engine.network,
    refusals: ConnectRefusals = remember { ConnectRefusals() },
    onSystemSettings: (SystemSettings) -> Unit = {},
    lanAsking: Flow<Boolean> = flowOf(false),
) {
    val backStack = rememberNavBackStack(*start.toTypedArray())
    val onTab: (Int) -> Unit = { backStack.selectTab(it) }
    val onReselect: () -> Boolean = { backStack.reselectTab() }
    // Held beside the root 活動 rather than in an entry: the pull-down and every 節點 page read one catalog, mapped once.
    val nodes: NodesViewModel = viewModel(factory = NodesViewModel.factory(nativeApi()))
    // The 連線 list and a connection's page read one list, held by the owner 節點's is held by; built when 連線 first shows.
    val owner = checkNotNull(LocalViewModelStoreOwner.current) { "DoonaApp runs inside a ViewModelStoreOwner" }
    val connections: @Composable () -> ConnectionsViewModel = { viewModel(owner, factory = ConnectionsViewModel.factory(nativeApi())) }
    val status by engine.status.collectAsStateWithLifecycle()
    RvOverDoona {
        RvNavHost(backStack, Modifier.fillMaxSize()) {
            entry<ActivityKey> {
                // The RV page draws its own floating nav; the gate's welcome page takes the old tab frame.
                val routes = remember(backStack, onSystemSettings) { activityRoutes(backStack, onSystemSettings) }
                FirstRunGate(engine, imports, onAdd = { backStack.push(SubAddKey) }, frame = { OldTabFrame(Tab.Activity, onTab, onReselect, it) }) {
                    NodesPull(nodes, RvPullMotion.Sheet, rememberRvPullState(), onDetail = { backStack.push(NodeKey(it)) }, onAdd = { backStack.push(SubAddKey) }) { look ->
                        ActivityRoute(engine, crashOffer, network, refusals, onConnect, routes, look)
                    }
                }
            }
            entry<PoliciesKey> {
                PoliciesRoute(
                    engine,
                    onOpenGroup = { backStack.push(GroupKey(it)) },
                    onOpenSubscriptions = { backStack.push(SubscriptionsKey) },
                    routes = RvPolicyRoutes(
                        mainNode = { backStack.push(MainNodeChoiceKey) },
                        rules = { backStack.push(RulesKey) },
                        template = { backStack.push(TemplateKey) },
                        config = { backStack.push(ConfigKey) },
                        tab = onTab,
                        search = { backStack.push(NodeSearchKey) },
                    ),
                )
            }
            entry<GroupKey> { key -> GroupRoute(key.groupId, onBack = { backStack.pop(key) }, onEdit = { backStack.push(GroupEditKey(key.groupId)) }) }
            entry<GroupEditKey> { key -> GroupEditRoute(key.groupId, onBack = { backStack.pop(key) }, onOpenSource = { backStack.push(ConfigSourceKey(it)) }) }
            entry<RulesKey> { key ->
                RulesRoute(
                    onBack = { backStack.pop(key) },
                    onTemplate = { backStack.push(TemplateKey) },
                    onOpen = { backStack.push(RuleEditKey(it)) },
                    onNew = { backStack.push(RuleEditKey(null)) },
                    onService = { backStack.push(ServiceRulesKey) },
                    routes = RvRulesRoutes(effective = { backStack.push(EffectiveConfigKey) }),
                )
            }
            entry<RuleEditKey> { key -> RuleEditRoute(key.ruleId, onBack = { backStack.pop(key) }, onOpenSource = { backStack.push(ConfigSourceKey(it)) }) }
            entry<TemplateKey> { key -> TemplateRoute(onBack = { backStack.pop(key) }, onReview = { backStack.push(TemplateImpactKey(it)) }) }
            entry<ServiceRulesKey> { key -> ServiceRulesRoute(onBack = { backStack.pop(key) }) }
            entry<TemplateImpactKey> { key ->
                TemplateImpactRoute(key.templateId, onBack = { backStack.pop(key) }, onApplied = { backStack.push(PoliciesKey) })
            }
            entry<EffectiveConfigKey> { key ->
                EffectiveConfigRoute(engine, onBack = { backStack.pop(key) }, onRules = { backStack.push(RulesKey) }, onTrace = { backStack.push(RouteTraceKey) })
            }
            entry<RouteTraceKey> { key -> RouteTraceRoute(onBack = { backStack.pop(key) }, onOpenSource = { backStack.push(ConfigSourceKey(it)) }) }
            configEntries(backStack)
            entry<SubscriptionsKey> { key ->
                SubscriptionsRoute(
                    onBack = { backStack.pop(key) },
                    onOpen = { backStack.push(SubDetailKey(it)) },
                    onAdd = { backStack.push(SubAddKey) },
                    onOpenNode = { backStack.push(NodeKey(it)) },
                    onViewGroups = { backStack.selectTab(Tab.Policies) },
                )
            }
            entry<SubAddKey> { key ->
                SubAddRoute(imports, lanAsking, onBack = { backStack.pop(key) })
            }
            entry<SubDetailKey> { key ->
                val context = LocalContext.current
                val updateHours = remember { AppSettings.get(context).subscriptionUpdate.map(::updateHoursOf) }
                SubDetailRoute(updateHours, key.subscriptionId, onBack = { backStack.pop(key) }, onAutoUpdate = { backStack.push(IntervalKey) })
            }
            // 設定 and its pages draw their own floating nav.
            settingsEntries(backStack, engine, version, onTab)
            entry<StatsKey> { key -> StatsRoute(onBack = { backStack.pop(key) }, onOpenReport = { backStack.push(ReportKey) }) }
            entry<PerAppKey> { key ->
                PerAppRoute(
                    engine,
                    installedApps,
                    onConnect,
                    onAddSubscription = { backStack.push(SubscriptionsKey) },
                    onCheckUpdate = rememberOpenReleases(),
                    onBack = { backStack.pop(key) },
                )
            }
            entry<TrafficKey> { key -> TrafficRoute(onBack = { backStack.pop(key) }) }
            entry<NodesKey> { key ->
                NodesRoute(
                    nodes,
                    onBack = { backStack.pop(key) },
                    onOpen = { backStack.push(NodeKey(it)) },
                    onAllNodes = { backStack.push(MainNodeChoiceKey) },
                    onAdd = { backStack.push(SubAddKey) },
                )
            }
            entry<NodeKey> { key -> NodeRoute(nodes, key.nodeId, onBack = { backStack.pop(key) }) }
            entry<NodeSearchKey> { key ->
                NodeSearchRoute(nodes, onClose = { backStack.pop(key) }, onDetail = { backStack.push(NodeKey(it)) }, onAdd = { backStack.push(SubAddKey) })
            }
            entry<ConnectionsKey> {
                ConnectionsRoute(connections(), connected = status is Status.Running, onOpen = { backStack.push(ConnectionKey(it)) }, onTab = onTab)
            }
            entry<ConnectionKey> { key ->
                ConnectionRoute(connections(), key.connectionId, onBack = { backStack.pop(key) }, onNewRule = { backStack.push(RulesKey) })
            }
            entry<MainNodeChoiceKey> { key ->
                MainNodeRoute(nodes, onBack = { backStack.pop(key) }, onSearch = { backStack.push(NodeSearchKey) }, onAdd = { backStack.push(SubAddKey) })
            }
        }
    }
}

/** The system settings pages the app opens; the activity starts each by its intent. */
enum class SystemSettings { Vpn, Network, Notifications }

/**
 * Where 活動 leads: the pages with an entry here, the 策略 tab for the mode, 設定 for 更多 and 檢查更新 (q-actmore), 回報問題
 * for the crash notice's 匯出報告, and the system's settings for the notices that need them.
 */
private fun activityRoutes(backStack: MutableList<NavKey>, onSystemSettings: (SystemSettings) -> Unit) = ActivityRoutes(
    traffic = { backStack.push(TrafficKey) },
    stats = { backStack.push(StatsKey) },
    search = { backStack.push(NodeSearchKey) },
    nodes = { backStack.push(NodesKey) },
    node = { backStack.push(NodeKey(it)) },
    tab = { backStack.selectTab(it) },
    mode = { backStack.selectTab(Tab.Policies) },
    rule = { backStack.push(GroupKey(it)) },
    rules = { backStack.push(RulesKey) },
    subscriptions = { backStack.push(SubscriptionsKey) },
    more = { backStack.selectTab(Tab.Settings) },
    addSubscription = { backStack.push(SubAddKey) },
    perApp = { backStack.push(PerAppKey) },
    checkUpdate = { backStack.selectTab(Tab.Settings) },
    exportReport = { received ->
        backStack.push(ReportKey)
        received()
    },
    networkSettings = { onSystemSettings(SystemSettings.Network) },
    notifySettings = { onSystemSettings(SystemSettings.Notifications) },
    vpnSettings = { onSystemSettings(SystemSettings.Vpn) },
)

/**
 * A tab root the RV page lanes have not replaced yet (plan §2.5): the old page above RV's floating nav, which RV's own
 * tab pages draw themselves. The page ends where the nav's clearance begins, so nothing it shows lies under the nav;
 * the nav's glass blurs the page's colour. The old page does not give the frame its scroll, so the nav does not fold
 * away. Tapping the selected tab does nothing here: the frame draws the nav on the tab's root only, so [onReselect] has
 * no page above the root to pop and returns false, and with no scroll the bar has nothing to move to the top.
 */
@Composable
private fun OldTabFrame(tab: Tab, onTab: (Int) -> Unit, onReselect: () -> Boolean, content: @Composable () -> Unit) {
    val glass = rememberRvGlass()
    val insets = DoonaTheme.insets
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .rvGlassSource(glass)
                .background(DoonaTheme.colors.page)
                .padding(bottom = rvNavClearance(insets))
                .consumeWindowInsets(insets.only(WindowInsetsSides.Bottom)),
        ) { content() }
        RvFloatingNav(tab.navId, onTab, glass, null, insets, Modifier.align(Alignment.BottomCenter), onReselect)
    }
}

/** 檢查更新 for a page in `feature/`, which may not reach `data/`: the releases page, opened from the app's activity. */
@Composable
private fun rememberOpenReleases(): () -> Unit {
    val activity = LocalActivity.current
    return remember<() -> Unit>(activity) { { activity?.let(UpdateCheck::openReleases) } }
}

/** A subscription page's auto-update interval in hours, or null while auto-update is off. */
internal fun updateHoursOf(update: SubscriptionUpdate): Int? = if (update.enabled) update.interval.duration.inWholeHours.toInt() else null
