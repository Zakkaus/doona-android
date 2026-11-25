package dev.doona.android.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Before
    fun setUp() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

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
    fun `the connect entry asks for VPN consent first`() {
        val consent = Intent("dev.doona.test.CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(consent.action, shadowOf(activity).nextStartedActivityForResult.intent.action)
            }
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }

    @Test
    fun `the connect action aimed at the activity itself does not connect`() {
        ShadowVpnService.setPrepareResult(null)
        val direct = Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT)
        ActivityScenario.launch<MainActivity>(direct).use {
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }

    @Test
    fun `a repeated connect request joins the pending one`() {
        val consent = Intent("dev.doona.test.CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val shadow = shadowOf(controller.get())
        val pending = shadow.nextStartedActivityForResult
        controller.newIntent(connect)
        assertNull(shadow.peekNextStartedActivityForResult())

        shadow.receiveResult(pending.intent, Activity.RESULT_OK, null)
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        controller.newIntent(connect)
        assertNotNull(shadow.peekNextStartedActivityForResult())
        controller.close()
    }

    @Test
    fun `a recreated activity keeps the pending request and its result`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        var controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val requestCode = shadowOf(controller.get()).nextStartedActivityForResult.requestCode

        controller = controller.recreate()
        controller.newIntent(connect)
        assertNull(shadowOf(controller.get()).peekNextStartedActivityForResult())

        controller.get().activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, null)
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        controller.close()
    }

    @Test
    fun `a refused VPN consent is recorded, and a later consent clears it`() {
        ShadowVpnService.setPrepareResult(Intent("dev.doona.test.CONSENT"))
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val shadow = shadowOf(controller.get())
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_CANCELED, null)
        assertTrue(refusals.vpn.value)

        controller.newIntent(connect)
        shadow.receiveResult(shadow.nextStartedActivityForResult.intent, Activity.RESULT_OK, null)
        assertFalse(refusals.vpn.value)
        controller.close()
    }

    @Test
    fun `a connect that needs no consent clears an earlier refusal`() {
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        refusals.vpn.value = true
        controller.newIntent(connect)
        assertFalse(refusals.vpn.value)
        controller.close()
    }

    @Test
    fun `a denied notification permission is recorded and the connect goes on`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationsEnabled(false)
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        val request = shadowOf(controller.get()).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        assertFalse(refusals.notifications.value)

        controller.get().activityResultRegistry.dispatchResult(request.requestCode, false)
        assertTrue(refusals.notifications.value)
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        controller.close()
    }

    @Test
    fun `a permission declined before is recorded without asking again`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.packageManager).setShouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS, true)
        notificationsEnabled(false)
        ShadowVpnService.setPrepareResult(null)
        val controller = Robolectric.buildActivity(MainActivity::class.java, connect).setup()
        assertNull(shadowOf(controller.get()).lastRequestedPermission)
        assertTrue(refusals.notifications.value)
        assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
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
}
