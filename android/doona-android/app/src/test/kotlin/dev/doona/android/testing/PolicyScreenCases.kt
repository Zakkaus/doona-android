package dev.doona.android.testing

import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaAppShell
import dev.doona.android.app.GroupKey
import dev.doona.android.app.PoliciesKey
import dev.doona.android.app.Tab
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.feature.policies.GroupScreen
import dev.doona.android.feature.policies.PoliciesScreen
import dev.doona.android.feature.policies.SAMPLE_PINNED_GROUPS
import dev.doona.android.feature.policies.SAMPLE_TESTING_GROUPS
import dev.doona.android.feature.policies.sampleGroupDetail
import dev.doona.android.feature.policies.samplePoliciesState

/**
 * The screens of the 策略 lane: the list, a group's page as a selector, as an automatic group and as one the user
 * pinned, and 活動 while the engine loads. The pages whose state the engine fixture cannot set are drawn in the app's
 * frame without a back stack. 訂閱 and the first run are RV pages now (`app-rv-sub*`, `app-rv-welcome*`).
 */
internal fun policyScreenCases(): List<ScreenCase> {
    val engine = sampleEngine()
    val apps = FakeInstalledApps()
    val groups = FakeEngine.SAMPLE_GROUPS
    val loading = FakeEngine(groups).apply { ready.value = false }
    val space = DoonaTheme.spacing
    val bars = setOf(LayoutRole.Sheet, LayoutRole.TopBar, LayoutRole.HubBar)
    val list = bars + setOf(LayoutRole.Card, LayoutRole.Segmented, LayoutRole.Mark)
    val listRhythm = listOf(space.afterTitle, space.section, space.section)
    val flatGroup = bars + setOf(LayoutRole.Button, LayoutRole.Dot)
    val group = flatGroup + LayoutRole.Tag
    val groupRhythm = listOf(space.item, space.section, space.section)
    return listOf(
        ScreenCase("policies", PageLayout(list, listRhythm)) { OldShellApp(engine, apps, listOf(ActivityKey, PoliciesKey)) },
        ScreenCase("policies-error", PageLayout(list + LayoutRole.Dot, listRhythm)) {
            DoonaAppShell(Tab.Policies, onSelect = {}) {
                PoliciesScreen(samplePoliciesState(SAMPLE_TESTING_GROUPS, ErrorCode.Busy), onAction = {}, onOpenGroup = {}, onOpenSubscriptions = {})
            }
        },
        ScreenCase("group", PageLayout(group, groupRhythm + space.block)) { OldShellApp(engine, apps, listOf(ActivityKey, PoliciesKey, GroupKey("g-Proxy"))) },
        ScreenCase("group-automatic", PageLayout(flatGroup, groupRhythm)) {
            DoonaAppShell(Tab.Policies, onSelect = {}) {
                GroupScreen(sampleGroupDetail("g-Auto", SAMPLE_TESTING_GROUPS.map { it.copy(testing = false) }), null, {}, {}, {})
            }
        },
        ScreenCase("group-pinned", PageLayout(flatGroup, groupRhythm)) {
            DoonaAppShell(Tab.Policies, onSelect = {}) { GroupScreen(sampleGroupDetail("g-Auto", SAMPLE_PINNED_GROUPS), ErrorCode.Busy, {}, {}, {}) }
        },
        ScreenCase("activity-loading", PageLayout(bars, listOf(space.section))) { OldShellApp(loading, apps, listOf(ActivityKey)) },
    )
}
