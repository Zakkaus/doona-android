package dev.doona.android.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.report.ExportState
import dev.doona.android.report.REPORT_MIME
import dev.doona.android.report.reportAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The share step: the share sheet the export opens, and the clear its return sends. */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class ReportFlowTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()

    /** Runs the main looper until [done], while the zip is written on another thread. */
    private fun idleUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!done() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("timed out", done())
    }

    /**
     * The share sheet sends the zip and grants read access to it only. One test builds the URI: FileProvider keeps the
     * roots it read per authority for the process, and each Robolectric test has a data directory of its own.
     */
    private fun assertWellFormed(chooser: Intent) {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(REPORT_MIME, send.type)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(reportAuthority(app), uri.authority)
        assertFalse("the URI names no path of the app", app.cacheDir.path in uri.toString())
        assertTrue(uri.lastPathSegment!!.matches(Regex("doona-report-\\d{8}-\\d{6}\\.zip")))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, send.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun `an export opens the share sheet, and its return clears the crash files the zip holds`() {
        val engine = app.container.engine as FakeEngine
        val dir = app.container.crashFiles.dir.apply { mkdirs() }
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        engine.crashes = listOf(CrashFile("1795300150000.txt", 13))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        app.container.reports.startExport()
        idleUntil { app.container.reports.export.value is ExportState.Sharing }
        val started = shadowOf(controller.get()).nextStartedActivityForResult
        assertWellFormed(started.intent)
        assertTrue(engine.crashes.isNotEmpty())

        // Most apps answer a share with no result; any return counts.
        shadowOf(controller.get()).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        idleUntil { engine.crashes.isEmpty() }
        assertEquals(ExportState.Idle, app.container.reports.export.value)
        controller.close()
    }
}
