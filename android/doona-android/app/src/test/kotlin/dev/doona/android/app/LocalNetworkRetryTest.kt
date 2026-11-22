package dev.doona.android.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Fails each add with [ErrorCode.LocalNetwork] until the permission is granted, as the core does; counts the adds. */
class LanEngine(private val granted: () -> Boolean, private val fake: FakeEngine = FakeEngine()) : Engine by fake {
    var adds = 0

    override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
        adds++
        return if (granted()) fake.addSubscription(url, name) else Result.failure(EngineException(ErrorCode.LocalNetwork))
    }
}

/** The app on a [LanEngine]: a subscription on the local network that only the permission lets through. */
class LanEngineApplication : DoonaApplication() {
    override fun createEngine(): Engine =
        LanEngine({ checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED })

    override fun createInstalledApps(): InstalledApps = FakeInstalledApps()
}

/**
 * After a refusal the app leads to its settings page (ACC3 A1). An import that failed on the local network waits on the
 * refusal; when the user comes back with access allowed, the activity's resume runs it once more, without a tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = LanEngineApplication::class)
class LocalNetworkRetryTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()
    private val container get() = app.container
    private val engine get() = container.engine as LanEngine
    private val access get() = container.localNetwork

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** An import of a LAN subscription fails; the user refuses in the system's dialog, and the refusal is shown. */
    private fun refusedImport() {
        assertTrue(container.imports.start("http://192.168.8.161/sub", null))
        idle()
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        access.answered(false)
        idle()
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        assertTrue("the import waits on the refusal", container.imports.state.value.busy)
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
    fun anImportWaitingWhenAccessIsAllowedInTheSettingsRunsOnceMoreOnResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val subscriptions = engine.subscriptions.value.size
            refusedImport()
            // The refusal's button: what it does before it opens the settings page.
            access.toSettings()
            scenario.leaveAndReturn(::allow)
            assertEquals(2, engine.adds)
            val state = container.imports.state.value
            assertFalse(state.busy)
            assertNull(state.error)
            assertEquals(1, state.imported)
            assertEquals(subscriptions + 1, engine.subscriptions.value.size)
            scenario.leaveAndReturn()
            assertEquals("one retry only", 2, engine.adds)
        }
    }

    @Test
    fun anImportWaitingWhenAccessIsAllowedFromTheAppInfoPageRunsOnceMoreOnResume() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            // The user leaves through the launcher with the refusal still up, not by its button.
            scenario.leaveAndReturn()
            assertEquals(1, engine.adds)
            assertTrue("still refused: the import keeps waiting", container.imports.state.value.busy)
            assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
            // Next time they allow access on the app's info page before they come back.
            scenario.leaveAndReturn(::allow)
            assertEquals(2, engine.adds)
            assertNull("allowed now: the refusal is gone", access.prompt.value)
            val state = container.imports.state.value
            assertFalse(state.busy)
            assertNull(state.error)
            assertEquals(1, state.imported)
            scenario.leaveAndReturn()
            assertEquals("one retry only", 2, engine.adds)
        }
    }

    @Test
    fun noRetryWhenAccessIsStillRefused() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            access.toSettings()
            scenario.leaveAndReturn()
            assertEquals(1, engine.adds)
            val state = container.imports.state.value
            assertFalse(state.busy)
            assertEquals(ErrorCode.LocalNetwork, state.error)
        }
    }

    @Test
    fun noRetryWithoutAPendingFetch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            refusedImport()
            // The user closes the refusal; the import ends with its failure and nothing waits any more.
            access.dismissed()
            idle()
            assertEquals(ErrorCode.LocalNetwork, container.imports.state.value.error)
            scenario.leaveAndReturn(::allow)
            assertEquals(1, engine.adds)
            assertEquals(ErrorCode.LocalNetwork, container.imports.state.value.error)
        }
    }
}
