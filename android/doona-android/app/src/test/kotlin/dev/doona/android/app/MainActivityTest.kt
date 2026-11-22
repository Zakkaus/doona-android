package dev.doona.android.app

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.Status
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowVpnService

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()
    private val connect = Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_CONNECT)

    @Before
    fun setUp() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    @Test
    fun `the connect action connects when consent is already given`() {
        ShadowVpnService.setPrepareResult(null)
        ActivityScenario.launch<MainActivity>(connect).use {
            assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        }
    }

    @Test
    fun `the connect action asks for VPN consent first`() {
        val consent = Intent("dev.doona.test.CONSENT")
        ShadowVpnService.setPrepareResult(consent)
        ActivityScenario.launch<MainActivity>(connect).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(consent.action, shadowOf(activity).nextStartedActivityForResult.intent.action)
            }
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }
}
