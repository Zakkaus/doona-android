package dev.doona.android.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import dev.doona.android.R
import kotlinx.serialization.Serializable
import dev.doona.android.icons.R as IconsR

// Back stack keys. They are saved across process death, so they carry ids only, never engine data.

@Serializable
data object ActivityKey : NavKey

@Serializable
data object PoliciesKey : NavKey

@Serializable
data object SettingsKey : NavKey

@Serializable
data class GroupKey(val groupId: String) : NavKey

@Serializable
data object SubscriptionsKey : NavKey

@Serializable
data object PerAppKey : NavKey

/** The hub bar's tabs. Each opens its root key; 活動 is always the bottom of the back stack. */
enum class Tab(val key: NavKey, @StringRes val label: Int, @DrawableRes val icon: Int) {
    // Each tab shows its first page's icon (doona `src/shell/HubBar.tsx:33`, `src/shell/registry.ts`).
    Activity(ActivityKey, R.string.hub_activity, IconsR.drawable.sym_speed),
    Policies(PoliciesKey, R.string.nav_policies, IconsR.drawable.sym_share),
    Settings(SettingsKey, R.string.hub_settings, IconsR.drawable.sym_description),
}

/** The tab whose root is nearest the top of [backStack]; a page pushed from a tab keeps that tab current. */
fun currentTab(backStack: List<NavKey>): Tab = Tab.entries.lastOrNull { it.key in backStack } ?: Tab.Activity

/**
 * Pops [key] only while it is on top, and never the root: a page that asks to leave twice, such as a second back
 * while its exit animates, must not pop the page underneath.
 */
fun MutableList<NavKey>.pop(key: NavKey) {
    if (size > 1 && last() == key) removeAt(lastIndex)
}

/**
 * Exit through home: switching tabs drops every page above 活動, so back from another tab returns to 活動 and back
 * from 活動 leaves the app. Selecting the current tab again returns to its root.
 */
fun MutableList<NavKey>.selectTab(tab: Tab) {
    while (size > 1) removeAt(lastIndex)
    if (tab != Tab.Activity) add(tab.key)
}
