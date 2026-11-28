package dev.doona.android.vpn

import android.content.Intent
import android.os.Looper
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.DoonaApplication
import dev.doona.android.app.FakeEngineApplication
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.report.AppInfo
import dev.doona.android.report.AppReportExporter
import dev.doona.android.report.CrashFiles
import dev.doona.android.report.CrashOffer
import dev.doona.android.report.ExportTokens
import dev.doona.android.report.REPORT_MIME
import dev.doona.android.report.ReportExport
import dev.doona.android.report.reportDir
import dev.doona.android.report.testFlowEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

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

    /** No result of the sheet reaches a broadcast: once it started, the zip goes when a page's would. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun adbsZipGoesTheGracePeriodAfterTheSheetStarted() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val data = app.noBackupFilesDir
        val files = CrashFiles(File(data, "crash"))
        val offer = CrashOffer(files, File(data, "crash-offered"))
        val tokens = ExportTokens { File(data, "report-exports.json") }
        val engine = FakeEngine()
        val logLevels = LogLevelController({ engine.setLogLevel(LogLevel.valueOf(it.name)) }, backgroundScope) { testScheduler.currentTime }
        val reports = ReportExport(engine, FakeHonk(), files, offer, { reportDir(app) }, tokens, { AppInfo.of(app) }, this, io, logLevels, testFlowEvents(io))
        val started = mutableListOf<Intent>()
        shareReport(AppReportExporter(app, reports, io)) { started += it }
        assertEquals(1, started.size)
        val zips = { reportDir(app).list().orEmpty().filter { it.endsWith(".zip") } }
        assertEquals(1, zips().size)
        advanceTimeBy(ReportExport.SHARE_GRACE_MS - 1)
        assertEquals("gone before the grace period ended", 1, zips().size)
        advanceTimeBy(2)
        assertEquals("kept after the grace period", emptyList<String>(), zips())
    }
}
