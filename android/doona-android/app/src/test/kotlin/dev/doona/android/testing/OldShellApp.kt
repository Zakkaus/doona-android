package dev.doona.android.testing

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaAppShell
import dev.doona.android.app.GroupKey
import dev.doona.android.app.PerAppKey
import dev.doona.android.app.PoliciesKey
import dev.doona.android.app.SettingsKey
import dev.doona.android.app.SubscriptionsKey
import dev.doona.android.app.currentTab
import dev.doona.android.app.pop
import dev.doona.android.app.selectTab
import dev.doona.android.engine.Engine
import dev.doona.android.engine.InstalledApps
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.perapp.PerAppRoute
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.settings.SettingsRoute
import dev.doona.android.feature.subscriptions.SubscriptionsRoute

/**
 * The old pages in the old hub frame, as the app drew them before RV's navigation (wire-p1a): the old screen cases,
 * their goldens and the hub bar's layout tests describe these pages and go with them, lane by lane (plan §2.5, P7).
 * The app itself shows its pages in `RvNavHost`.
 */
@Composable
internal fun OldShellApp(engine: Engine, installedApps: InstalledApps, start: List<NavKey>) {
    val backStack = rememberNavBackStack(*start.toTypedArray())
    DoonaAppShell(current = currentTab(backStack), onSelect = { backStack.selectTab(it) }) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { backStack.pop(backStack.last()) },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<ActivityKey> {
                    // 活動 is the RV page now, with goldens of its own: the old shell shows only its first-run gate.
                    FirstRunGate(engine, onAdd = {}, frame = { it() }) {}
                }
                entry<PoliciesKey> {
                    PoliciesRoute(engine, onOpenGroup = { backStack.add(GroupKey(it)) }, onOpenSubscriptions = { backStack.add(SubscriptionsKey) })
                }
                entry<GroupKey> { key ->
                    GroupRoute(engine, key.groupId, onBack = { backStack.pop(key) }, onOpenGroup = { backStack.add(GroupKey(it)) })
                }
                entry<SubscriptionsKey> { key -> SubscriptionsRoute(engine, onBack = { backStack.pop(key) }, onOpen = {}, onAdd = {}) }
                entry<SettingsKey> {
                    SettingsRoute(engine, "", onOpenPerApp = { backStack.add(PerAppKey) }, onOpenSubscriptions = { backStack.add(SubscriptionsKey) })
                }
                entry<PerAppKey> { key -> PerAppRoute(engine, installedApps, onConnect = {}, onAddSubscription = {}, onCheckUpdate = {}, onBack = { backStack.pop(key) }) }
            },
        )
    }
}
