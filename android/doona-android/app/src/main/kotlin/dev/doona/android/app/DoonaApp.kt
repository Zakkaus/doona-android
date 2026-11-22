package dev.doona.android.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.doona.android.designsystem.components.DoonaHubShell
import dev.doona.android.designsystem.components.DoonaHubTab
import dev.doona.android.engine.Engine
import dev.doona.android.feature.activity.ActivityRoute
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.settings.SettingsScreen
import dev.doona.android.feature.subscriptions.SubscriptionImports
import dev.doona.android.feature.subscriptions.SubscriptionsRoute
import dev.doona.android.feature.subscriptions.rememberSubscriptionImports

/**
 * The frame every page of the app sits in: the hub bar below it, or the hub rail beside it in a short window (see
 * [DoonaHubShell]). Exposed so a screen can be shown in the frame without a back stack, as the screenshot tests do.
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
 * The app shell: one back stack shown by [NavDisplay] in the [DoonaAppShell]. The back stack is saved with the activity,
 * so it survives configuration changes and process death; each entry gets its own ViewModels and saved state.
 * [start] must begin with [ActivityKey]. [imports] is the app-wide subscription import in flight; the default lives
 * as long as the composition.
 */
@Composable
fun DoonaApp(
    engine: Engine,
    onConnect: () -> Unit,
    start: List<NavKey> = listOf(ActivityKey),
    imports: SubscriptionImports = rememberSubscriptionImports(engine),
) {
    val backStack = rememberNavBackStack(*start.toTypedArray())
    DoonaAppShell(current = currentTab(backStack), onSelect = { backStack.selectTab(it) }) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { backStack.pop(backStack.last()) },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<ActivityKey> {
                    FirstRunGate(engine, imports) { ActivityRoute(engine, onConnect, onOpenGroup = { backStack.add(GroupKey(it)) }) }
                }
                entry<PoliciesKey> {
                    PoliciesRoute(engine, onOpenGroup = { backStack.add(GroupKey(it)) }, onOpenSubscriptions = { backStack.add(SubscriptionsKey) })
                }
                entry<GroupKey> { key ->
                    GroupRoute(engine, key.groupId, onBack = { backStack.pop(key) }, onOpenGroup = { backStack.add(GroupKey(it)) })
                }
                entry<SubscriptionsKey> { key -> SubscriptionsRoute(engine, imports, onBack = { backStack.pop(key) }) }
                entry<SettingsKey> { SettingsScreen() }
            },
        )
    }
}
