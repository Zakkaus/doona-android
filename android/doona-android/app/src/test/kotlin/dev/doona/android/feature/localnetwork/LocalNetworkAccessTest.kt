package dev.doona.android.feature.localnetwork

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The question asked once, retried on a yes, and never asked again after a no in the same process. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkAccessTest {
    private var granted = false
    private val access = LocalNetworkAccess { granted }

    @Test
    fun anAllowedAccessAsksNothingAndIsNotWorthARetry() = runTest {
        granted = true
        assertFalse(access.ask())
        assertNull(access.prompt.value)
    }

    @Test
    fun aYesInTheSystemDialogIsWorthOneMoreTry() = runTest(UnconfinedTestDispatcher()) {
        val answer = async { access.ask() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        assertNull("the reason gives way to the system's dialog", access.prompt.value)
        granted = true
        access.answered(true)
        assertTrue(answer.await())
        assertNull(access.prompt.value)
        // Allowed now: a later failure has another cause and asks nothing.
        assertFalse(access.ask())
        assertNull(access.prompt.value)
    }

    @Test
    fun failuresWhileTheQuestionIsOpenShareItsAnswer() = runTest(UnconfinedTestDispatcher()) {
        val first = async { access.ask() }
        val second = async { access.ask() }
        access.requesting()
        access.answered(true)
        assertTrue(first.await() && second.await())
    }

    @Test
    fun aNoInTheSystemDialogShowsTheWayToTheSettingsAndIsFinal() = runTest(UnconfinedTestDispatcher()) {
        val answer = async { access.ask() }
        access.requesting()
        access.answered(false)
        assertFalse(answer.await())
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        access.dismissed()
        assertNull(access.prompt.value)
        // The next failure shows the refusal again without asking.
        assertFalse(access.ask())
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        access.dismissed()
        // The tunnel's report after a refusal shows nothing.
        access.noticed()
        assertNull(access.prompt.value)
    }

    @Test
    fun notNowIsARefusalWithoutASecondDialog() = runTest(UnconfinedTestDispatcher()) {
        val answer = async { access.ask() }
        access.declined()
        assertFalse(answer.await())
        assertNull(access.prompt.value)
        // A failure the user starts later still leads to the settings.
        assertFalse(access.ask())
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
    }

    @Test
    fun theTunnelsReportAsksOnceAndAYesNeedsNothingMore() = runTest(UnconfinedTestDispatcher()) {
        val noticed = async { access.noticed() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        granted = true
        access.answered(true)
        noticed.await()
        assertNull(access.prompt.value)
        access.noticed()
        assertNull(access.prompt.value)
    }
}
