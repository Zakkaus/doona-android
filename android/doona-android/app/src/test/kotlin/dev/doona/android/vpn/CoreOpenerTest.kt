package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.Executors

/** What the opener hands the core's `init`. It asks on its IO dispatcher: finding an app directory makes it, which is disk work. */
@RunWith(AndroidJUnit4::class)
class CoreOpenerTest {
    @Test
    fun `the data directory is asked for on the IO dispatcher`() {
        var ioThread: Thread? = null
        val io = Executors.newSingleThreadExecutor { Thread(it).also { thread -> ioThread = thread } }
        try {
            var askedOn: Thread? = null
            val opener = CoreOpener(FakeCore(), { "/data".also { askedOn = Thread.currentThread() } }, io.asCoroutineDispatcher())
            assertEquals(CORE_SCHEMA, runBlocking { opener.schema() })
            assertSame(ioThread, askedOn)
        } finally {
            io.shutdown()
        }
    }

    @Test
    fun `init carries the stored performance mode`() {
        for (enabled in listOf(false, true)) {
            val core = FakeCore()
            val opener = CoreOpener(core, { "/data" }, Dispatchers.IO) { enabled }
            assertEquals(CORE_SCHEMA, runBlocking { opener.schema() })
            assertEquals(listOf("""{"performanceMode":$enabled}"""), core.initOptions)
        }
    }

    @Test
    fun `an unreadable performance mode opens the core with it off`() {
        val core = FakeCore()
        val opener = CoreOpener(core, { "/data" }, Dispatchers.IO) { throw IOException("corrupt app_settings") }
        assertEquals(CORE_SCHEMA, runBlocking { opener.schema() })
        assertEquals(listOf("""{"performanceMode":false}"""), core.initOptions)
    }
}
