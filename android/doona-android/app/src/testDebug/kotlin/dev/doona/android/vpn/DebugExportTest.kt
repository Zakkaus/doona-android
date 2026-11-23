package dev.doona.android.vpn

import android.content.Intent
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.DoonaApplication
import dev.doona.android.app.FakeEngineApplication
import dev.doona.android.report.REPORT_MIME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** adb's report export: with no page to start it, the hook writes the report and starts its share sheet itself. */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class DebugExportTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()

    /** FileProvider keeps the roots it read per authority for the process; each Robolectric test has a data directory of its own. */
    @Before
    fun forgetProviderRoots() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as HashMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    @Test
    fun adbExportsTheReportAndStartsItsShareSheet() {
        DebugHooks().onReceive(app, Intent(DebugHooks.ACTION_EXPORT))
        // The zip is written off the main thread, then the sheet is started from it.
        val deadline = System.nanoTime() + 10_000_000_000
        while (shadowOf(app).peekNextStartedActivity() == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("no share sheet started", started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertTrue("a task of its own, as an application starts it", started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        val send = IntentCompat.getParcelableExtra(started, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(REPORT_MIME, send.type)
    }
}
