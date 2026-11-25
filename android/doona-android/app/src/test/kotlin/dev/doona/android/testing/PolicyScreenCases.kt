package dev.doona.android.testing

import androidx.compose.runtime.remember
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaAppShell
import dev.doona.android.app.GroupKey
import dev.doona.android.app.PoliciesKey
import dev.doona.android.app.Tab
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.feature.policies.GroupScreen
import dev.doona.android.feature.policies.PoliciesScreen
import dev.doona.android.feature.policies.SAMPLE_PINNED_GROUPS
import dev.doona.android.feature.policies.SAMPLE_TESTING_GROUPS
import dev.doona.android.feature.policies.sampleGroupDetail
import dev.doona.android.feature.policies.samplePoliciesState
import dev.doona.android.feature.subscriptions.AddFormFields
import dev.doona.android.feature.subscriptions.AddFormState
import dev.doona.android.feature.subscriptions.SAMPLE_TROUBLED_LIST
import dev.doona.android.feature.subscriptions.SubscriptionsScreen
import dev.doona.android.feature.subscriptions.sampleAddFields
import dev.doona.android.feature.subscriptions.sampleSubscriptionsState

/**
 * The screens of the 策略, 訂閱 and first-run lanes: the list, a group's page as a selector, as an automatic group and
 * as one the user pinned, the subscriptions page with its states, and the first-run and loading pages. The pages
 * whose state the engine fixture cannot set are drawn in the app's frame without a back stack.
 */
internal fun policyScreenCases(): List<ScreenCase> {
    val engine = sampleEngine()
    val apps = FakeInstalledApps()
    val groups = FakeEngine.SAMPLE_GROUPS
    val noSubscriptions = FakeEngine(groups, subscriptions = emptyList())
    val noSubscriptionsFailed = FakeEngine(groups, subscriptions = emptyList(), status = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes))
    val noSubscriptionsStopped = FakeEngine(groups, subscriptions = emptyList(), status = Status.Failed(FailureReason.CoreStopped))
    val loading = FakeEngine(groups).apply { ready.value = false }
    val space = DoonaTheme.spacing
    val bars = setOf(LayoutRole.Sheet, LayoutRole.TopBar, LayoutRole.HubBar)
    val list = bars + setOf(LayoutRole.Card, LayoutRole.Segmented, LayoutRole.Mark)
    val listRhythm = listOf(space.afterTitle, space.section, space.section)
    val flatGroup = bars + setOf(LayoutRole.Button, LayoutRole.Dot)
    val group = flatGroup + LayoutRole.Tag
    val groupRhythm = listOf(space.item, space.section, space.section)
    val subscriptions = bars + setOf(LayoutRole.Card, LayoutRole.Button)
    val form = subscriptions + setOf(LayoutRole.Field, LayoutRole.Dot)
    val firstRun = bars + setOf(LayoutRole.Card, LayoutRole.Button, LayoutRole.Field)
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
        ScreenCase("subscriptions", PageLayout(subscriptions + LayoutRole.Bar, listOf(space.section))) {
            DoonaAppShell(Tab.Policies, onSelect = {}) { SubscriptionsScreen(sampleSubscriptionsState(), AddFormFields(), {}, {}) }
        },
        ScreenCase("subscriptions-error", PageLayout(subscriptions + LayoutRole.Dot, listOf(space.section))) {
            val state = sampleSubscriptionsState(SAMPLE_TROUBLED_LIST, updating = setOf("s2"), error = ErrorCode.Busy)
            DoonaAppShell(Tab.Policies, onSelect = {}) { SubscriptionsScreen(state, AddFormFields(), {}, {}) }
        },
        ScreenCase("subscriptions-empty", PageLayout(bars + LayoutRole.Button, listOf(space.section))) {
            DoonaAppShell(Tab.Policies, onSelect = {}) { SubscriptionsScreen(sampleSubscriptionsState(emptyList()), AddFormFields(), {}, {}) }
        },
        ScreenCase("subscriptions-add", PageLayout(form, listOf(space.section))) {
            val state = sampleSubscriptionsState(adding = true, form = AddFormState(attempted = true, error = ErrorCode.FetchFailed))
            DoonaAppShell(Tab.Policies, onSelect = {}) { SubscriptionsScreen(state, remember { sampleAddFields() }, {}, {}) }
        },
        ScreenCase("first-run", PageLayout(firstRun, listOf(space.section, space.section))) { OldShellApp(noSubscriptions, apps, listOf(ActivityKey)) },
        ScreenCase("first-run-hint", PageLayout(firstRun, listOf(space.section, space.section))) {
            OldShellApp(noSubscriptionsFailed, apps, listOf(ActivityKey))
        },
        ScreenCase("first-run-failed", PageLayout(firstRun, listOf(space.section, space.section))) {
            OldShellApp(noSubscriptionsStopped, apps, listOf(ActivityKey))
        },
        ScreenCase("activity-loading", PageLayout(bars, listOf(space.section))) { OldShellApp(loading, apps, listOf(ActivityKey)) },
    )
}
