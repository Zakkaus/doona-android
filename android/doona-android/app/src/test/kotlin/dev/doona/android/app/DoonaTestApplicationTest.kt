package dev.doona.android.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DoonaTestApplicationTest {
    /** WorkManager schedules in-process only in its default process: the configured one, else the app's own. */
    @Test
    fun workManagersDefaultProcessIsNotTheTestProcess() {
        val app = ApplicationProvider.getApplicationContext<DoonaTestApplication>()
        val configured = app.workManagerConfiguration.defaultProcessName
        assertNotNull(configured)
        assertNotEquals(app.applicationInfo.processName, configured)
        assertNotEquals(app.packageName, configured)
    }
}
