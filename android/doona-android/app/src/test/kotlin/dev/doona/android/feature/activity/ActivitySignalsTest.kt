package dev.doona.android.feature.activity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** [steadyOnline]: 活動 says the network is down only once it stays down. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivitySignalsTest {
    @Test
    fun `a network down for less than the grace never reads offline, one down longer does`() = runTest {
        // The source starts offline until Android first answers.
        val source = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { steadyOnline(source).collect { seen += it } }
        advanceTimeBy(OFFLINE_GRACE_MS / 2)
        source.value = true
        advanceTimeBy(OFFLINE_GRACE_MS * 2)
        assertEquals(listOf(true), seen)
        // A handover: down for a moment, then up again.
        source.value = false
        advanceTimeBy(OFFLINE_GRACE_MS - 1)
        source.value = true
        assertEquals(listOf(true), seen)
        source.value = false
        advanceTimeBy(OFFLINE_GRACE_MS + 1)
        assertEquals(listOf(true, false), seen)
        source.value = true
        assertEquals(listOf(true, false, true), seen)
    }
}
