package dev.doona.android.report

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.FakeEngineApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The handler as the application installs it, with the main thread the Looper names. */
@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class CrashInstallTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a crash while another thread holds the Looper's class still hands on by the deadline`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        val handed = CountDownLatch(1)
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> handed.countDown() }
            CrashCapture.install(CrashFiles(tmp.root.resolve("crash")), "v")
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            val crashing = Thread { handler.uncaughtException(Thread.currentThread(), IllegalStateException()) }
            // Looper.getMainLooper() takes this monitor.
            synchronized(Looper::class.java) {
                val begun = System.nanoTime()
                crashing.start()
                assertTrue("the crash was not handed on", handed.await(5, TimeUnit.SECONDS))
                assertTrue("handed on late", System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(1))
            }
            crashing.join()
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
