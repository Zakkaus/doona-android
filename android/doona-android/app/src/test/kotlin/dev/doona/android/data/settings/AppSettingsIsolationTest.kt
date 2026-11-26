package dev.doona.android.data.settings

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.FakeEngineApplication
import dev.doona.android.testing.runBlockingWithMainLooper
import kotlinx.coroutines.flow.first
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
 * notification ask recorded by one activity test kept the next from explaining the notification. The app's own startup
 * reads of the store wait for the main looper, so the tests read with [runBlockingWithMainLooper], not runBlocking.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppSettingsIsolationTest {
    private val app = ApplicationProvider.getApplicationContext<FakeEngineApplication>()

    @Test
    fun aTestRecordsTheNotificationAsk() = runBlockingWithMainLooper {
        AppSettings.get(app).recordNotifyAsked()
        assertTrue(AppSettings.get(app).notifyAsked.first())
    }

    @Test
    fun bTheNextTestStartsFromItsOwnSettings() = runBlockingWithMainLooper {
        assertFalse(AppSettings.get(app).notifyAsked.first())
    }
}
