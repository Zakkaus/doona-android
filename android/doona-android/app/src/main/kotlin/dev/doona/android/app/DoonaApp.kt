package dev.doona.android.app

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
import dev.doona.android.feature.settings.SettingsRoute
import dev.doona.android.feature.subscriptions.SubAddRoute
import dev.doona.android.feature.subscriptions.SubDetailRoute
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.feature.subscriptions.SubscriptionsRoute
import dev.doona.android.feature.subscriptions.rememberSubscriptionImports
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
                    FirstRunGate(engine, onAdd = { backStack.push(SubAddKey) }) { ActivityRoute(engine, onConnect, onOpenGroup = { backStack.push(GroupKey(it)) }) }
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
            entry<SubscriptionsKey> { key ->
                SubscriptionsRoute(
                    engine,
                    onBack = { backStack.pop(key) },
                    onOpen = { backStack.push(SubDetailKey(it)) },
                    onAdd = { backStack.push(SubAddKey) },
                )
            }
            entry<SubAddKey> { key ->
                val lanAsking = remember { localNetwork?.access?.prompt?.map { it != null } ?: flowOf(false) }
                SubAddRoute(engine, imports, lanAsking, onBack = { backStack.pop(key) })
            }
            entry<SubDetailKey> { key ->
                val context = LocalContext.current
                val updateHours = remember { AppSettings.get(context).subscriptionUpdate.map(::updateHoursOf) }
                // 更新間隔 (p6a's IntervalRoute) has no navigation key on this base; integ3 pushes it here.
                SubDetailRoute(engine, updateHours, key.subscriptionId, onBack = { backStack.pop(key) }, onAutoUpdate = {})
            }
            entry<SettingsKey> {
                OldTabFrame(Tab.Settings, onTab, onReselect) {
                    SettingsRoute(engine, version, onOpenPerApp = { backStack.push(PerAppKey) }, onOpenSubscriptions = { backStack.push(SubscriptionsKey) })
                }
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

/** A subscription page's auto-update interval in hours, or null while auto-update is off. */
internal fun updateHoursOf(update: SubscriptionUpdate): Int? = if (update.enabled) update.interval.duration.inWholeHours.toInt() else null
