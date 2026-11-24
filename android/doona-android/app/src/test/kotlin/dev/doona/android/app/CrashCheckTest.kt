package dev.doona.android.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The look for a crash to offer the report for, which every activity creation asks for ([AppContainer.checkCrashes]). */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class CrashCheckTest {
    private val app = ApplicationProvider.getApplicationContext<DoonaApplication>()

    @Test
    fun `a look that could not read the crash files leaves the offer to the next activity`() {
        val container = app.container
        // A file where the directory goes: the lock cannot be opened, so the first look reads nothing.
        val dir = File(app.noBackupFilesDir, "crash").apply { writeText("x") }
        runBlocking { container.checkCrashes().join() }
        assertFalse(container.crashOffer.offered.value)

        assertTrue(dir.delete() && dir.mkdirs())
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        runBlocking { container.checkCrashes().join() }
        assertTrue("one failed read hid the offer for the process", container.crashOffer.offered.value)
    }
}
