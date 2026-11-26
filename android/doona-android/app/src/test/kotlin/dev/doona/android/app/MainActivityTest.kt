package dev.doona.android.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.MemoryPreferences
import dev.doona.android.engine.Status
import dev.doona.android.feature.permissions.Permission
import dev.doona.android.feature.permissions.PermissionStep
import dev.doona.android.feature.permissions.PermissionViewModel
import dev.doona.android.feature.settings.RvLogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    private val refusals get() = app.container.connectRefusals

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
            // The level goes to honk off the main thread and lands on it, so the main looper runs while the test waits.
            val set = CoroutineScope(Dispatchers.Unconfined).async { levels.set(RvLogLevel.Debug) }
            settle { set.isCompleted }
            runBlocking { set.await().getOrThrow() }
            activity.pause()
            ShadowSystemClock.simulateDeepSleep(Duration.ofHours(1))
            assertEquals(RvLogLevel.Debug, levels.level.value)
            assertEquals(0L, levels.remainingMs())

            activity.resume()
            settle { levels.level.value == RvLogLevel.Info }
        }
    }

    /** Runs the main looper until [done], for at most 5 s of real time: work that hops to `Dispatchers.IO` and back. */
    private fun settle(done: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!done() && System.nanoTime() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        assertTrue(done())
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
                assertTrue("and on 活動", refusals.vpn.value)
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
    fun `the answer to the system's notification dialog reaches the activity a dead process brings back`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
        val first = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Permission.Notify, first.get().asks().showing.value)
        first.get().onPermissionStep(first.get().asks().confirm())
        val requestCode = shadowOf(first.get()).lastRequestedPermission.requestCode

        // The process dies while the system's dialog is up: only the saved state and the registry's mapping survive.
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java, connect).create(state).start().restoreInstanceState(state)
            .postCreate(state).resume().visible()
        val asks = second.get().asks()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("not explained again while the dialog is up", asks.showing.value)
        assertFalse(runBlocking { settings.notifyAsked.first() })

        val denied = intArrayOf(PackageManager.PERMISSION_DENIED)
        second.get().onRequestPermissionsResult(requestCode, arrayOf(Manifest.permission.POST_NOTIFICATIONS), denied)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("the answer reached notifyAnswered", Permission.Notify, asks.refused.value)
        assertTrue("and was stored", runBlocking { settings.notifyAsked.first() })
        assertFalse(asks.unrecorded.value)
        second.close()
    }

    @Test
    fun `a notification dialog that cannot be launched leaves the app running and is explained again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val activity = controller.get()
        val asks = activity.asks()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Permission.Notify, asks.showing.value)
        val step = asks.confirm()
        shadowOf(app).checkActivities(true) // nothing handles the request, so launch() throws ActivityNotFoundException
        activity.onPermissionStep(step)
        runBlocking { asks.onStatus(running = true, notify = { true }) {} }
        assertEquals("the flag was not set, so the next status explains again", Permission.Notify, asks.showing.value)
    }

    @Test
    fun `a consent dialog that cannot be launched leaves the app running and the next connect explains again`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val activity = controller.get()
        val asks = activity.asks()
        assertEquals(Permission.Vpn, asks.showing.value)
        val step = asks.confirm()
        shadowOf(app).checkActivities(true) // nothing handles the consent request, so launch() throws
        activity.onPermissionStep(step)
        assertEquals("told as a refused consent", Permission.Vpn, asks.refused.value)
        assertEquals("the 連線 ended, so the next one explains again", PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
    }

    @Test
    fun `a consent answered while the connection is up lets the waiting notification explanation show`() {
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val activity = controller.get()
        val asks = activity.asks()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        assertNull(asks.showing.value)
        // The permission is taken away while the connection runs, and the tile finds the consent gone.
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        controller.newIntent(connect)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Permission.Vpn, asks.showing.value)
        val shadow = shadowOf(activity)
        activity.onPermissionStep(asks.confirm())
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_OK, null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no new status follows, the answer itself runs onStatus", Permission.Notify, asks.showing.value)
        controller.close()
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

    @Test
    fun `a refused VPN consent is recorded, and a later consent clears it`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val activity = controller.get()
        val shadow = shadowOf(activity)
        activity.onPermissionStep(activity.asks().confirm())
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
        assertTrue(refusals.vpn.value)

        controller.newIntent(connect)
        activity.onPermissionStep(activity.asks().confirm())
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_OK, null)
        assertFalse(refusals.vpn.value)
        controller.close()
    }

    @Test
    fun `a notification refused after the connection dropped is recorded at once`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationsEnabled(false)
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        activity.onPermissionStep(activity.asks().confirm())
        val request = shadowOf(activity).lastRequestedPermission
        app.container.engine.disconnect()
        shadowOf(Looper.getMainLooper()).idle()

        activity.activityResultRegistry.dispatchResult(request.requestCode, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(refusals.notifications.value)
        controller.close()
    }

    @Test
    fun `a notification asked before is recorded without asking again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationsEnabled(false)
        runBlocking { settings.recordNotifyAsked() }
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        assertNull(controller.get().asks().showing.value)
        assertNull(shadowOf(controller.get()).lastRequestedPermission)
        assertTrue(refusals.notifications.value)
        controller.close()
    }

    @Test
    fun `resuming clears each refusal only once the system allows it`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        notificationsEnabled(false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        refusals.vpn.value = true
        refusals.notifications.value = true
        controller.pause().resume()
        assertTrue(refusals.vpn.value)
        assertTrue(refusals.notifications.value)

        notificationsEnabled(true)
        controller.pause().resume()
        assertTrue(refusals.vpn.value)
        assertFalse(refusals.notifications.value)

        ShadowVpnService.setPrepareResult(null)
        controller.pause().resume()
        assertFalse(refusals.vpn.value)
        controller.close()
    }

    private fun notificationsEnabled(enabled: Boolean) =
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(enabled)

    /** The explanation on screen once the main looper has run what the last step posted. */
    private fun ActivityScenario<MainActivity>.showing(): Permission? {
        shadowOf(Looper.getMainLooper()).idle()
        var showing: Permission? = null
        onActivity { showing = it.asks().showing.value }
        return showing
    }

    private fun MainActivity.asks() = ViewModelProvider(this, PermissionViewModel.Factory)[PermissionViewModel::class.java].asks
}
