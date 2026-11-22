package dev.doona.android.vpn

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
    private val engine by lazy {
        val opener = CoreOpener(core, "/data", Dispatchers.IO)
        val settings = PerAppSettings(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { PerApp() }, scope, killProcess = {})
        ServiceEngine(core, opener, controller, settings, startService = { dispatch(it) }, io = Dispatchers.IO, scope = scope)
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
        val callEntered = CountDownLatch(1)
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
            snapshotReplies.poll()?.let { return it() }
            return ContractFixtures.read(if (sinceRevision < 7) "snapshot-stopped-catalog.json" else "snapshot-running.json")
        }

        override fun call(command: String, argsJson: String): String {
            callEntered.countDown()
            callGate?.await()
            calls += command to argsJson
            return reply
        }
    }
}
