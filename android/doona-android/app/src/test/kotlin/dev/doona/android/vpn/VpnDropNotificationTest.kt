package dev.doona.android.vpn

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.LanguageSettings
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class VpnDropNotificationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy { AppSettings(AppSettings.store(scope) { folder.root.resolve("settings.preferences_pb") }) }
    private val notifications = VpnNotifications(context)

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifications.createChannel()
    }

    @After
    fun closeSettings() = scope.cancel()

    @Test
    @Config(sdk = [31], qualifiers = "en")
    fun notificationTitleUsesTheAppLanguageUntilCleared() {
        LanguageSettings.setLanguageTags(context, "zh-TW")
        assertEquals("連線中…", VpnNotifications(context).ongoing(Status.Starting).extras.getString(Notification.EXTRA_TITLE))
        LanguageSettings.setLanguageTags(context, "")
        assertEquals("Connecting…", VpnNotifications(context).ongoing(Status.Starting).extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun unexpectedFailureIsDismissibleAndKeepsTheFailureReason() = runBlocking {
        notifications.showDrop(Session(1, Status.Failed(FailureReason.CoreStopped)), settings) { true }
        val posted = manager.activeNotifications.single()
        assertNotEquals(VpnNotifications.ID, posted.id)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(posted.notification.channelId).importance)
        assertEquals(context.getString(R.string.vpn_failure_core_stopped), posted.notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue(posted.notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
    }

    @Test
    fun explicitStopAndSupersededDropAreSilentButRevocationAlerts() = runBlocking {
        notifications.showDrop(Session(1, Status.Stopped), settings) { true }
        val revoked = Session(2, Status.Failed(FailureReason.Revoked))
        notifications.showDrop(revoked, settings) { false }
        assertTrue(manager.activeNotifications.isEmpty())
        notifications.showDrop(revoked, settings) { true }
        assertEquals(
            context.getString(R.string.vpn_drop_revoked),
            manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT),
        )
    }

    @Test
    fun turningOffThePreferenceSuppressesUnexpectedDrops() = runBlocking {
        settings.setNotifyOnDrop(false)
        val failure = Session(1, Status.Failed(FailureReason.CoreStopped))
        notifications.showDrop(failure, settings) { true }
        assertTrue(manager.activeNotifications.isEmpty())

        settings.setNotifyOnDrop(true)
        notifications.showDrop(failure, settings) { true }
        assertEquals(context.getString(R.string.vpn_failed_title), manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun anUnreadablePreferenceDoesNotOptTheUserIn() = runBlocking {
        folder.root.resolve("settings.preferences_pb").writeBytes(ByteArray(8) { -1 })
        notifications.showDrop(Session(1, Status.Failed(FailureReason.CoreStopped)), settings) { true }
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
