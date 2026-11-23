package dev.doona.android.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The share step: the share sheet the export opens, and the clear that only a pick in it sends. */
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
        assertNotNull("the sheet tells the app of a pick", pickSender(chooser))
    }

    /** The sender `Intent.createChooser` stores: under its own key from Android 15, under the older one before. */
    private fun pickSender(chooser: Intent): IntentSender? = listOf(Intent.EXTRA_CHOOSER_RESULT_INTENT_SENDER, Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER)
        .firstNotNullOfOrNull { IntentCompat.getParcelableExtra(chooser, it, IntentSender::class.java) }

    /** The sheet calls back, naming the app picked, or none for one of its own actions. */
    private fun pick(sender: IntentSender, app: ComponentName?) {
        sender.sendIntent(this.app, 0, Intent().apply { app?.let { putExtra(Intent.EXTRA_CHOSEN_COMPONENT, it) } }, null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `an export opens the share sheet, whose return keeps the crash files and whose pick clears them`() {
        val engine = app.container.engine as FakeEngine
        val dir = app.container.crashFiles.dir.apply { mkdirs() }
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        engine.crashes = listOf(CrashFile("1795300150000.txt", 13))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()

        app.container.reports.startExport()
        // The zip is written, then its URI resolved, each off the main thread, before the sheet opens.
        idleUntil { shadowOf(controller.get()).peekNextStartedActivityForResult() != null }
        val started = shadowOf(controller.get()).nextStartedActivityForResult
        assertWellFormed(started.intent)

        // The sheet closed without a pick answers as a share sent through it does: no result.
        shadowOf(controller.get()).receiveResult(started.intent, Activity.RESULT_CANCELED, null)
        idleUntil { app.container.reports.export.value == ExportState.Idle }
        assertTrue("a return alone keeps the crash files", engine.crashes.isNotEmpty())

        // The sheet's Copy names no app and sends the zip nowhere; a pick names the app.
        val sender = pickSender(started.intent)!!
        pick(sender, app = null)
        assertTrue(engine.crashes.isNotEmpty())
        pick(sender, ComponentName("com.example.mail", "com.example.mail.Compose"))
        idleUntil { engine.crashes.isEmpty() }
        controller.close()
    }
}
