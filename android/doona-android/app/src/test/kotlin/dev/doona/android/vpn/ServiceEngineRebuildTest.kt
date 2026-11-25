package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.Status
import dev.doona.android.vpn.ServiceEngineTest.StartHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** A per-app save over a running tunnel whose rebuild then fails: what the save answers, as 依 App reads it. */
@RunWith(AndroidJUnit4::class)
class ServiceEngineRebuildTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val core = FakeCore()
    private val requested = CopyOnWriteArrayList<Long>()

    private val engine by lazy {
        val opener = CoreOpener(core, { "/data" }, Dispatchers.IO)
        val perAppSettings = PerAppSettings(PerAppSettings.store(scope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { perAppSettings.perApp.first() }, scope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
        ServiceEngine(core, opener, controller, perAppSettings, { requested.add(it) }, Dispatchers.IO, scope, expireDebugCapture = { null }, perAppRetryMs = 50)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aSaveWhoseRebuildFailsIsStoredAndReportsWhy() = runBlocking {
        val saved = PerApp(AppPolicy.OnlyListed, setOf("gone"))
        val failure = savedOverARunningTunnel(saved)
        assertEquals(FailureReason.NoAllowedApps to null, failure.reason to failure.code)
        assertEquals("the policy was stored all the same", saved, engine.settings.value.perApp)
        assertEquals(Status.Failed(FailureReason.NoAllowedApps), engine.status.value)
    }

    @Test
    fun aRebuildTheCoreRefusesForWantOfNodesCarriesTheCoresCode() = runBlocking {
        // The first start runs; the rebuild's start is the core's second, and it finds no node.
        core.onStart = { if (core.configs.size > 1) throw IllegalStateException("no_nodes") }
        val failure = savedOverARunningTunnel(PerApp(AppPolicy.ExceptListed, setOf("a")))
        assertEquals(FailureReason.StartFailed to ErrorCode.NoNodes, failure.reason to failure.code)
        assertEquals(Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes), engine.status.value)
    }

    /** What the save of [perApp] answers on a running tunnel that the rebuild with it takes down. */
    private suspend fun savedOverARunningTunnel(perApp: PerApp): RebuildFailedException {
        engine.launch(Dispatchers.IO.limitedParallelism(1))
        engine.connect()
        assertTrue(engine.controller.start(StartHost(installed = emptySet()), requested.single()))
        withTimeout(5_000) { engine.status.first { it is Status.Running } }
        return engine.setPerApp(perApp).exceptionOrNull() as RebuildFailedException
    }
}
