package dev.doona.android.app

import android.Manifest
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import dev.doona.android.feature.subscriptions.ImportRequest
import dev.doona.android.vpn.FakeHonk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * After a refusal the app leads to its settings page (ACC3 A1). An import of a link on the local network asks for access
 * before it reaches honk and waits on the refusal; when the user comes back with access allowed, the activity's resume
 * lets it add the subscription, without a tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class LocalNetworkRetryTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()
    private val container get() = app.container
    private val access get() = container.localNetwork

    /** The adds honk was asked for: POST /providers. */
    private val adds get() = (container.nativeApi as FakeHonk).let { honk -> synchronized(honk) { honk.calls.count { it.method == "POST" && it.path == "/api/v1/providers" } } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the main looper until the import ends: honk's add runs on another thread. */
    private fun settled() {
        val end = System.currentTimeMillis() + READ_MS
        while (container.imports.state.value.busy && System.currentTimeMillis() < end) {
            Thread.sleep(5)
            idle()
        }
    }

    /** An import of a LAN subscription asks; the user refuses in the system's dialog, and the refusal is shown. */
    private fun refusedImport() {
        assertNotNull(container.imports.start(ImportRequest("http://192.168.8.161/sub", null, null)))
        idle()
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        access.answered(false)
        idle()
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        assertTrue("the import waits on the refusal", container.imports.state.value.busy)
        assertEquals("honk is not asked while access is refused", 0, adds)
    }

    /** The settings page covers the app while [inSettings] runs, then the app is resumed. */
    private fun ActivityScenario<MainActivity>.leaveAndReturn(inSettings: () -> Unit = {}) {
        moveToState(Lifecycle.State.STARTED)
        inSettings()
        moveToState(Lifecycle.State.RESUMED)
        idle()
    }

    private fun allow() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_LOCAL_NETWORK)

    @Test
    fun anImportWaitingWhenAccessIsAllowedInTheSettingsRunsOnResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            // The refusal's button: what it does before it opens the settings page.
            access.toSettings()
            scenario.leaveAndReturn(::allow)
            settled()
            assertEquals(1, adds)
            val state = container.imports.state.value
            assertFalse(state.busy)
            assertNull(state.error)
            assertNotNull("the import added its subscription", state.added)
            scenario.leaveAndReturn()
            assertEquals("one add only", 1, adds)
        }
    }

    @Test
    fun anImportWaitingWhenAccessIsAllowedFromTheAppInfoPageRunsOnResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            // The user leaves through the launcher with the refusal still up, not by its button.
            scenario.leaveAndReturn()
            assertEquals(0, adds)
            assertTrue("still refused: the import keeps waiting", container.imports.state.value.busy)
            assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
            // Next time they allow access on the app's info page before they come back.
            scenario.leaveAndReturn(::allow)
            settled()
            assertEquals(1, adds)
            assertNull("allowed now: the refusal is gone", access.prompt.value)
            val state = container.imports.state.value
            assertFalse(state.busy)
            assertNull(state.error)
            assertNotNull("the import added its subscription", state.added)
            scenario.leaveAndReturn()
            assertEquals("one add only", 1, adds)
        }
    }

    @Test
    fun noAddWhenAccessIsStillRefused() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            access.toSettings()
            scenario.leaveAndReturn()
            assertEquals(0, adds)
            val state = container.imports.state.value
            assertFalse(state.busy)
            assertEquals(ErrorCode.LocalNetwork, state.error)
        }
    }

    @Test
    fun noAddOnceTheRefusalIsClosed() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            // The user closes the refusal; the import ends with its failure and nothing waits any more.
            access.dismissed()
            idle()
            assertEquals(ErrorCode.LocalNetwork, container.imports.state.value.error)
            scenario.leaveAndReturn(::allow)
            assertEquals(0, adds)
            assertEquals(ErrorCode.LocalNetwork, container.imports.state.value.error)
        }
    }
}

private const val READ_MS = 5_000L
