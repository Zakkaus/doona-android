package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 新增訂閱 adding to honk, which answers each add with the provider or with one of its refusals. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubAddViewModelTest {
    private val honk = FakeHonk().apply {
        on("POST", "/api/v1/providers", 201, provider("s9", "Harbor"))
        on("GET", "/api/v1/nodes", 200, """{"nodes":[],"next_cursor":null}""")
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** The clipboard: whether it may hold a link, and how often its content was read. */
    private class Clip(var hasLink: Boolean = true, val content: String? = LINK) : ClipboardLinks {
        var reads = 0

        override fun mayHoldLink() = hasLink

        override fun link(): String? {
            reads++
            return content
        }
    }

    /** A page over [imports], collected as the page collects it. */
    private fun TestScope.page(imports: SubscriptionImports = imports(), clip: ClipboardLinks = Clip()) =
        SubAddViewModel(honk, imports, clip, lanAsking = flowOf(false), io = UnconfinedTestDispatcher(testScheduler)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    /** The app's import holder; on a [slow] dispatcher an add runs only once the test lets the clock run. */
    private fun TestScope.imports(slow: Boolean = false) = SubscriptionImports(
        honk,
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)),
        if (slow) StandardTestDispatcher(testScheduler) else UnconfinedTestDispatcher(testScheduler),
    )

    private fun SubAddViewModel.type(link: String = LINK) = edit(uiState.value.copy(link = link))

    private fun adds() = honk.calls.filter { it.method == "POST" && it.path == "/api/v1/providers" }

    /** The last add's request [field]. */
    private fun sent(field: String) = Json.parseToJsonElement(adds().last().body!!).jsonObject[field]?.jsonPrimitive?.content

    @Test
    fun `an import outlives its page and the next page follows it with what was typed`() = runTest {
        val imports = imports(slow = true)
        page(imports).apply {
            type()
            edit(uiState.value.copy(name = "Harbor", userAgent = "clash.meta"))
            import()
            assertEquals(RvAddPhase.Importing(null, null), uiState.value.phase)
        }
        // The first page is gone; a new one opens while the add still runs.
        val again = page(imports)
        assertEquals(RvAddPhase.Importing(null, null), again.uiState.value.phase)
        assertEquals(Triple(LINK, "Harbor", "clash.meta"), again.uiState.value.let { Triple(it.link, it.name, it.userAgent) })
        again.import()
        advanceUntilIdle()
        assertEquals("a second import does not start while one runs", 1, adds().size)
        val done = again.uiState.value.phase as RvAddPhase.Done
        assertEquals("Harbor", done.sub.name)
        assertEquals("clash.meta", done.sub.userAgent)
        assertEquals(listOf("Harbor", "subscription", LINK, "clash.meta"), listOf("name", "kind", "url", "user_agent").map(::sent))
    }

    @Test
    fun `a link added with no name is named by its host, as honk requires a name`() = runTest {
        page().apply {
            type()
            import()
        }
        assertEquals("sub.example", sent("name"))
        assertEquals(null, sent("user_agent"))
    }

    @Test
    fun `an import that ended well while no page showed it leaves the next page empty`() = runTest {
        val imports = imports()
        page(imports).apply {
            type()
            import()
        }
        val again = page(imports)
        assertEquals(RvAddState(clipboardHasLink = true), again.uiState.value)
    }

    @Test
    fun `each error code maps to its reason`() {
        val expected = mapOf(
            ErrorCode.TooLarge to RvAddFailure.TooLarge,
            ErrorCode.ParseFailed to RvAddFailure.NotSubscription,
            ErrorCode.NoNodes to RvAddFailure.NotSubscription,
            ErrorCode.FetchFailed to RvAddFailure.Unreachable,
            ErrorCode.DnsFailed to RvAddFailure.Unreachable,
            ErrorCode.LocalNetwork to RvAddFailure.Unreachable,
            ErrorCode.InvalidArgument to RvAddFailure.Unreachable,
            ErrorCode.NotFound to RvAddFailure.Unreachable,
            ErrorCode.Busy to RvAddFailure.Unreachable,
            ErrorCode.Internal to RvAddFailure.Unreachable,
            ErrorCode.StorageFull to RvAddFailure.NotSaved(ErrorCode.StorageFull),
            ErrorCode.WriteFailed to RvAddFailure.NotSaved(ErrorCode.WriteFailed),
        )
        assertEquals(ErrorCode.entries.toSet(), expected.keys)
        expected.forEach { (code, failure) -> assertEquals(code.name, failure, rvAddFailure(code)) }
    }

    @Test
    fun `honk's refusals of an add show their reasons and keep what was typed`() = runTest {
        val answers = listOf(
            Triple(409, CONFLICT, RvAddFailure.Unreachable),
            Triple(413, CONFLICT, RvAddFailure.TooLarge),
            Triple(422, CONFLICT, RvAddFailure.Unreachable),
            Triple(202, failedFetch("parse_failed"), RvAddFailure.NotSubscription),
            Triple(202, failedFetch("connect"), RvAddFailure.Unreachable),
        )
        for ((status, body, failure) in answers) {
            if (status == 202) {
                honk.on("POST", "/api/v1/providers", 202, """{"operation_id":"op-add","kind":"provider_create","status":"queued","href":"/api/v1/operations/op-add"}""")
                honk.on("GET", "/api/v1/operations/op-add", 200, body)
            } else {
                honk.on("POST", "/api/v1/providers", status, body)
            }
            val vm = page().apply {
                type()
                import()
            }
            advanceUntilIdle()
            assertEquals("$status", RvAddPhase.Failed(failure), vm.uiState.value.phase)
            assertEquals("the fields keep what was typed", LINK, vm.uiState.value.link)
        }
    }

    @Test
    fun `retry imports again and a success ends with what came in`() = runTest {
        honk.on("POST", "/api/v1/providers", 503, CONFLICT)
        val vm = page().apply {
            type()
            import()
        }
        assertEquals(RvAddPhase.Failed(RvAddFailure.Unreachable), vm.uiState.value.phase)
        honk.on("POST", "/api/v1/providers", 201, provider("s9", "Harbor"))
        vm.import()
        assertTrue(vm.uiState.value.phase is RvAddPhase.Done)
        assertEquals(2, adds().size)
    }

    @Test
    fun `nothing is imported while the link, the name or the User-Agent is not valid`() = runTest {
        val vm = page()
        vm.type("ftp://x.example/s")
        vm.import()
        vm.type()
        vm.edit(vm.uiState.value.copy(name = "a".repeat(65)))
        vm.import()
        vm.edit(vm.uiState.value.copy(name = "", userAgent = "a\nb"))
        vm.import()
        assertEquals(0, adds().size)
    }

    @Test
    fun `the clipboard is read only when its row is tapped`() = runTest {
        val clip = Clip()
        val vm = page(clip = clip)
        vm.refreshClipboard()
        assertTrue(vm.uiState.value.clipboardHasLink)
        assertEquals(0, clip.reads)
        vm.paste()
        assertEquals(1, clip.reads)
        assertEquals(LINK, vm.uiState.value.link)
    }

    @Test
    fun `a clipboard without a link hides its row when tapped`() = runTest {
        val clip = Clip(content = null)
        val vm = page(clip = clip)
        vm.paste()
        assertEquals(RvAddState(clipboardHasLink = false), vm.uiState.value)
        clip.hasLink = false
        vm.refreshClipboard()
        assertEquals(false, vm.uiState.value.clipboardHasLink)
    }

    @Test
    fun `what came in is counted by protocol`() = runTest {
        assertEquals(
            listOf(RvProtocolCount("VLESS", 2), RvProtocolCount("Trojan", 1), RvProtocolCount("mystery", 1)),
            rvProtocolCounts(listOf("trojan", "vless", "mystery", "vless")),
        )
    }

    /**
     * The done card counts every node honk holds from the new provider, those no group lists included (a user's own
     * groups need not name them).
     */
    @Test
    fun `the done card counts honk's nodes of the new provider`() = runTest {
        val nodes = listOf(node("a", "s9", "vless"), node("b", "s1", "shadowsocks"), node("c", "s9", "trojan"), node("d", null, "shadowsocks"), node("e", "s9", "vless"))
        honk.on("GET", "/api/v1/nodes", 200, """{"nodes":[${nodes.joinToString(",")}],"next_cursor":null}""")
        val vm = page()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.protocols.collect {} }
        vm.type()
        vm.import()
        assertTrue(vm.uiState.value.phase is RvAddPhase.Done)
        assertEquals(listOf(RvProtocolCount("VLESS", 2), RvProtocolCount("Trojan", 1)), vm.protocols.value)
    }

    private companion object {
        const val LINK = "https://sub.example/api?token=1"
        val CONFLICT = HonkFixtures.body("errors/temporarily-unavailable")

        fun provider(id: String, name: String) = """{"id":"$id","name":"$name","kind":"subscription","url_redacted":"$LINK",""" +
            """"node_count":3,"updated_at":"2026-11-22T06:00:00Z","expires_at":null,"traffic":null,"status":"ok","last_error":null}"""

        fun node(id: String, provider: String?, protocol: String) =
            """{"id":"$id","name":"$id","protocol":"$protocol","subscription_tag":null,"provider_id":${provider?.let { "\"$it\"" }},"group_ids":[],"health":[]}"""

        /** A `provider_create` operation that failed fetching at [stage]. */
        fun failedFetch(stage: String) = """{"operation_id":"op-add","kind":"provider_create","status":"failed","created_at":"2026-11-22T06:00:00Z",""" +
            """"started_at":"2026-11-22T06:00:00Z","finished_at":"2026-11-22T06:00:01Z","result":null,""" +
            """"error":{"code":"fetch_failed","message":"The subscription could not be fetched.","details":{"stage":"$stage"}}}"""
    }
}
