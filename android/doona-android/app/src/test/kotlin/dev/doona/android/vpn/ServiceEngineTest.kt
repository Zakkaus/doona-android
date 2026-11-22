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
import dev.doona.android.engine.Mode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ServiceEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = FakeCore()
    private val requested = CopyOnWriteArrayList<Long>()

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
        ServiceEngine(core, opener, controller, perAppSettings, { dispatch(it) }, Dispatchers.IO, scope, perAppRetryMs = 50)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aCommandSendsTypedArgsAndRefreshesTheCatalog() = runBlocking {
        assertFalse(engine.ready.value)
        assertTrue(engine.setMode(Mode.Direct).isSuccess)
        assertEquals("setMode" to """{"mode":"Direct"}""", core.calls.single())
        assertTrue(engine.ready.value)
        assertEquals(listOf("g-Proxy", "g-Auto", "g-HK"), engine.groups.value.map { it.id })
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
        assertEquals(listOf("g-Proxy", "g-Auto", "g-HK"), engine.groups.value.map { it.id })
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

        assertTrue(engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf("a"))).isSuccess)
        assertTrue("the rebuilt tunnel is up when the save returns", engine.status.value is Status.Running)
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
    }

    /** A host that builds a tun for any routing; [installed] are the packages the device has. */
    private class StartHost(private val installed: Set<String>) : TunHost {
        override val selfPackage = "dev.doona.android"

        override fun installed(packages: Set<String>) = packages intersect installed

        override fun bootstrapDns() = emptyList<String>()

        override fun establish(routing: AppRouting): Tun = object : Tun {
            override val fd = 3

            override fun close() = Unit
        }
    }

    private object NoHost : TunHost {
        override val selfPackage = "dev.doona.android"

        override fun installed(packages: Set<String>) = error("unused")

        override fun bootstrapDns() = error("unused")

        override fun establish(routing: AppRouting) = error("unused")
    }

    private class FakeCore : CoreBridge {
        @Volatile var reply = """{"ok":{}}"""

        @Volatile var schema = CORE_SCHEMA

        @Volatile var callGate: CountDownLatch? = null

        @Volatile var callError: Throwable? = null

        @Volatile var snapshotError: Throwable? = null
        val callEntered = CountDownLatch(1)

        /** Every call that reached the core, logged before [callGate] holds it. */
        val entered = CopyOnWriteArrayList<String>()
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        val snapshots = AtomicInteger()

        /** Served first, in order, before the fixtures. */
        val snapshotReplies = ConcurrentLinkedQueue<() -> String>()

        override fun init(dataDir: String) = """{"schema":$schema}"""

        override fun start(tunFd: Int, configJson: String) = Unit

        override fun stop() = Unit

        // The catalog fixture is revision 7; the running fixture is the same revision without a catalog.
        override fun snapshot(sinceRevision: Long): String {
            snapshots.incrementAndGet()
            snapshotError?.let { throw it }
            snapshotReplies.poll()?.let { return it() }
            return ContractFixtures.read(if (sinceRevision < 7) "snapshot-stopped-catalog.json" else "snapshot-running.json")
        }

        override fun call(command: String, argsJson: String): String {
            entered += command
            callEntered.countDown()
            callGate?.await()
            calls += command to argsJson
            callError?.let { throw it }
            return reply
        }
    }
}
