package dev.doona.android.app

import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.service.chooser.ChooserResult
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.report.REPORT_MIME
import dev.doona.android.report.ReportPickReceiver
import dev.doona.android.report.ReportShare
import dev.doona.android.report.reportAuthority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The share step, through the export's API ([dev.doona.android.report.ReportExporter]): its share sheet, and the clear that only a pick in it sends. */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class ReportFlowTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()
    private val mail = ComponentName("com.example.mail", "com.example.mail.Compose")

    /**
     * FileProvider keeps the roots it read per authority for the process, and each Robolectric test has a data directory
     * of its own: forget them, so each test's report URI resolves.
     */
    @Before
    fun forgetProviderRoots() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as HashMap<*, *>
        synchronized(cache) { cache.clear() }
    }

    /** The share sheet sends the zip and grants read access to it only. */
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

    /** A report from the export's API, as a page asks for it; the zip is written, and its URI resolved, off this thread. */
    private fun export(): ReportShare = runBlocking { app.container.reportExporter.export() }!!

    @Test
    fun `an export gives a share sheet whose return keeps the crash files and whose pick clears them`() {
        val engine = app.container.engine as FakeEngine
        val dir = app.container.crashFiles.dir.apply { mkdirs() }
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        engine.crashes = listOf(CrashFile("1795300150000.txt", 13))

        val share = export()
        assertWellFormed(share.intent)

        // The sheet closed without a pick answers as a share sent through it does: no result.
        share.returned()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("a return alone keeps the crash files", engine.crashes.isNotEmpty())

        // The sheet's Copy names no app and sends the zip nowhere; a pick names the app.
        val sender = pickSender(share.intent)!!
        pick(sender, app = null)
        assertTrue(engine.crashes.isNotEmpty())
        pick(sender, mail)
        assertTrue(engine.crashes.isEmpty())
    }

    /** Exports a report, closes its sheet without a pick, and answers the sheet's callback. */
    private fun share(): IntentSender {
        val share = export()
        share.returned()
        return pickSender(share.intent)!!
    }

    /** The sheet's callback from Android 15: a result, which names the app picked only for a pick. */
    private fun chooserResult(sender: IntentSender, type: Int, app: ComponentName?) {
        val result = ReflectionHelpers.callConstructor(
            ChooserResult::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, type),
            ClassParameter.from(ComponentName::class.java, app),
            ClassParameter.from(Boolean::class.javaPrimitiveType, false),
        )
        sender.sendIntent(this.app, 0, Intent().putExtra(Intent.EXTRA_CHOOSER_RESULT, result), null, null)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a pick reported as a chooser result clears, a copy does not`() {
        val engine = app.container.engine as FakeEngine
        val dir = app.container.crashFiles.dir.apply { mkdirs() }
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        engine.crashes = listOf(CrashFile("1795300150000.txt", 13))
        val sender = share()
        chooserResult(sender, ChooserResult.CHOOSER_RESULT_COPY, null)
        assertTrue(engine.crashes.isNotEmpty())
        chooserResult(sender, ChooserResult.CHOOSER_RESULT_SELECTED_COMPONENT, mail)
        assertTrue(engine.crashes.isEmpty())
    }

    @Test
    fun `a late pick in the first sheet clears only the first export's crash files`() {
        val engine = app.container.engine as FakeEngine
        val dir = app.container.crashFiles.dir.apply { mkdirs() }
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        engine.crashes = listOf(CrashFile("1795300150000.txt", 13))
        val first = share()
        // A crash after the first export: only the second holds it.
        dir.resolve("1795300160000.txt").writeText("source: core\n")
        engine.crashes = engine.crashes + CrashFile("1795300160000.txt", 12)
        val second = share()
        pick(first, mail)
        assertEquals(listOf("1795300160000.txt"), engine.crashes.map { it.name })
        pick(second, mail)
        assertTrue(engine.crashes.isEmpty())
    }

    @Test
    fun `the sheet calls back a receiver in the manifest, which no other app can reach`() {
        val receiver = ComponentName(app, ReportPickReceiver::class.java)
        val info = app.packageManager.getReceiverInfo(receiver, PackageManager.ComponentInfoFlags.of(0))
        assertFalse(info.exported)
    }
}
