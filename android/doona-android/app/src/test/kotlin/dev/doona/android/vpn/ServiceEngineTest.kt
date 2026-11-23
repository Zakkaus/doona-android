package dev.doona.android.vpn

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Mode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ServiceEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = FakeCore()
    private val requested = CopyOnWriteArrayList<Long>()

    /** Where the engine reaches the core; set before the engine is first used. */
    private var io: CoroutineDispatcher = Dispatchers.IO

    /** Sends a requested start to the service; the system may find none or refuse it. */
    @Volatile
    private var dispatch: (Long) -> Boolean = { requested.add(it) }
    private val perAppFile by lazy { folder.root.resolve("per_app.preferences_pb") }
    private val perAppStore by lazy { PerAppSettings.store(scope) { perAppFile } }

    /** Fails the next read of the per-app policy, as a disk error would. */
    @Volatile
    private var failNextRead = false

    /** Released for each per-app policy the store hands the engine, before the engine decodes it. */
    private val perAppReads = Semaphore(0)

    /** Runs once the store has written, then the writer reaches a cancellation point, as when its caller leaves meanwhile. */
    @Volatile
    private var afterWrite: (() -> Unit)? = null

    private val engine by lazy {
        val opener = CoreOpener(core, "/data", Dispatchers.IO)
        val store = object : DataStore<Preferences> by perAppStore {
            override val data = flow {
                if (failNextRead) {
                    failNextRead = false
                    throw IOException("read")
                }
                emitAll(perAppStore.data.onEach { perAppReads.release() })
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val written = perAppStore.updateData(transform)
                afterWrite?.let { hook ->
                    afterWrite = null
                    hook()
                    yield()
                }
                return written
            }
        }
        val perAppSettings = PerAppSettings(store)
        val controller = TunnelController(core, opener, { perAppSettings.perApp.first() }, scope, killProcess = {})
        ServiceEngine(core, opener, controller, perAppSettings, { dispatch(it) }, io, scope, perAppRetryMs = 50)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun diagnosticsSendsItsLevelAndKeepsTheReply() = runBlocking {
        core.reply = ContractFixtures.read("call-ok-diagnostics.json")
        val diagnostics = engine.diagnostics(LogLevel.Warn).getOrThrow()
        assertEquals("diagnostics" to """{"level":"warn"}""", core.calls.single())
        assertEquals(LogLevel.Info, diagnostics.level)
        assertEquals(listOf("1795300150000.txt"), diagnostics.crashes.map { it.name })
    }

    @Test
    fun aCommandSendsTypedArgsAndRefreshesTheCatalog() = runBlocking {
        assertFalse(engine.ready.value)
        assertTrue(engine.setMode(Mode.Direct).isSuccess)
        assertEquals("setMode" to """{"mode":"Direct"}""", core.calls.single())
        assertTrue(engine.ready.value)
        assertEquals(listOf("g-Proxy", "g-Auto", "g-Streaming", "g-AI", "g-HK"), engine.groups.value.map { it.id })
        assertEquals(listOf("s1"), engine.subscriptions.value.map { it.id })
    }

    @Test
    fun aSecondTapBeforeTheServiceRunsDisconnects() = runBlocking {
        scope.launch(Dispatchers.IO.limitedParallelism(1)) { engine.controller.run() }
        engine.connect()
        assertEquals(Status.Starting, engine.status.value)
        engine.connect()
        assertEquals(1, requested.size)
        engine.disconnect()
        // The service's start intent arrives after the disconnect.
        assertFalse(engine.controller.start(NoHost, requested.single()))
        withTimeout(5_000) { engine.status.first { it == Status.Stopped } }
        Unit
    }

    @Test
    fun aStartWithNoServiceToTakeItEnds() = runBlocking {
        scope.launch(Dispatchers.IO.limitedParallelism(1)) { engine.controller.run() }
        dispatch = { false }
        engine.connect()
        withTimeout(5_000) { engine.status.first { it == Status.Stopped } }
        Unit
    }

    @Test
    fun aStartTheSystemRefusesEnds() = runBlocking {
        scope.launch(Dispatchers.IO.limitedParallelism(1)) { engine.controller.run() }
        dispatch = { throw SecurityException("refused") }
        assertThrows(SecurityException::class.java) { engine.connect() }
        withTimeout(5_000) { engine.status.first { it == Status.Stopped } }
        Unit
    }

    @Test
    fun aMissingLibraryFailsTheEngineInsteadOfCrashingIt() = runBlocking {
        // The JVM has no libdoonacore.so, as when packaging dropped it.
        val engine = ServiceEngine.create(ApplicationProvider.getApplicationContext())
        withTimeout(5_000) { engine.ready.first { it } }
        assertEquals(Status.Failed(FailureReason.StartFailed), engine.status.value)
    }

    @Test
    fun anIncompatibleCoreIsReadyAndFailedAndNeverCalled() = runBlocking {
        core.schema = CORE_SCHEMA + 1
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.ready.first { it } }
        assertEquals(Status.Failed(FailureReason.IncompatibleCore), engine.status.value)
        assertEquals(ErrorCode.Internal, (engine.setMode(Mode.Direct).exceptionOrNull() as EngineException).code)
        assertTrue(core.calls.isEmpty())
        assertEquals(0, core.snapshots.get())
    }

    @Test
    fun aMissingSnapshotSymbolFailsTheEngineAndEndsNativeCalls() = runBlocking {
        core.snapshotError = UnsatisfiedLinkError("snapshot")
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.ready.first { it } }
        assertEquals(Status.Failed(FailureReason.StartFailed), engine.status.value)
        assertEquals(ErrorCode.Internal, (engine.setMode(Mode.Direct).exceptionOrNull() as EngineException).code)
        assertTrue(core.calls.isEmpty())
        assertEquals(1, core.snapshots.get())
    }

    @Test
    fun aMissingCallSymbolFailsTheEngineAndEndsNativeCalls() = runBlocking {
        core.callError = UnsatisfiedLinkError("call")
        assertEquals(ErrorCode.Internal, (engine.setMode(Mode.Direct).exceptionOrNull() as EngineException).code)
        assertTrue(engine.ready.value)
        assertEquals(Status.Failed(FailureReason.StartFailed), engine.status.value)
        assertEquals(ErrorCode.Internal, (engine.select("g-Proxy", null).exceptionOrNull() as EngineException).code)
        assertEquals(1, core.calls.size)
        assertEquals(0, core.snapshots.get())
    }

    @Test
    fun aCommandTheCallerCancelsStillRefreshesTheCatalog() = runBlocking {
        val release = CountDownLatch(1)
        core.callGate = release
        val caller = launch(Dispatchers.IO) { engine.setMode(Mode.Direct) }
        assertTrue(core.callEntered.await(5, TimeUnit.SECONDS))
        caller.cancel()
        release.countDown()
        withTimeout(5_000) { engine.ready.first { it } }
        assertEquals(listOf("g-Proxy", "g-Auto", "g-Streaming", "g-AI", "g-HK"), engine.groups.value.map { it.id })
    }

    @Test
    fun tapsReachTheCoreInTapOrderEvenFromAScreenThatHasLeft() = runBlocking {
        val release = CountDownLatch(1)
        core.callGate = release
        val screen = CoroutineScope(Dispatchers.IO + Job())
        screen.launch(Dispatchers.Unconfined) { engine.select("g-Proxy", "n-hk1") }
        assertTrue(core.callEntered.await(5, TimeUnit.SECONDS))
        // The second tap waits behind the first, and its screen is left before its turn comes.
        screen.launch(Dispatchers.Unconfined) { engine.select("g-Proxy", "n-us1") }
        screen.cancel()
        // The screen is opened again and taps: that tap queues behind the other two instead of overtaking them.
        val reopened = CoroutineScope(Dispatchers.IO + Job())
        reopened.launch(Dispatchers.Unconfined) { engine.select("g-Proxy", "n-jp1") }
        delay(200)
        assertEquals("only the first call is in the core", 1, core.entered.size)

        release.countDown()
        withTimeout(5_000) { while (core.calls.size < 3) delay(10) }
        assertEquals(listOf("n-hk1", "n-us1", "n-jp1"), core.calls.map { Regex("n-[a-z0-9]+").find(it.second)!!.value })
        reopened.cancel()
    }

    @Test
    fun aTapAndATestShareOneQueueButOtherCommandsDoNotWaitInIt() = runBlocking {
        val release = CountDownLatch(1)
        core.callGate = release
        val tap = launch(Dispatchers.Unconfined) { engine.select("g-Proxy", "n-hk1") }
        assertTrue(core.callEntered.await(5, TimeUnit.SECONDS))
        val test = launch(Dispatchers.Unconfined) { engine.testLatency("g-Proxy") }
        val mode = launch(Dispatchers.Unconfined) { engine.setMode(Mode.Direct) }
        delay(200)
        assertEquals("the tap, and the command that is not queued", listOf("select", "setMode"), core.entered.toList())
        release.countDown()
        tap.join()
        test.join()
        mode.join()
        assertEquals(listOf("select", "testLatency"), core.calls.map { it.first }.filter { it != "setMode" })
    }

    @Test
    fun aRuleModeCatalogCarriesRuleItsMainGroupAndItsSource() = runBlocking {
        assertEquals(null, engine.catalog.value)
        val rule = ContractFixtures.read("snapshot-stopped-catalog.json").replace("\"mode\": \"Global\"", "\"mode\": \"Rule\"")
        core.snapshotReplies += { rule }
        assertTrue(engine.setMode(Mode.Rule).isSuccess)
        assertEquals("setMode" to """{"mode":"Rule"}""", core.calls.single())
        assertEquals(Mode.Rule, engine.settings.value.mode)
        val catalog = checkNotNull(engine.catalog.value)
        assertEquals(Mode.Rule to "g-Proxy", catalog.mode to catalog.mainGroupId)
        assertEquals(listOf("cn-direct", 5, 91044300313602L), catalog.ruleSource.let { listOf(it.template, it.rules, it.rulesRevision) })
        // The flows that repeat parts of it agree.
        assertEquals(catalog.groups to catalog.subscriptions, engine.groups.value to engine.subscriptions.value)
    }

    @Test
    fun aCatalogIsNeverSeenHalfPublished() = runBlocking {
        val both = ContractFixtures.read("snapshot-stopped-catalog.json")
            .replace("\"mode\": \"Global\"", "\"mode\": \"Rule\"").replace("\"blockQuic\": false", "\"blockQuic\": true")
        core.snapshotReplies += { both }
        // Collected undispatched: it sees each value as it is published.
        val seen = CopyOnWriteArrayList<Pair<Mode, Boolean>>()
        val watcher = launch(Dispatchers.Unconfined) {
            engine.catalog.collect { catalog -> catalog?.let { seen += it.mode to it.ruleSource.options.blockQuic } }
        }
        assertTrue(engine.setMode(Mode.Rule).isSuccess)
        watcher.cancel()
        assertEquals(listOf(Mode.Rule to true), seen.toList())
    }

    @Test
    fun aReadTheCallerLeavesBeforeTheCoreIsReachedNeverReachesIt() = runBlocking {
        val dispatched = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val threads = CopyOnWriteArrayList<Thread>()
        io = Executor { task ->
            // Listed before the test is told, so the test waits for every task the read dispatched.
            threads += thread {
                gate.await()
                task.run()
            }
            dispatched.countDown()
        }.asCoroutineDispatcher()
        val reader = scope.launch { engine.effectiveConfig() }
        assertTrue(dispatched.await(5, TimeUnit.SECONDS))
        reader.cancel()
        gate.countDown()
        reader.join()
        threads.forEach { it.join(5_000) }
        assertFalse("effectiveConfig" in core.entered)
    }

    @Test
    fun rulesAreReadThePageAsked() = runBlocking {
        core.reply = ContractFixtures.read("call-ok-rules.json")
        val page = engine.rules(offset = 50, limit = 25).getOrThrow()
        assertEquals("rules" to """{"offset":50,"limit":25}""", core.calls.single())
        assertEquals(listOf(91044300313602L, 5L), listOf(page.rulesRevision, page.total.toLong()))
        assertEquals(RuleTarget.Group("g-Streaming"), page.rows[1].target)
    }

    @Test
    fun ruleModeCommandsSendTheirArgsAndMapTheReplies() = runBlocking {
        assertTrue(engine.setRuleSource("cn-direct", RuleOptions(blockQuic = true)).isSuccess)
        core.reply = ContractFixtures.read("call-ok-effective-config.json")
        val config = engine.effectiveConfig().getOrThrow()
        core.reply = ContractFixtures.read("call-ok-usage.json")
        val usage = engine.usage().getOrThrow()
        val sent = listOf(
            "setRuleSource" to """{"kind":"template","template":"cn-direct","options":{"blockQuic":true}}""",
            "effectiveConfig" to "{}",
            "usage" to "{}",
        )
        assertEquals(sent, core.calls.toList())
        assertEquals(Mode.Rule to RuleTarget.Group("g-Proxy"), config.mode to config.dnsVia)
        assertEquals(listOf("r-6e59b550be7e592b", "r-8d066c6f0b6552cd"), usage.today.byRule.map { it.id })
    }

    @Test
    fun ruleModeErrorsKeepTheirCodes() = runBlocking {
        fun Result<*>.code() = (exceptionOrNull() as EngineException).code
        core.reply = """{"error":{"code":"invalid_argument"}}"""
        assertEquals(ErrorCode.InvalidArgument, engine.rules(offset = 0, limit = 501).code())
        core.reply = """{"error":{"code":"not_found"}}"""
        assertEquals(ErrorCode.NotFound, engine.setRuleSource("later", RuleOptions()).code())
        core.reply = """{"error":{"code":"parse_failed"}}"""
        assertEquals(ErrorCode.ParseFailed, engine.setRuleSource("cn-direct", RuleOptions()).code())
        // A reply without its fields cannot be read.
        core.reply = """{"ok":{"rulesRevision":1}}"""
        assertEquals(ErrorCode.Internal, engine.rules(offset = 0, limit = 50).code())
        assertEquals(ErrorCode.Internal, engine.effectiveConfig().code())
        assertEquals(ErrorCode.Internal, engine.usage().code())
    }

    @Test
    fun aSlowSnapshotNeitherHoldsBackNorReplacesANewerCatalog() = runBlocking {
        val older = ContractFixtures.read("snapshot-stopped-catalog.json")
        val newer = older.replace("\"revision\": 7", "\"revision\": 8").replace("\"mode\": \"Global\"", "\"mode\": \"Direct\"")
        val olderEntered = CountDownLatch(1)
        val olderRelease = CountDownLatch(1)
        core.snapshotReplies += {
            olderEntered.countDown()
            olderRelease.await()
            older
        }
        core.snapshotReplies += { newer }
        val first = launch(Dispatchers.IO) { engine.select("g-Proxy", null) }
        assertTrue(olderEntered.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) { engine.setMode(Mode.Direct) }
        assertEquals(Mode.Direct, engine.settings.value.mode)
        olderRelease.countDown()
        first.join()
        assertEquals(Mode.Direct, engine.settings.value.mode)
    }

    @Test
    fun aSaveShowsThePolicyThatRepairedTheFile() = runBlocking {
        perAppFile.writeBytes(ByteArray(0))
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        assertTrue("the settings read the damaged file", perAppReads.tryAcquire(5, TimeUnit.SECONDS))
        val saved = PerApp(AppPolicy.OnlyListed, setOf("a"))
        assertTrue(engine.setPerApp(saved).isSuccess)
        assertEquals(saved, engine.settings.value.perApp)
    }

    @Test
    fun aSaveTheCallerLeavesAfterTheWriteIsStillShownAndRebuilt() = runBlocking {
        engine.connect()
        val requestedStart = requested.single()
        // The controller does not run: a session that took a host is all a rebuild needs to be requested.
        assertTrue(engine.controller.start(NoHost, requestedStart))
        val saved = PerApp(AppPolicy.OnlyListed, setOf("a"))
        val caller = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { engine.setPerApp(saved) }
        afterWrite = { caller.cancel() }
        caller.start()
        caller.join()

        assertEquals(saved, PerAppSettings(perAppStore).perApp.first())
        withTimeout(5_000) { engine.settings.first { it.perApp == saved } }
        val rebuild = withTimeout(5_000) { engine.controller.session.first { it.generation > requestedStart } }
        assertEquals("the rebuild was requested", requestedStart + 1, rebuild.generation)
    }

    @Test
    fun aSaveWaitsForTheRebuildItAsksFor() = runBlocking {
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        engine.connect()
        assertTrue(engine.controller.start(StartHost(installed = setOf("a")), requested.single()))
        withTimeout(5_000) { engine.status.first { it is Status.Running } }
        val before = engine.controller.session.value.generation

        assertTrue(engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf("a"))).isSuccess)
        // The tunnel was Running before the save too, so only a newer session that is Running shows the save waited for it.
        val after = engine.controller.session.value
        assertTrue("the save rebuilt the session: generation ${after.generation} after $before", after.generation > before)
        assertTrue("the rebuilt tunnel is up when the save returns, not still ${after.status}", after.status is Status.Running)
    }

    @Test
    fun aSnapshotWarningShowsOnlyWhileTheTunnelRuns() = runBlocking {
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.ready.first { it } }
        // The refresh after the command reads the running fixture, which carries `local_network`.
        assertTrue(engine.setMode(Mode.Direct).isSuccess)
        assertEquals(null, engine.warning.value)
        engine.connect()
        assertTrue(engine.controller.start(StartHost(installed = setOf("a")), requested.single()))
        withTimeout(5_000) { engine.status.first { it is Status.Running } }
        // A snapshot of this run: the refresh after a command.
        assertTrue(engine.setMode(Mode.Direct).isSuccess)
        withTimeout(5_000) { engine.warning.first { it == ErrorCode.LocalNetwork } }
        engine.disconnect()
        withTimeout(5_000) { engine.warning.first { it == null } }
        Unit
    }

    @Test
    fun aRunShowsNoWarningAnEarlierRunReportedBeforeItsOwnSnapshot() = runBlocking {
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.ready.first { it } }
        engine.connect()
        assertTrue(engine.controller.start(StartHost(installed = setOf("a")), requested.single()))
        withTimeout(5_000) { engine.status.first { it is Status.Running } }
        assertTrue(engine.setMode(Mode.Direct).isSuccess)
        withTimeout(5_000) { engine.warning.first { it == ErrorCode.LocalNetwork } }
        engine.disconnect()
        withTimeout(5_000) { engine.status.first { it == Status.Stopped } }

        // The next run's core has no failed dial, so none of its snapshots carries a warning.
        core.onStart = { core.warns = false }
        val shown = CopyOnWriteArrayList<ErrorCode>()
        val watcher = launch(Dispatchers.Unconfined) { engine.warning.collect { it?.let(shown::add) } }
        engine.connect()
        assertTrue(engine.controller.start(StartHost(installed = setOf("a")), requested.last()))
        withTimeout(5_000) { engine.status.first { it is Status.Running } }
        assertTrue("a snapshot of this run", engine.setMode(Mode.Direct).isSuccess)
        watcher.cancel()
        assertEquals("the earlier run's warning", emptyList<ErrorCode>(), shown)
    }

    @Test
    fun aSaveWhoseRebuildFailsIsStoredAndReportsWhy() = runBlocking {
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        engine.connect()
        assertTrue(engine.controller.start(StartHost(installed = emptySet()), requested.single()))
        withTimeout(5_000) { engine.status.first { it is Status.Running } }

        val saved = PerApp(AppPolicy.OnlyListed, setOf("gone"))
        val failure = engine.setPerApp(saved).exceptionOrNull()
        assertEquals(FailureReason.NoAllowedApps, (failure as RebuildFailedException).reason)
        assertEquals("the policy was stored all the same", saved, engine.settings.value.perApp)
        assertEquals(Status.Failed(FailureReason.NoAllowedApps), engine.status.value)
    }

    @Test
    fun aSaveWithNoTunnelToRebuildSucceedsAtOnce() = runBlocking {
        assertTrue(engine.setPerApp(PerApp(AppPolicy.ExceptListed, setOf("a"))).isSuccess)
    }

    @Test
    fun thePolicyIsLoadedOnceItHasBeenRead() = runBlocking {
        val stored = PerApp(AppPolicy.OnlyListed, setOf("a"))
        PerAppSettings(perAppStore).update { stored }
        assertFalse("before the engine reads it", engine.perAppLoaded.value)
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.perAppLoaded.first { it } }
        assertEquals(stored, engine.settings.value.perApp)
    }

    @Test
    fun anUnreadablePolicyIsLoadedToo() = runBlocking {
        // A save has to be possible then: it is what repairs the file.
        perAppFile.writeBytes(ByteArray(0))
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.perAppLoaded.first { it } }
        assertEquals(PerApp(), engine.settings.value.perApp)
    }

    @Test
    fun aSaveLoadsThePolicyToo() = runBlocking {
        assertFalse(engine.perAppLoaded.value)
        assertTrue(engine.setPerApp(PerApp(AppPolicy.ExceptListed)).isSuccess)
        assertTrue(engine.perAppLoaded.value)
    }

    @Test
    fun theSettingsFollowThePolicyAgainAfterAFailedRead() = runBlocking {
        val stored = PerApp(AppPolicy.OnlyListed, setOf("a"))
        PerAppSettings(perAppStore).update { stored }
        failNextRead = true
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        withTimeout(5_000) { engine.settings.first { it.perApp == stored } }
        Unit
    }

    @Test
    fun anErrorReplyFailsWithItsCode() = runBlocking {
        core.reply = """{"error":{"code":"busy"}}"""
        assertEquals(ErrorCode.Busy, (engine.select("g-Proxy", null).exceptionOrNull() as EngineException).code)
    }

    @Test
    fun anUnreadableReplyIsInternal() = runBlocking {
        core.reply = """{"ok":{"id":"s1"}}"""
        assertEquals(ErrorCode.Internal, (engine.updateSubscription("s1").exceptionOrNull() as EngineException).code)
    }

    @Test
    fun aSubscriptionReplyMapsToTheEngineType() = runBlocking {
        core.reply = ContractFixtures.read("call-ok-subscription.json")
        assertEquals(1073741824L, engine.addSubscription("https://example.invalid/sub").getOrThrow().usedBytes)
        assertEquals("addSubscription" to """{"url":"https://example.invalid/sub","name":null}""", core.calls.single())
    }

    @Test
    fun aSubscriptionsAgentIsSetWithoutAFetch() = runBlocking {
        core.reply = ContractFixtures.read("call-ok-subscription.json")
        assertEquals("clash-verge/v2.2.3", engine.setSubscriptionUserAgent("s1", "clash-verge/v2.2.3").getOrThrow().userAgent)
        assertEquals("setSubscriptionUserAgent" to """{"id":"s1","userAgent":"clash-verge/v2.2.3"}""", core.calls.single())
        core.reply = """{"error":{"code":"invalid_argument"}}"""
        val refused = engine.setSubscriptionUserAgent("s1", "doona\r\nX: 1").exceptionOrNull() as EngineException
        assertEquals(ErrorCode.InvalidArgument, refused.code)
    }

    /** A host that builds a tun for any routing; [installed] are the packages the device has. */
    private class StartHost(private val installed: Set<String>) : TunHost {
        override val selfPackage = "dev.doona.android"

        override fun installed(packages: Set<String>) = packages intersect installed

        override fun bootstrapDns() = emptyList<String>()

        override fun establish(routing: AppRouting, mtu: Int): Tun = object : Tun {
            override val fd = 3

            override fun close() = Unit
        }
    }

    private object NoHost : TunHost {
        override val selfPackage = "dev.doona.android"

        override fun installed(packages: Set<String>) = error("unused")

        override fun bootstrapDns() = error("unused")

        override fun establish(routing: AppRouting, mtu: Int) = error("unused")
    }
}
