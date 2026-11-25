package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.report.AppEvents
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceEnginePreferencesTest {
    @get:Rule val folder = TemporaryFolder()
    private val core = FakeCore()

    private fun TestScope.engine(): ServiceEngine {
        val io = StandardTestDispatcher(testScheduler)
        val opener = CoreOpener(core, { folder.root.path }, io)
        val settings = PerAppSettings(PerAppSettings.store(backgroundScope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { error("Tunnel not used") }, backgroundScope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
        return ServiceEngine(core, opener, controller, settings, { error("Tunnel not used") }, io, backgroundScope, expireDebugCapture = { null })
    }

    @Test
    fun renamePublishesTheUpdatedCatalogWithoutARefreshDownload() = runTest {
        val engine = engine()
        core.reply = ContractFixtures.read("call-ok-subscription.json")
        assertEquals("harbor", engine.renameSubscription("s1", " harbor ").getOrThrow().name)
        assertEquals("harbor", engine.subscriptions.value.first { it.id == "s1" }.name)
        assertEquals("renameSubscription" to ContractFixtures.read("call-rename-subscription-args.json").trim(), core.calls.single())
        core.reply = """{"error":{"code":"invalid_argument"}}"""
        assertEquals(ErrorCode.InvalidArgument, (engine.renameSubscription("s1", " ").exceptionOrNull() as EngineException).code)
    }

    @Test
    fun shareReturnsThePrivateValueOnlyToTheCaller() = runTest {
        val engine = engine()
        val link = "trojan://fixture-marker@192.0.2.1:443#A%20B"
        core.reply = ContractFixtures.read("call-ok-node-share-link.json")
        val result = engine.nodeShareLink("n-0c41be77")
        assertEquals(link, result.getOrThrow().uri)
        assertFalse("fixture-marker" in result.toString())
        assertFalse("fixture-marker" in result.getOrThrow().toString())
        assertEquals("nodeShareLink" to ContractFixtures.read("call-node-share-link-args.json").trim(), core.calls.single())
        core.reply = """{"error":{"code":"not_found"}}"""
        assertEquals(ErrorCode.NotFound, (engine.nodeShareLink("n-two").exceptionOrNull() as EngineException).code)
        assertFalse(AppEvents.snapshot().any { "fixture-marker" in it.text })
    }

    @Test
    fun dnsEditsSendTheOrderedEndpointsAndSurfaceRefusal() = runTest {
        val engine = engine()
        engine.setDnsUpstreams(listOf("9.9.9.9", "[2001:db8::1]:5353")).getOrThrow()
        assertEquals("setDnsUpstreams" to ContractFixtures.read("call-set-dns-upstreams-args.json").trim(), core.calls.single())
        core.reply = """{"error":{"code":"invalid_argument"}}"""
        assertEquals(ErrorCode.InvalidArgument, (engine.setDnsUpstreams(listOf("host")).exceptionOrNull() as EngineException).code)
    }
}
