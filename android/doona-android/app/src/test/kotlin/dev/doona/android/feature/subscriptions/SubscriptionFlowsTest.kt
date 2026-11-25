package dev.doona.android.feature.subscriptions

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.RvNavHost
import dev.doona.android.app.SubAddKey
import dev.doona.android.app.SubDetailKey
import dev.doona.android.app.SubscriptionsKey
import dev.doona.android.app.pop
import dev.doona.android.app.push
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.onboarding.FirstRunGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 訂閱, 新增訂閱, a subscription's page and the first run's welcome page as a user moves through them, each route on its
 * own back stack entry as DoonaApp hosts them (the routing itself lands with the orchestrator's DoonaApp change; this
 * host stands for it). 活動 is a marker: the gate hands over to it.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val url = "https://example.invalid/x"

    private fun string(id: Int, vararg args: Any): String = ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    private fun show(engine: Engine, vararg start: NavKey) {
        compose.setContent { RvTheme(rvSystemScheme(false), false) { Host(engine, start.toList().ifEmpty { listOf(ActivityKey) }) } }
    }

    /** Types [url] into 新增訂閱's link field, the first field that takes text. */
    private fun typeUrl() {
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput(url)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `a subscription is added from 訂閱 and removed from its menu`() {
        val engine = FakeEngine(subscriptions = emptyList())
        show(engine, ActivityKey, SubscriptionsKey)
        compose.onNodeWithText(string(R.string.rv_empty_subscription)).assertExists()

        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        typeUrl()
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.runOnIdle { assertEquals(listOf(url), engine.subscriptions.value.map { it.url }) }
        compose.onNodeWithText(string(R.string.rv_done)).performClick()

        compose.onNodeWithText("example.invalid").performTouchInput { longClick() }
        compose.onNodeWithText(string(R.string.rv_menu_delete)).performClick()
        compose.onNodeWithText(string(R.string.rv_sub_delete_title, "example.invalid")).assertExists()
        compose.runOnIdle { assertEquals(1, engine.subscriptions.value.size) }
        compose.onAllNodesWithText(string(R.string.rv_menu_delete)).onLast().performClick()
        compose.runOnIdle { assertTrue(engine.subscriptions.value.isEmpty()) }
        compose.onNodeWithText(string(R.string.rv_empty_subscription)).assertExists()
    }

    @Test
    fun `a link with an upper-case scheme can be added, as the core reads it`() {
        val engine = FakeEngine(subscriptions = emptyList())
        show(engine, ActivityKey, SubAddKey)
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput("HTTPS://example.invalid/x")
        compose.onNodeWithText(string(R.string.rv_add_import)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("HTTPS://example.invalid/x"), engine.subscriptions.value.map { it.url }) }
    }

    @Test
    fun `新增訂閱's name field stops at the core's 64 code points`() {
        show(FakeEngine(subscriptions = emptyList()), ActivityKey, SubAddKey)
        compose.onAllNodes(hasSetTextAction() and isEnabled())[1].performTextInput("a".repeat(70))
        compose.onNodeWithText("a".repeat(64)).assertExists()
    }

    @Test
    fun `the first run's welcome page leads to 新增訂閱, and the first import hands over to 活動`() {
        val engine = FakeEngine(subscriptions = emptyList())
        show(engine)
        assertTrue("the welcome page", shows(string(R.string.rv_welcome_title)))
        compose.onAllNodesWithText(ACTIVITY).assertCountEquals(0)

        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        typeUrl()
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.onNodeWithText(string(R.string.rv_done)).performClick()

        compose.onNodeWithText(ACTIVITY).assertExists()
        assertTrue("no welcome page", !shows(string(R.string.rv_welcome_title)))
    }

    @Test
    fun `with no subscription a start that failed for want of nodes offers 新增訂閱`() {
        show(FakeEngine(subscriptions = emptyList(), status = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes)))
        assertTrue("the welcome page", shows(string(R.string.rv_welcome_title)))
        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        compose.onNodeWithText(string(R.string.rv_add_import)).assertExists()
    }

    @Test
    fun `leaving 新增訂閱 during a slow add and coming back imports once`() {
        val gate = CompletableDeferred<Unit>()
        val fake = FakeEngine(subscriptions = emptyList())
        var calls = 0
        val engine = object : Engine by fake {
            override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> {
                calls++
                gate.await()
                return fake.addSubscription(url, name, userAgent)
            }
        }
        show(engine, ActivityKey, SubscriptionsKey)
        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        typeUrl()
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.runOnIdle { assertEquals(1, calls) }

        compose.onNodeWithContentDescription(string(R.string.ui_back)).performClick()
        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        // The import in flight is still the page's; if the form is offered again, a second 新增 joins it.
        compose.onAllNodes(hasSetTextAction() and isEnabled()).fetchSemanticsNodes().firstOrNull()?.let { typeUrl() }
        if (compose.onAllNodes(hasText(string(R.string.rv_add_import)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        }

        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("one fetch for the one import", 1, calls)
            assertEquals(1, fake.subscriptions.value.size)
        }
    }

    /** The subscription routes on their own entries, as DoonaApp routes them; 活動 is [ACTIVITY]. */
    @Composable
    private fun Host(engine: Engine, start: List<NavKey>) {
        val backStack = rememberNavBackStack(*start.toTypedArray())
        val imports = rememberSubscriptionImports(engine)
        RvNavHost(backStack) {
            entry<ActivityKey> { FirstRunGate(engine, onAdd = { backStack.push(SubAddKey) }) { Text(ACTIVITY) } }
            entry<SubscriptionsKey> { key ->
                SubscriptionsRoute(
                    engine,
                    onBack = { backStack.pop(key) },
                    onOpen = { backStack.push(SubDetailKey(it)) },
                    onAdd = { backStack.push(SubAddKey) },
                )
            }
            entry<SubAddKey> { key -> SubAddRoute(engine, imports, onBack = { backStack.pop(key) }) }
            entry<SubDetailKey> { key -> SubDetailRoute(engine, MutableStateFlow(12), key.subscriptionId, onBack = { backStack.pop(key) }) }
        }
    }

    private companion object {
        const val ACTIVITY = "活動"
    }
}
