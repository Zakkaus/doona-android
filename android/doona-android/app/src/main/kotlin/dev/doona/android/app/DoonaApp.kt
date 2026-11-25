package dev.doona.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import dev.doona.android.designsystem.components.DoonaHubShell
import dev.doona.android.designsystem.components.DoonaHubTab
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvOverDoona
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvNavClearance
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
            // The RV pages draw their own floating nav.
            settingsEntries(backStack, engine, version, onTab)
            entry<PerAppKey> { key -> PerAppRoute(engine, installedApps, onBack = { backStack.pop(key) }) }
        }
    }
    localNetwork?.let { LocalNetworkPrompts(it) }
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
