package dev.doona.android.app

import android.app.Activity
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
import dev.doona.android.feature.activity.ActivityRoute
import dev.doona.android.feature.localnetwork.LocalNetworkHost
import dev.doona.android.feature.localnetwork.LocalNetworkPrompts
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.perapp.PerAppRoute
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.report.AppReportFacts
import dev.doona.android.feature.report.ReportRoute
import dev.doona.android.feature.settings.SettingsNav
import dev.doona.android.feature.settings.SettingsRoute
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.feature.subscriptions.SubscriptionsRoute
import dev.doona.android.feature.subscriptions.rememberSubscriptionImports

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
 * as long as the composition. [version] is the app's version name for the settings, read by the activity.
 * [localNetwork] shows the local network prompts over every page; a host without one never asks.
 */
@Composable
fun DoonaApp(
    engine: Engine,
    installedApps: InstalledApps,
    onConnect: () -> Unit,
    start: List<NavKey> = listOf(ActivityKey),
    imports: SubscriptionImports = rememberSubscriptionImports(engine),
    version: String = "",
    localNetwork: LocalNetworkHost? = null,
) {
    val backStack = rememberNavBackStack(*start.toTypedArray())
    val onTab: (Int) -> Unit = { backStack.selectTab(it) }
    val onReselect: () -> Boolean = { backStack.reselectTab() }
    RvOverDoona {
        RvNavHost(backStack, Modifier.fillMaxSize()) {
            entry<ActivityKey> {
                OldTabFrame(Tab.Activity, onTab, onReselect) {
                    FirstRunGate(engine, imports) { ActivityRoute(engine, onConnect, onOpenGroup = { backStack.push(GroupKey(it)) }) }
                }
            }
            entry<PoliciesKey> {
                OldTabFrame(Tab.Policies, onTab, onReselect) {
                    PoliciesRoute(engine, onOpenGroup = { backStack.push(GroupKey(it)) }, onOpenSubscriptions = { backStack.push(SubscriptionsKey) })
                }
            }
            entry<GroupKey> { key ->
                GroupRoute(engine, key.groupId, onBack = { backStack.pop(key) }, onOpenGroup = { backStack.push(GroupKey(it)) })
            }
            entry<SubscriptionsKey> { key -> SubscriptionsRoute(engine, imports, onBack = { backStack.pop(key) }) }
            entry<SettingsKey> {
                val context = LocalContext.current
                val container = (context.applicationContext as DoonaApplication).container
                val store = remember(container) { AppSettingsStore(container.application, AppSettings.get(container.application), container.quickTile) }
                val system = remember(context, container) { ActivitySettingsSystem(context as Activity, container.quickTile) }
                // The sub-pages' keys (關於, 語言, 更新間隔, 實驗室, 主題, DNS, 生效設定) are the orchestrator's to add.
                val nav = remember(backStack) {
                    SettingsNav(
                        perApp = { backStack.push(PerAppKey) },
                        dns = {},
                        effectiveConfig = {},
                        report = { backStack.push(ReportKey) },
                        about = {},
                        theme = {},
                        language = {},
                        interval = {},
                        lab = {},
                    )
                }
                SettingsRoute(engine, store, system, nav, version, onTab)
            }
            entry<ReportKey> { key ->
                val context = LocalContext.current
                val container = (context.applicationContext as DoonaApplication).container
                val facts = remember(context) { AppReportFacts(context) }
                ReportRoute(engine, container.reportExporter, facts, onBack = { backStack.pop(key) })
            }
            entry<PerAppKey> { key -> PerAppRoute(engine, installedApps, onBack = { backStack.pop(key) }) }
        }
    }
    localNetwork?.let { LocalNetworkPrompts(it) }
}

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
 * away, and tapping the selected tab does nothing: [onReselect] finds no page above the root, and there is no scroll.
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
