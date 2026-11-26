package dev.doona.android.testing

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [runBlocking] for a test that runs on Robolectric's paused main looper. A plain runBlocking there stops the looper until
 * its block ends, so the block never ends if it waits for a coroutine that waits for the looper. The app starts such
 * coroutines in onCreate: AppContainer reads AppSettings on Dispatchers.Main, and the first read holds DataStore's
 * init mutex while it goes to Dispatchers.IO and back to the main looper. A test that then reads or writes the same
 * store waits for that mutex forever. This runs [block] on Dispatchers.IO and runs the looper's due tasks until it
 * ends; past [timeout] it fails rather than hang the job.
 */
fun <T> runBlockingWithMainLooper(timeout: Duration = 30.seconds, block: suspend CoroutineScope.() -> T): T {
    val looper = shadowOf(Looper.getMainLooper())
    val result = CoroutineScope(Dispatchers.IO).async { withTimeout(timeout) { block() } }
    while (!result.isCompleted) {
        looper.idle()
        Thread.sleep(1)
    }
    return runBlocking { result.await() }
}
