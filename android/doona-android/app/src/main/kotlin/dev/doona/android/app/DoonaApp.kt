package dev.doona.android.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import dev.doona.android.designsystem.components.HubBar
import dev.doona.android.designsystem.components.HubTab
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.feature.activity.ActivityRoute
import dev.doona.android.feature.policies.GroupRoute
import dev.doona.android.feature.policies.PoliciesRoute
import dev.doona.android.feature.settings.SettingsScreen

/**
 * The app shell: one back stack shown by [NavDisplay] over the hub bar. The back stack is saved with the activity,
 * so it survives configuration changes and process death; each entry gets its own ViewModels and saved state.
 * [start] must begin with [ActivityKey].
 */
@Composable
fun DoonaApp(engine: Engine, onConnect: () -> Unit, start: List<NavKey> = listOf(ActivityKey)) {
    val backStack = rememberNavBackStack(*start.toTypedArray())
    Column(Modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.weight(1f),
            onBack = { backStack.pop(backStack.last()) },
            entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
            entryProvider = entryProvider {
                entry<ActivityKey> { ActivityRoute(engine, onConnect, onOpenGroup = { backStack.add(GroupKey(it)) }) }
                entry<PoliciesKey> { PoliciesRoute(engine, onOpenGroup = { backStack.add(GroupKey(it)) }) }
                entry<GroupKey> { key -> GroupRoute(engine, key.groupId, onBack = { backStack.pop(key) }) }
                entry<SettingsKey> { SettingsScreen() }
            },
        )
        HubBar(
            tabs = Tab.entries.map { HubTab(stringResource(it.label), it.icon) },
            selected = currentTab(backStack).ordinal,
            onSelect = { backStack.selectTab(Tab.entries[it]) },
        )
    }
}
