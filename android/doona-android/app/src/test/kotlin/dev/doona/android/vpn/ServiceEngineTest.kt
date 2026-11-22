package dev.doona.android.vpn

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class ServiceEngineTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = FakeCore()
    private val requested = CopyOnWriteArrayList<Long>()
    private val engine by lazy {
        val opener = CoreOpener(core, "/data", Dispatchers.IO)
        val settings = PerAppSettings(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { PerApp() }, scope, killProcess = {})
        ServiceEngine(core, opener, controller, settings, startService = { requested += it }, io = Dispatchers.IO)
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
        val calls = CopyOnWriteArrayList<Pair<String, String>>()

        override fun init(dataDir: String) = """{"schema":$CORE_SCHEMA}"""

        override fun start(tunFd: Int, configJson: String) = Unit

        override fun stop() = Unit

        // The catalog fixture is revision 7; the running fixture is the same revision without a catalog.
        override fun snapshot(sinceRevision: Long): String =
            ContractFixtures.read(if (sinceRevision < 7) "snapshot-stopped-catalog.json" else "snapshot-running.json")

        override fun call(command: String, argsJson: String): String {
            calls += command to argsJson
            return reply
        }
    }
}
