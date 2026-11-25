package dev.doona.android.app

import android.Manifest
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

/** 實驗室's 重開 App ends the process; what it starts decides whether the tunnel that went down with it comes back. */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class AppRestartTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowVpnService.setPrepareResult(null)
    }

    @Test
    fun `a restart while connected opens the connect entry, which connects again`() {
        val intent = checkNotNull(restartIntent(app, connected = true))
        assertEquals(MainActivity.connectIntent(app).component, intent.component)
        assertEquals(MainActivity.ACTION_CONNECT, intent.action)
        assertTrue(intent.flags and RESTART_TASK == RESTART_TASK)

        ActivityScenario.launch<MainActivity>(intent).use {
            assertEquals(Status.Running(sinceEpochMs = 0), app.container.engine.status.value)
        }
    }

    @Test
    fun `a restart while disconnected opens the launcher activity and leaves the VPN off`() {
        val intent = checkNotNull(restartIntent(app, connected = false))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNotEquals(MainActivity.ACTION_CONNECT, intent.action)
        assertTrue(intent.flags and RESTART_TASK == RESTART_TASK)

        ActivityScenario.launch<MainActivity>(intent).use {
            assertEquals(Status.Stopped, app.container.engine.status.value)
        }
    }

    private companion object {
        const val RESTART_TASK = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
}
