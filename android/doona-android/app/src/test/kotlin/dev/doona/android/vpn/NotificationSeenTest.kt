package dev.doona.android.vpn

import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.NotificationManager.IMPORTANCE_NONE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSeenTest {
    @Test
    fun onlyANotificationThatCanShowWithTheScreenOnIsSeen() {
        assertTrue(isNotificationSeen(screenOn = true, appNotificationsEnabled = true, channelImportance = IMPORTANCE_LOW))
        assertFalse(isNotificationSeen(screenOn = false, appNotificationsEnabled = true, channelImportance = IMPORTANCE_LOW))
        assertFalse(isNotificationSeen(screenOn = true, appNotificationsEnabled = false, channelImportance = IMPORTANCE_LOW))
        assertFalse(isNotificationSeen(screenOn = true, appNotificationsEnabled = true, channelImportance = IMPORTANCE_NONE))
        assertFalse(isNotificationSeen(screenOn = true, appNotificationsEnabled = true, channelImportance = null))
    }
}
