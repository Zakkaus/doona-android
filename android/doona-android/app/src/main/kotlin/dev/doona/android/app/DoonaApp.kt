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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.SubscriptionUpdate
import dev.doona.android.data.settings.UpdateCheck
import dev.doona.android.designsystem.components.DoonaHubShell
import dev.doona.android.designsystem.components.DoonaHubTab
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvNavClearance
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.NetworkState
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityRoute
import dev.doona.android.feature.activity.ActivityRoutes
import dev.doona.android.feature.activity.ConnectRefusals
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.perapp.PerAppRoute
import dev.doona.android.feature.policies.EffectiveConfigRoute
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.policies.RulesRoute
import dev.doona.android.feature.policies.RvPolicyRoutes
import dev.doona.android.feature.policies.RvRulesRoutes
import dev.doona.android.feature.policies.TemplateImpactRoute
import dev.doona.android.feature.policies.TemplateRoute
import dev.doona.android.feature.settings.SettingsRoute
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
fun DoonaApp(
    engine: Engine,
    installedApps: InstalledApps,
    onConnect: () -> Unit,
    start: List<NavKey> = listOf(ActivityKey),
    imports: SubscriptionImports = rememberSubscriptionImports(engine),
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
    RvOverDoona {
        RvNavHost(backStack, Modifier.fillMaxSize()) {
            entry<ActivityKey> {
                // The RV page draws its own floating nav; the gate's welcome page takes the old tab frame.
                val routes = remember(backStack, onSystemSettings) { activityRoutes(backStack, onSystemSettings) }
                FirstRunGate(engine, onAdd = { backStack.push(SubAddKey) }, frame = { OldTabFrame(Tab.Activity, onTab, onReselect, it) }) {
                    ActivityRoute(engine, crashOffer, network, refusals, onConnect, routes)
                }
            }
            entry<PoliciesKey> {
                PoliciesRoute(
                    engine,
                    onOpenGroup = { backStack.push(GroupKey(it)) },
                    onOpenSubscriptions = { backStack.push(SubscriptionsKey) },
                    routes = RvPolicyRoutes(rules = { backStack.push(RulesKey) }, template = { backStack.push(TemplateKey) }, tab = onTab),
                )
            }
            entry<GroupKey> { key -> GroupRoute(engine, key.groupId, onBack = { backStack.pop(key) }) }
            entry<RulesKey> { key ->
                RulesRoute(
                    engine,
                    onBack = { backStack.pop(key) },
                    onTemplate = { backStack.push(TemplateKey) },
                    routes = RvRulesRoutes(effective = { backStack.push(EffectiveConfigKey) }),
                )
            }
            entry<TemplateKey> { key -> TemplateRoute(engine, onBack = { backStack.pop(key) }, onReview = { backStack.push(TemplateImpactKey(it)) }) }
            entry<TemplateImpactKey> { key ->
                TemplateImpactRoute(engine, key.templateId, onBack = { backStack.pop(key) }, onApplied = { backStack.push(PoliciesKey) })
            }
            entry<EffectiveConfigKey> { key -> EffectiveConfigRoute(engine, onBack = { backStack.pop(key) }, onRules = { backStack.push(RulesKey) }) }
            entry<SubscriptionsKey> { key ->
                SubscriptionsRoute(
                    engine,
                    onBack = { backStack.pop(key) },
                    onOpen = { backStack.push(SubDetailKey(it)) },
                    onAdd = { backStack.push(SubAddKey) },
                )
            }
            entry<SubAddKey> { key ->
                SubAddRoute(engine, imports, lanAsking, onBack = { backStack.pop(key) })
            }
            entry<SubDetailKey> { key ->
                val context = LocalContext.current
                val updateHours = remember { AppSettings.get(context).subscriptionUpdate.map(::updateHoursOf) }
                // 更新間隔 (p6a) has no entry yet, so the auto-update row is not wired.
                SubDetailRoute(engine, updateHours, key.subscriptionId, onBack = { backStack.pop(key) }, onAutoUpdate = NOT_WIRED)
            }
            entry<SettingsKey> {
                OldTabFrame(Tab.Settings, onTab, onReselect) {
                    SettingsRoute(engine, version, onOpenPerApp = { backStack.push(PerAppKey) }, onOpenSubscriptions = { backStack.push(SubscriptionsKey) })
                }
            }
            // 回報問題 (p6a) has no entry yet, so the export is not wired.
            entry<StatsKey> { key -> StatsRoute(engine, onBack = { backStack.pop(key) }, onOpenReport = NOT_WIRED) }
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
            entry<TrafficKey> { key -> TrafficRoute(engine, onBack = { backStack.pop(key) }) }
        }
    }
}

/** The system settings pages the app opens; the activity starts each by its intent. */
enum class SystemSettings { Vpn, Network, Notifications }

/**
 * Where 活動 leads: the pages with an entry here, the 策略 tab for the mode, 設定 for 更多 and 檢查更新 (q-actmore), and
 * the system's settings for the notices that need them. The pages without an entry yet (the node search and list, the
 * main node choice and the report) are not wired.
 */
private fun activityRoutes(backStack: MutableList<NavKey>, onSystemSettings: (SystemSettings) -> Unit) = ActivityRoutes(
    traffic = { backStack.push(TrafficKey) },
    stats = { backStack.push(StatsKey) },
    search = NOT_WIRED,
    nodes = NOT_WIRED,
    node = NOT_WIRED,
    tab = { backStack.selectTab(it) },
    mode = { backStack.selectTab(Tab.Policies) },
    rule = { backStack.push(GroupKey(it)) },
    rules = { backStack.push(RulesKey) },
    subscriptions = { backStack.push(SubscriptionsKey) },
    more = { backStack.selectTab(Tab.Settings) },
    addSubscription = { backStack.push(SubAddKey) },
    perApp = { backStack.push(PerAppKey) },
    checkUpdate = { backStack.selectTab(Tab.Settings) },
    exportReport = NOT_WIRED,
    networkSettings = { onSystemSettings(SystemSettings.Network) },
    notifySettings = { onSystemSettings(SystemSettings.Notifications) },
    vpnSettings = { onSystemSettings(SystemSettings.Vpn) },
)

/**
 * RV's tokens on the system scheme, for the floating nav and the host's motion, until MainActivity's theme provides
 * them (wire-p1b's AppTheme, which gives the old pages' [DoonaTheme] the same scheme); then this is a no-op to drop.
 */
@Composable
private fun RvOverDoona(content: @Composable () -> Unit) {
    val dark = DoonaTheme.colors.dark
    RvTheme(rvSystemScheme(dark), dark, content)
}

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
