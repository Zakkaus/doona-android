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
import dev.doona.android.engine.InstalledApps
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityRoute
import dev.doona.android.feature.perapp.PerAppRoute
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.settings.SettingsRoute

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
 * [start] must begin with [ActivityKey].
 */
@Composable
fun DoonaApp(
    engine: Engine,
    installedApps: InstalledApps,
    onConnect: () -> Unit,
    start: List<NavKey> = listOf(ActivityKey),
    version: String = "",
) {
    val backStack = rememberNavBackStack(*start.toTypedArray())
    DoonaAppShell(current = currentTab(backStack), onSelect = { backStack.selectTab(it) }) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { backStack.pop(backStack.last()) },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<ActivityKey> { ActivityRoute(engine, onConnect, onOpenGroup = { backStack.add(GroupKey(it)) }) }
                entry<PoliciesKey> { PoliciesRoute(engine, onOpenGroup = { backStack.add(GroupKey(it)) }) }
                entry<GroupKey> { key -> GroupRoute(engine, key.groupId, onBack = { backStack.pop(key) }) }
                entry<SettingsKey> {
                    // 訂閱 is the policies-and-subscriptions lane's page; its row opens nothing until that lane wires it.
                    SettingsRoute(engine, version, onOpenPerApp = { backStack.add(PerAppKey) }, onOpenSubscriptions = NOT_WIRED)
                }
                entry<PerAppKey> { key -> PerAppRoute(engine, installedApps, onBack = { backStack.pop(key) }) }
            },
        )
    }
}
