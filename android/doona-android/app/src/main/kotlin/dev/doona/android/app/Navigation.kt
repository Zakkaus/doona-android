package dev.doona.android.app

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NAV
import dev.doona.android.designsystem.rv.RV_NAV_ACTIVITY
import dev.doona.android.designsystem.rv.RV_NAV_POLICY
import dev.doona.android.designsystem.rv.RV_NAV_SETTINGS
import dev.doona.android.designsystem.rv.RvNavItem
import kotlinx.serialization.Serializable
import dev.doona.android.icons.R as IconsR

/**
 * A back stack key, one per page (plan §2.3). Keys are saved across process death under their class names, so they
 * carry ids only, never engine data: a page reads what it shows from the engine by the id. Sealed, so every key is
 * known to the serialization test; a new page adds its key here.
 */
@Serializable
sealed interface AppKey : NavKey

@Serializable
data object ActivityKey : AppKey

@Serializable
data object PoliciesKey : AppKey

@Serializable
data object SettingsKey : AppKey

@Serializable
data object TrafficKey : AppKey

@Serializable
data object StatsKey : AppKey

@Serializable
data object NodesKey : AppKey

@Serializable
data object NodeSearchKey : AppKey

@Serializable
data class NodeKey(val nodeId: String) : AppKey

@Serializable
data class GroupKey(val groupId: String) : AppKey

@Serializable
data object MainNodeChoiceKey : AppKey

@Serializable
data object RulesKey : AppKey

@Serializable
data object TemplateKey : AppKey

@Serializable
data class TemplateImpactKey(val templateId: String) : AppKey

@Serializable
data object EffectiveConfigKey : AppKey

@Serializable
data object SubscriptionsKey : AppKey

@Serializable
data object SubAddKey : AppKey

@Serializable
data class SubDetailKey(val subscriptionId: String) : AppKey

@Serializable
data object PerAppKey : AppKey

@Serializable
data object ReportKey : AppKey

@Serializable
data object AboutKey : AppKey

@Serializable
data object LanguageKey : AppKey

@Serializable
data object IntervalKey : AppKey

@Serializable
data object LabKey : AppKey

@Serializable
data object ThemeKey : AppKey

@Serializable
data object DnsKey : AppKey

@Serializable
data object LogLevelKey : AppKey

@Serializable
data object LicensesKey : AppKey

/** One licence's text, by its place in 授權's list. */
@Serializable
data class LicenseTextKey(val index: String) : AppKey

/**
 * The tabs of the floating nav, each with its root key and its id in the RV library's bar ([navId], an `RV_NAV_*`
 * value). 活動 is always the bottom of the back stack. 連線 (D31) waits for the connection list, so the app shows three.
 * [label] and [icon] are the old hub bar's, which the old pages' screenshot frame ([DoonaAppShell]) still draws.
 */
enum class Tab(val key: NavKey, internal val navId: Int, @StringRes val label: Int, @DrawableRes val icon: Int) {
    // Each tab shows its first page's icon (doona `src/shell/HubBar.tsx:33`, `src/shell/registry.ts`).
    Activity(ActivityKey, RV_NAV_ACTIVITY, R.string.hub_activity, IconsR.drawable.sym_speed),
    Policies(PoliciesKey, RV_NAV_POLICY, R.string.nav_policies, IconsR.drawable.sym_share),
    Settings(SettingsKey, RV_NAV_SETTINGS, R.string.hub_settings, IconsR.drawable.sym_description),
}

/** The floating nav's tabs in the app: RV's bar in its own order, without the tabs the app does not ship. */
internal val APP_NAV: List<RvNavItem> = RV_NAV.filter { item -> Tab.entries.any { it.navId == item.id } }

/**
 * The tab [backStack] is on, by position: the tab root directly above 活動, or 活動 itself. A page pushed from a tab
 * keeps that tab current, even one whose key is another tab's root (a page on 策略 opening 設定's key).
 */
fun currentTab(backStack: List<NavKey>): Tab = backStack.getOrNull(1)?.let(::tabOf) ?: Tab.Activity

/** The tab [key] is the root of, or null for a page pushed onto a tab. */
fun tabOf(key: NavKey): Tab? = Tab.entries.firstOrNull { it.key == key }

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

/**
 * Tapping the current tab again: pops every page above that tab's root and returns true, or returns false on the root
 * itself, where the bar scrolls the page to its top instead.
 */
fun MutableList<NavKey>.reselectTab(): Boolean {
    val root = if (currentTab(this) == Tab.Activity) 1 else 2
    if (size <= root) return false
    while (size > root) removeAt(lastIndex)
    return true
}

/**
 * Keeps the stack to pages the app has: if any key has no page ([routed] false), such as one another version of the
 * app saved, the stack falls back to its tab's root (to 活動 if that root has no page either).
 */
fun MutableList<NavKey>.keepRouted(routed: (NavKey) -> Boolean) {
    if (all(routed)) return
    val tab = currentTab(this)
    val root = if (tab != Tab.Activity && routed(tab.key)) 2 else 1
    while (size > root) removeAt(lastIndex)
}

/** [selectTab] by the RV bar's id ([RvNavItem.id]); an id the app does not ship changes nothing. */
internal fun MutableList<NavKey>.selectTab(navId: Int) {
    Tab.entries.firstOrNull { it.navId == navId }?.let { selectTab(it) }
}

/**
 * Pushes [key] unless it is already on top, so a double tap opens one page. A key already lower in the stack is not
 * pushed twice: the stack pops back to it, since two entries with one key would share their saved state.
 */
fun MutableList<NavKey>.push(key: NavKey) {
    val at = indexOf(key)
    if (at < 0) {
        add(key)
    } else {
        while (lastIndex > at) removeAt(lastIndex)
    }
}
