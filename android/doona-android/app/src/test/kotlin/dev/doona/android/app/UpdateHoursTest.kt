package dev.doona.android.app

import dev.doona.android.data.settings.SubscriptionUpdate
import dev.doona.android.data.settings.UpdateInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The interval a subscription's page shows, from the settings: hours while auto-update is on, none (關閉) while off. */
class UpdateHoursTest {
    @Test
    fun `auto-update on gives its interval in hours, off gives none`() {
        assertEquals(12, updateHoursOf(SubscriptionUpdate(enabled = true, UpdateInterval.ofHours(12))))
        assertEquals(48, updateHoursOf(SubscriptionUpdate(enabled = true, UpdateInterval.ofHours(48))))
        assertNull(updateHoursOf(SubscriptionUpdate(enabled = false, UpdateInterval.ofHours(12))))
    }
}
