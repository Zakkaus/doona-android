package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.localnetwork.LocalNetworkEngine
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAddViewModelTest {
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
    private fun TestScope.page(engine: Engine, imports: SubscriptionImports, clip: ClipboardLinks = Clip()) = SubAddViewModel(engine, imports, clip).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun TestScope.imports(engine: Engine) = SubscriptionImports(engine, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))

    private fun SubAddViewModel.type(link: String = LINK) = edit(uiState.value.copy(link = link))

    /** [FakeEngine], whose imports wait for [gate] and then answer [answers] in turn (a success when they run out). */
    private class Slow(val fake: FakeEngine = FakeEngine(subscriptions = emptyList()), val answers: MutableList<ErrorCode> = mutableListOf()) : Engine by fake {
        var gate = CompletableDeferred(Unit)
        var calls = 0

        override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> {
            calls++
            gate.await()
            answers.removeFirstOrNull()?.let { return Result.failure(EngineException(it)) }
            return fake.addSubscription(url, name, userAgent)
        }
    }

    @Test
    fun `an import outlives its page and the next page follows it with what was typed`() = runTest {
        val engine = Slow().apply { gate = CompletableDeferred() }
        val imports = imports(engine)
        page(engine, imports).apply {
            type()
            edit(uiState.value.copy(name = "Harbor", userAgent = "clash.meta"))
            import()
            assertEquals(RvAddPhase.Importing(null, null), uiState.value.phase)
        }
        // The first page is gone; a new one opens while the fetch still runs.
        val again = page(engine, imports)
        assertEquals(RvAddPhase.Importing(null, null), again.uiState.value.phase)
        assertEquals(Triple(LINK, "Harbor", "clash.meta"), again.uiState.value.let { Triple(it.link, it.name, it.userAgent) })
        again.import()
        assertEquals("a second import does not start while one runs", 1, engine.calls)
        engine.gate.complete(Unit)
        val done = again.uiState.value.phase as RvAddPhase.Done
        assertEquals("Harbor", done.sub.name)
        assertEquals("clash.meta", engine.fake.subscriptions.value.single().userAgent)
    }

    @Test
    fun `an import that ended well while no page showed it leaves the next page empty`() = runTest {
        val engine = Slow()
        val imports = imports(engine)
        page(engine, imports).apply {
            type()
            import()
        }
        val again = page(engine, imports)
        assertEquals(RvAddState(clipboardHasLink = true), again.uiState.value)
    }

    @Test
    fun `each error code maps to its reason`() = runTest {
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
        )
        assertEquals(ErrorCode.entries.toSet(), expected.keys)
        expected.forEach { (code, failure) ->
            val engine = Slow(answers = mutableListOf(code))
            val vm = page(engine, imports(engine)).apply {
                type()
                import()
            }
            assertEquals(code.name, RvAddPhase.Failed(failure), vm.uiState.value.phase)
            assertEquals("the fields keep what was typed", LINK, vm.uiState.value.link)
        }
    }

    @Test
    fun `retry imports again and a success ends with what came in`() = runTest {
        val engine = Slow(answers = mutableListOf(ErrorCode.FetchFailed))
        val vm = page(engine, imports(engine)).apply {
            type()
            import()
        }
        assertEquals(RvAddPhase.Failed(RvAddFailure.Unreachable), vm.uiState.value.phase)
        vm.import()
        assertTrue(vm.uiState.value.phase is RvAddPhase.Done)
        assertEquals(2, engine.calls)
    }

    @Test
    fun `nothing is imported while the link or the User-Agent is not valid`() = runTest {
        val engine = Slow()
        val vm = page(engine, imports(engine))
        vm.type("ftp://x.example/s")
        vm.import()
        vm.type()
        vm.edit(vm.uiState.value.copy(userAgent = "a\nb"))
        vm.import()
        assertEquals(0, engine.calls)
    }

    @Test
    fun `the clipboard is read only when its row is tapped`() = runTest {
        val clip = Clip()
        val engine = Slow()
        val vm = page(engine, imports(engine), clip)
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
        val engine = Slow()
        val vm = page(engine, imports(engine), clip)
        vm.paste()
        assertEquals(RvAddState(clipboardHasLink = false), vm.uiState.value)
        clip.hasLink = false
        vm.refreshClipboard()
        assertEquals(false, vm.uiState.value.clipboardHasLink)
    }

    @Test
    fun `a link on the local network asks, and once allowed the import runs again`() = runTest {
        val engine = Slow(answers = mutableListOf(ErrorCode.LocalNetwork))
        val access = LocalNetworkAccess(granted = { false })
        val lan = LocalNetworkEngine(engine, access, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))
        val vm = page(lan, imports(lan)).apply {
            type(LAN)
            import()
        }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        assertEquals(RvAddPhase.Importing(null, null), vm.uiState.value.phase)
        assertTrue(access.requesting())
        access.answered(allowed = true)
        assertTrue(vm.uiState.value.phase is RvAddPhase.Done)
        assertEquals(2, engine.calls)
    }

    @Test
    fun `turned down at doona's reason, the import fails as unreachable and is not tried again`() = runTest {
        val engine = Slow(answers = mutableListOf(ErrorCode.LocalNetwork))
        val access = LocalNetworkAccess(granted = { false })
        val lan = LocalNetworkEngine(engine, access, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))
        val vm = page(lan, imports(lan)).apply {
            type(LAN)
            import()
        }
        access.declined()
        assertEquals(null, access.prompt.value)
        assertEquals(RvAddPhase.Failed(RvAddFailure.Unreachable), vm.uiState.value.phase)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `refused in Android's dialog, the refusal shows and the import fails once it is closed`() = runTest {
        val engine = Slow(answers = mutableListOf(ErrorCode.LocalNetwork))
        val access = LocalNetworkAccess(granted = { false })
        val lan = LocalNetworkEngine(engine, access, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))
        val vm = page(lan, imports(lan)).apply {
            type(LAN)
            import()
        }
        assertTrue(access.requesting())
        access.answered(allowed = false)
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        assertEquals("it waits on the refusal", RvAddPhase.Importing(null, null), vm.uiState.value.phase)
        access.dismissed()
        assertEquals(RvAddPhase.Failed(RvAddFailure.Unreachable), vm.uiState.value.phase)
        assertEquals(1, engine.calls)
    }

    @Test
    fun `what came in is counted by protocol`() = runTest {
        assertEquals(
            listOf(RvProtocolCount("VLESS", 2), RvProtocolCount("Trojan", 1), RvProtocolCount("mystery", 1)),
            rvProtocolCounts(listOf("trojan", "vless", "mystery", "vless")),
        )
    }

    /**
     * The done card counts every node the catalog holds from the new subscription, those no group lists included (a
     * user's own groups need not name them), and only once the catalog has taken the import in.
     */
    @Test
    fun `the done card counts the catalog's nodes of the subscription once the catalog holds them`() = runTest {
        val fake = FakeEngine(subscriptions = emptyList())
        val added = Subscription("s9", "Harbor", LINK, 3, 0L)
        val engine = object : Engine by fake {
            override suspend fun addSubscription(url: String, name: String?, userAgent: String?) = Result.success(added)

            override suspend fun nodes(ids: List<String>?) = Result.success(
                listOf(node("a", "s9", "vless"), node("b", "s1", "ss"), node("c", "s9", "trojan"), node("d", null, "ss"), node("e", "s9", "vless")),
            )
        }
        val vm = page(engine, imports(engine))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.protocols.collect {} }
        vm.type()
        vm.import()
        assertTrue(vm.uiState.value.phase is RvAddPhase.Done)
        assertEquals("not before the catalog lists the subscription", emptyList<RvProtocolCount>(), vm.protocols.value)
        fake.subscriptions.value = listOf(added)
        assertEquals(listOf(RvProtocolCount("VLESS", 2), RvProtocolCount("Trojan", 1)), vm.protocols.value)
    }

    private fun node(id: String, subscription: String?, protocol: String) = NodeInfo(id, subscription, protocol, "$id.example", 443, "tcp", true, emptyList())

    private companion object {
        const val LINK = "https://sub.example/api?token=1"
        const val LAN = "http://192.168.1.20/sub.yaml"
    }
}
