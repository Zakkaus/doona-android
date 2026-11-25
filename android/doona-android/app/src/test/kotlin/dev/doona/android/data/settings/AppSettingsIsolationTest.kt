package dev.doona.android.data.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.FakeEngineApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.annotation.Config

/**
 * [AppSettings.get] in two tests of one sandbox, in this order: Robolectric gives each test a new application over new
 * files, and the second test must not see what the first wrote. With one process-wide instance it did: the
 * notification ask recorded by one activity test kept the next from explaining the notification.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppSettingsIsolationTest {
    private val app = ApplicationProvider.getApplicationContext<FakeEngineApplication>()

    @Test
    fun aTestRecordsTheNotificationAsk() = runBlocking {
        AppSettings.get(app).recordNotifyAsked()
        assertTrue(AppSettings.get(app).notifyAsked.first())
    }

    @Test
    fun bTheNextTestStartsFromItsOwnSettings() = runBlocking {
        assertFalse(AppSettings.get(app).notifyAsked.first())
    }
}
