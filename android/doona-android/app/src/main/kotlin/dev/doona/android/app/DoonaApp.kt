package dev.doona.android.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.doona.android.designsystem.components.Hub
import dev.doona.android.feature.activity.ActivityScreen
import dev.doona.android.feature.policies.GroupScreen
import dev.doona.android.feature.policies.PoliciesScreen
import dev.doona.android.model.Demo

enum class Screen { Activity, Policies, Group }

/** The sketch's navigation: the 活動 and 路由 hubs, and a group page pushed from 策略. */
@Composable
fun DoonaApp(start: Screen = Screen.Activity) {
    var screen by remember { mutableStateOf(start) }
    val onHub: (Hub) -> Unit = { hub ->
        when (hub) {
            Hub.Activity -> screen = Screen.Activity
            Hub.Routing -> screen = Screen.Policies
            else -> Unit
        }
    }
    BackHandler(enabled = screen == Screen.Group) { screen = Screen.Policies }
    when (screen) {
        Screen.Activity -> ActivityScreen(Demo.activity, onHub)

        Screen.Policies -> PoliciesScreen(
            Demo.groups,
            Triple(Demo.GROUPS_ALL, Demo.GROUPS_MANUAL, Demo.GROUPS_AUTO),
            onOpen = { if (it.name == Demo.proxy.group.name) screen = Screen.Group },
            onHub = onHub,
        )

        Screen.Group -> GroupScreen(Demo.proxy, onBack = { screen = Screen.Policies }, onHub = onHub)
    }
}
