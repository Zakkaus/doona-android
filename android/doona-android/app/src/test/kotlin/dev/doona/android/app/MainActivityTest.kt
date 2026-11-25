package dev.doona.android.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.MemoryPreferences
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Status
import dev.doona.android.feature.permissions.Permission
import dev.doona.android.feature.permissions.PermissionViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowVpnService
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class MainActivityTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()
    private val connect = MainActivity.connectIntent(app)

    // The settings in memory: the activity reads and writes them on the main thread, so a looper idle settles each step.
    private val prefs = MemoryPreferences()
    private val settings = AppSettings(prefs)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AppSettings.use(app, settings)
    }

    @Test
    fun `resuming after deep sleep ends an expired capture`() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { activity ->
            val levels = app.container.logLevels
            runBlocking { levels.set(LogLevel.Debug).getOrThrow() }
            activity.pause()
            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(1))
            assertEquals(LogLevel.Debug, levels.level.value)
            assertEquals(0L, levels.remainingMs())

            activity.resume()
            assertEquals(LogLevel.Info, levels.level.value)
        }
    }

    @Test
    fun `the connect entry connects when consent is already given`() {
        ShadowVpnService.setPrepareResult(null)
        ActivityScenario.launch<MainActivity>(connect).use {
            assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        }
    }

    @Test
    fun `the connect entry explains the VPN before the system's consent`() {
        val consent = Intent("dev.doona.test.CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            scenario.onActivity { activity ->
                val shadow = shadowOf(activity)
                assertEquals(Permission.Vpn, activity.asks().showing.value)
                assertNull("no system dialog before the explanation", shadow.peekNextStartedActivityForResult())
                activity.onPermissionStep(activity.asks().confirm())
                assertEquals(consent.action, shadow.nextStartedActivityForResult.intent.action)
            }
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }

    @Test
    fun `a cancelled explanation asks nothing, connects nothing and is told`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            scenario.onActivity { activity ->
                activity.asks().dismiss()
                assertNull(shadowOf(activity).peekNextStartedActivityForResult())
                assertEquals(Permission.Vpn, activity.asks().refused.value)
            }
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }

    @Test
    fun `a refused consent connects nothing and is told`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect)
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        controller.setup()
        val activity = controller.get()
        val shadow = shadowOf(activity)
        activity.onPermissionStep(activity.asks().confirm())
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
        assertEquals(Status.Stopped, app.container.engine.status.value)
        assertEquals(Permission.Vpn, activity.asks().refused.value)
        controller.close()
    }

    @Test
    fun `the connect action aimed at the activity itself does not connect`() {
        ShadowVpnService.setPrepareResult(null)
        val direct = Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT)
        ActivityScenario.launch<MainActivity>(direct).use { scenario ->
            scenario.onActivity { assertNull("nothing is explained either", it.asks().showing.value) }
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }

    @Test
    fun `no other intent connects or disconnects`() {
        ShadowVpnService.setPrepareResult(null)
        val actions = listOf(Intent.ACTION_MAIN, Intent.ACTION_VIEW, "dev.doona.android.action.DISCONNECT", "dev.doona.android.prototype.rv.CONNECT", null)
        for (action in actions) {
            val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java).setAction(action)).setup()
            // And the connect action redelivered to the exported activity rather than the app's own entry.
            controller.newIntent(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT))
            assertEquals("$action", Status.Stopped, app.container.engine.status.value)
            assertNull(controller.get().asks().showing.value)
            controller.close()
        }
        app.container.engine.connect()
        val running = app.container.engine.status.value
        for (action in actions) {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java).setAction(action)).close()
            assertEquals("$action does not disconnect", running, app.container.engine.status.value)
        }
    }

    @Test
    fun `a repeated connect request joins the pending one`() {
        val consent = Intent("dev.doona.test.CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val activity = controller.get()
        val shadow = shadowOf(activity)
        activity.onPermissionStep(activity.asks().confirm())
        val pending = shadow.nextStartedActivityForResult
        controller.newIntent(connect)
        assertNull(shadow.peekNextStartedActivityForResult())
        assertNull("the explanation does not show over the system's dialog", activity.asks().showing.value)

        shadow.receiveResult(pending.intent, Activity.RESULT_OK, null)
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        controller.newIntent(connect)
        assertEquals("a new request explains again", Permission.Vpn, activity.asks().showing.value)
        controller.close()
    }

    @Test
    fun `a recreated activity keeps the pending request and its result`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        var controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        controller.get().onPermissionStep(controller.get().asks().confirm())
        val requestCode = shadowOf(controller.get()).nextStartedActivityForResult.requestCode

        controller = controller.recreate()
        controller.newIntent(connect)
        assertNull(shadowOf(controller.get()).peekNextStartedActivityForResult())

        controller.get().activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        controller.close()
    }

    @Test
    fun `the notification permission is explained once a connection is up`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
            assertEquals(Permission.Notify, scenario.showing())
            assertFalse("stored once answered", runBlocking { settings.notifyAsked.first() })
            scenario.onActivity { it.asks().dismiss() }
            assertTrue(runBlocking { settings.notifyAsked.first() })
        }
    }

    @Test
    fun `a connect from the tile waits behind the notification's explanation`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val asks = controller.get().asks()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Permission.Notify, asks.showing.value)
        // The connection drops, and the tile, finding the consent gone, sends the app's connect entry.
        app.container.engine.disconnect()
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        controller.newIntent(connect)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Permission.Notify, asks.showing.value)
        asks.dismiss()
        assertEquals(Permission.Vpn, asks.showing.value)
        controller.close()
    }

    @Test
    fun `settings that cannot be read leave the app running and the ask comes on the next connection`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
        prefs.failing = true
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
            assertNull(scenario.showing())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            prefs.failing = false
            app.container.engine.disconnect()
            app.container.engine.connect()
            assertEquals(Permission.Notify, scenario.showing())
        }
    }

    @Test
    fun `an answer that cannot be stored leaves the app running and is stored next time`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            assertEquals(Permission.Notify, scenario.showing())
            prefs.failing = true
            scenario.onActivity { it.asks().dismiss() }
            assertNull(scenario.showing())
            assertEquals(Lifecycle.State.RESUMED, scenario.state)

            prefs.failing = false
            app.container.engine.disconnect()
            assertNull("not explained again", scenario.showing())
            assertTrue(runBlocking { settings.notifyAsked.first() })
        }
    }

    /** The explanation on screen once the main looper has run what the last step posted. */
    private fun ActivityScenario<MainActivity>.showing(): Permission? {
        shadowOf(Looper.getMainLooper()).idle()
        var showing: Permission? = null
        onActivity { showing = it.asks().showing.value }
        return showing
    }

    private fun MainActivity.asks() = ViewModelProvider(this, PermissionViewModel.Factory)[PermissionViewModel::class.java].asks
}
