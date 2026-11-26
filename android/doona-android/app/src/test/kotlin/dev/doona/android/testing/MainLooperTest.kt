package dev.doona.android.testing

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainLooperTest {
    /**
     * What the app's startup reads do to DataStore's init mutex: a coroutine on the main looper holds a lock while it goes
     * to Dispatchers.IO and back. A plain runBlocking on this thread would wait for the lock forever.
     */
    @Test
    fun aLockHeldAcrossAHopToTheMainLooperIsReleased() {
        val lock = Mutex()
        CoroutineScope(Dispatchers.Main.immediate).launch { lock.withLock { withContext(Dispatchers.IO) {} } }
        assertTrue(lock.isLocked)

        assertEquals(1, runBlockingWithMainLooper { lock.withLock { 1 } })
    }
}
