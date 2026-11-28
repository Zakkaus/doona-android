package dev.doona.android.feature.subscriptions

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.onboarding.FirstRunGate
import dev.doona.android.feature.onboarding.FirstRunViewModel
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

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

    /** The routes on [engine] and [honk], whose adds run on [io]. */
    private fun show(engine: Engine, vararg start: NavKey, honk: NativeApi = ProvidersHonk(), io: CoroutineDispatcher = Dispatchers.Unconfined) {
        compose.setContent { RvTheme(rvSystemScheme(false), false) { Host(engine, honk, io, start.toList().ifEmpty { listOf(ActivityKey) }) } }
    }

    /** Opens 新增訂閱 from 訂閱's 新增 menu. */
    private fun addFromSubscriptions() {
        compose.onNodeWithText(string(R.string.rv_list_create)).performClick()
        compose.onNodeWithText(string(R.string.rv_subs_add_subscription)).performClick()
    }

    /** Types [url] into 新增訂閱's link field, the first field that takes text. */
    private fun typeUrl() {
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput(url)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun catalogReadRetryPrecedesProviderRefresh() {
        val honk = HonkFixtures.rvSample().apply {
            on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/rv-sample-failed"))
            on("GET", "/api/v1/nodes", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        }
        show(FakeEngine(), ActivityKey, SubscriptionsKey, honk = honk)
        compose.onNodeWithText(string(R.string.rv_failed_read_nodes)).assertExists()
        honk.on("GET", "/api/v1/nodes", 200, HonkFixtures.body("nodes/rv-sample"))
        compose.onNodeWithText(string(R.string.rv_retry)).performClick()
        compose.onNodeWithText(string(R.string.rv_sub_failed_title, "Harbor Cloud")).assertExists()
    }

    @Test
    fun `a subscription is added from 訂閱 and removed from its menu`() {
        val honk = ProvidersHonk()
        show(FakeEngine(subscriptions = emptyList()), ActivityKey, SubscriptionsKey, honk = honk)
        compose.onNodeWithText(string(R.string.rv_empty_subscription)).assertExists()

        addFromSubscriptions()
        typeUrl()
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.runOnIdle { assertEquals(1, honk.providers.size) }
        compose.onNodeWithText(string(R.string.rv_done)).performClick()

        compose.onNodeWithText("example.invalid").performTouchInput { longClick() }
        compose.onNodeWithText(string(R.string.rv_menu_delete)).performClick()
        compose.onNodeWithText(string(R.string.rv_sub_delete_title, "example.invalid")).assertExists()
        compose.runOnIdle { assertEquals(1, honk.providers.size) }
        compose.onAllNodesWithText(string(R.string.rv_menu_delete)).onLast().performClick()
        compose.runOnIdle { assertTrue(honk.providers.isEmpty()) }
        compose.onNodeWithText(string(R.string.rv_empty_subscription)).assertExists()
    }

    @Test
    fun `a link with an upper-case scheme can be added, as honk reads it`() {
        val honk = ProvidersHonk()
        show(FakeEngine(subscriptions = emptyList()), ActivityKey, SubAddKey, honk = honk)
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput("HTTPS://example.invalid/x")
        compose.onNodeWithText(string(R.string.rv_add_import)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("HTTPS://example.invalid/x"), honk.added) }
    }

    @Test
    fun `新增訂閱 says under the link field why a link that is not http or https is refused`() {
        show(FakeEngine(subscriptions = emptyList()), ActivityKey, SubAddKey)
        compose.onNodeWithText(string(R.string.rv_add_link_invalid)).assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction() and isEnabled())[0].performTextInput("ftp://sub.example/x")
        compose.onNodeWithText(string(R.string.rv_add_link_invalid)).assertExists()
        compose.onNodeWithText(string(R.string.rv_add_import)).assertIsNotEnabled()
    }

    @Test
    fun `新增訂閱 keeps a name over the core's 64 code points as typed, says why under it, and does not add`() {
        show(FakeEngine(subscriptions = emptyList()), ActivityKey, SubAddKey)
        typeUrl()
        compose.onAllNodes(hasSetTextAction() and isEnabled())[1].performTextInput("a".repeat(70))
        compose.onNodeWithText("a".repeat(70)).assertExists()
        compose.onNodeWithText(string(R.string.rv_sub_name_invalid)).assertExists()
        compose.onNodeWithText(string(R.string.rv_add_import)).assertIsNotEnabled()
    }

    /** This honk publishes no node for what it adds, so the add does not hand over to 活動 (PolicyFlowsTest has one that does). */
    @Test
    fun `the first run's welcome page leads to 新增訂閱, whose import adds to honk`() {
        val honk = ProvidersHonk()
        show(FakeEngine(subscriptions = emptyList()), honk = honk)
        assertTrue("the welcome page", shows(string(R.string.rv_welcome_title)))
        compose.onAllNodesWithText(ACTIVITY).assertCountEquals(0)

        compose.onNodeWithText(string(R.string.rv_add_subscription)).performClick()
        typeUrl()
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.onNodeWithText(string(R.string.rv_done)).assertExists()
        compose.runOnIdle { assertEquals(listOf(url), honk.added) }
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
        val honk = ProvidersHonk(gate = CountDownLatch(1))
        show(FakeEngine(subscriptions = emptyList()), ActivityKey, SubscriptionsKey, honk = honk, io = Dispatchers.IO)
        addFromSubscriptions()
        typeUrl()
        compose.onNodeWithText(string(R.string.rv_add_import)).performClick()
        compose.waitUntil(READ_MS) { honk.adds == 1 }

        compose.onNodeWithContentDescription(string(R.string.ui_back)).performClick()
        addFromSubscriptions()
        // The import in flight is still the page's: it shows the import running, with no field or 新增 to start another.
        compose.onNodeWithText(string(R.string.rv_add_importing)).assertExists()
        compose.onAllNodes(hasSetTextAction() and isEnabled()).assertCountEquals(0)
        compose.onAllNodes(hasText(string(R.string.rv_add_import)) and isEnabled()).assertCountEquals(0)

        honk.gate!!.countDown()
        compose.waitUntil(READ_MS) { shows(string(R.string.rv_done)) }
        compose.runOnIdle {
            assertEquals("one add for the one import", 1, honk.adds)
            assertEquals(1, honk.providers.size)
        }
    }

    /** The subscription routes on their own entries, as DoonaApp routes them; 活動 is [ACTIVITY]. */
    @Composable
    private fun Host(engine: Engine, honk: NativeApi, io: CoroutineDispatcher, start: List<NavKey>) {
        val backStack = rememberNavBackStack(*start.toTypedArray())
        val scope = rememberCoroutineScope()
        val imports = remember { SubscriptionImports(honk, LocalNetworkAccess { true }, scope, io) }
        RvNavHost(backStack) {
            entry<ActivityKey> { FirstRunGate(remember { FirstRunViewModel(engine, NO_NODES, imports.state, Dispatchers.Unconfined) }, onAdd = { backStack.push(SubAddKey) }, frame = { it() }) { Text(ACTIVITY) } }
            entry<SubscriptionsKey> { key ->
                SubscriptionsRoute(
                    onBack = { backStack.pop(key) },
                    onOpen = { backStack.push(SubDetailKey(it)) },
                    onAdd = { backStack.push(SubAddKey) },
                    viewModel = remember { SubscriptionsViewModel(honk, io = Dispatchers.Unconfined) },
                    nodes = remember { AddNodeViewModel(honk, HeldClipboard(), io = Dispatchers.Unconfined) },
                )
            }
            entry<SubAddKey> { key ->
                val clipboard = LocalContext.current.applicationContext.getSystemService(ClipboardManager::class.java)
                val viewModel = remember { SubAddViewModel(honk, imports, AndroidClipboardLinks(clipboard), flowOf(false), io = Dispatchers.Unconfined) }
                SubAddRoute(imports, flowOf(false), onBack = { backStack.pop(key) }, viewModel = viewModel)
            }
            entry<SubDetailKey> { key ->
                val hours = MutableStateFlow(12)
                val viewModel = remember { SubDetailViewModel(honk, hours, key.subscriptionId, io = Dispatchers.Unconfined) }
                SubDetailRoute(hours, key.subscriptionId, onBack = { backStack.pop(key) }, onAutoUpdate = {}, viewModel = viewModel)
            }
        }
    }

    private companion object {
        const val ACTIVITY = "活動"

        /** honk with no node, so a profile of no subscription has none: the first run's welcome page shows. */
        val NO_NODES = FakeHonk().apply { on("GET", "/api/v1/nodes", 200, """{"nodes":[],"next_cursor":null}""") }

        /** How long an add on another thread may take to show. */
        const val READ_MS = 5_000L
    }
}

/**
 * honk keeping its providers as the test's [providers] (honk-shaped JSON objects): GET lists them, POST adds one named
 * as asked (each link in [added]; with a [gate], once it opens), DELETE removes one. It has no nodes.
 */
internal class ProvidersHonk(vararg providers: String, val gate: CountDownLatch? = null) : NativeApi {
    val providers = providers.toMutableList()
    val added = mutableListOf<String>()

    @Volatile
    var adds = 0

    override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
        if (method == "POST" && path == "/api/v1/providers") {
            adds++
            gate?.await()
        }
        return synchronized(this) { answer(method, path, body) }
    }

    private fun answer(method: String, path: String, body: String?): NativeResponse = when {
        method == "GET" && path == "/api/v1/providers" -> NativeResponse(200, """{"providers":[${providers.joinToString(",")}],"next_cursor":null}""")

        method == "GET" && path == "/api/v1/nodes" -> NativeResponse(200, """{"nodes":[],"next_cursor":null}""")

        method == "POST" && path == "/api/v1/providers" -> {
            val request = Json.parseToJsonElement(body!!).jsonObject
            val url = request.getValue("url").jsonPrimitive.content
            added += url
            provider("p${providers.size + 1}", request.getValue("name").jsonPrimitive.content, url).also { providers += it }.let { NativeResponse(201, it) }
        }

        method == "DELETE" && path.startsWith(PROVIDER) -> {
            val removed = providers.removeAll { it.contains(""""id":"${path.removePrefix(PROVIDER)}"""") }
            NativeResponse(200, """{"deleted":${if (removed) 1 else 0}}""")
        }

        else -> error("no reply for $method $path")
    }

    companion object {
        const val PROVIDER = "/api/v1/providers/"

        fun provider(id: String, name: String, url: String) = """{"id":"$id","name":"$name","kind":"subscription","url_redacted":"$url",""" +
            """"node_count":1,"updated_at":"2026-11-22T06:00:00Z","expires_at":null,"traffic":null,"status":"ok","last_error":null}"""
    }
}
