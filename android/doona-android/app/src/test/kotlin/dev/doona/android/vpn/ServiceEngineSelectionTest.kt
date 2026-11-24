package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.Network
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceEngineSelectionTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun selectEncodesTcpAndUdpAndOmitsNetworkForBoth() = runTest {
        val core = FakeCore()
        val io = StandardTestDispatcher(testScheduler)
        val opener = CoreOpener(core, { folder.root.path }, io)
        val settings = PerAppSettings(PerAppSettings.store(backgroundScope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { error("Tunnel not used") }, backgroundScope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
        val engine = ServiceEngine(core, opener, controller, settings, { error("Tunnel not used") }, io, backgroundScope, expireDebugCapture = { null })

        engine.select("g-Proxy", "n-hk1", Network.Tcp).getOrThrow()
        engine.select("g-Proxy", "n-hk1", Network.Udp).getOrThrow()
        engine.select("g-Proxy", null).getOrThrow()

        assertEquals(
            listOf(
                "select" to """{"group":"g-Proxy","memberId":"n-hk1","network":"tcp"}""",
                "select" to """{"group":"g-Proxy","memberId":"n-hk1","network":"udp"}""",
                "select" to """{"group":"g-Proxy","memberId":null}""",
            ),
            core.calls,
        )
    }
}
