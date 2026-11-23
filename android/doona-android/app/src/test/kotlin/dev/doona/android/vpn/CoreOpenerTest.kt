package dev.doona.android.vpn

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.Executors

/** The opener asks for the data directory on its IO dispatcher: finding an app directory makes it, which is disk work. */
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
}
