package dev.doona.android.feature.localnetwork

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The question asked once, retried on a yes, never asked again after a no in the same process, and withdrawn unanswered. */
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
        // The tunnel's report after a refusal shows the way to the settings too, never nothing, until it clears.
        val report = launch { access.noticed() }
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        report.cancel()
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

    @Test
    fun closingTheReasonIsNoRefusal() = runTest(UnconfinedTestDispatcher()) {
        val answer = async { access.ask() }
        access.closed()
        assertFalse("closed: nothing to retry", answer.await())
        assertNull(access.prompt.value)
        // The next failure asks again rather than showing the refusal.
        val again = async { access.ask() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        again.cancel()
    }

    @Test
    fun aReasonNobodyWaitsForIsWithdrawnWithoutARefusal() = runTest(UnconfinedTestDispatcher()) {
        val asking = launch { access.ask() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        asking.cancel()
        assertNull(access.prompt.value)
        val again = async { access.ask() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        access.answered(true)
        assertTrue(again.await())
    }

    @Test
    fun aQuestionAnotherCallerSharesStaysOpen() = runTest(UnconfinedTestDispatcher()) {
        val tunnel = launch { access.noticed() }
        val action = async { access.ask() }
        tunnel.cancel()
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        access.answered(true)
        assertTrue(action.await())
    }

    @Test
    fun theSystemsAnswerCountsAfterTheCallerLeft() = runTest(UnconfinedTestDispatcher()) {
        val asking = launch { access.ask() }
        access.requesting()
        asking.cancel()
        access.answered(false)
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        access.dismissed()
        assertFalse(access.ask())
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
    }

    @Test
    fun theTunnelsRefusalComesDownWithoutAnActionsRefusal() = runTest(UnconfinedTestDispatcher()) {
        val answer = async { access.ask() }
        access.declined()
        assertFalse(answer.await())
        // The tunnel's report shows the way to the settings; the user closes it while the report stays.
        val report = launch { access.noticed() }
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        access.dismissed()
        assertNull(access.prompt.value)
        // A subscription then fails on the local network, and the tunnel's report clears while that refusal is up.
        assertFalse(access.ask())
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        report.cancel()
        assertEquals("the subscription's way to the settings stays", LocalNetworkPrompt.Denied, access.prompt.value)
        access.dismissed()
        assertNull(access.prompt.value)
    }

    @Test
    fun accessAllowedOnTheInfoPageTakesTheTunnelsRefusalDownToo() = runTest(UnconfinedTestDispatcher()) {
        val answer = async { access.ask() }
        access.declined()
        assertFalse(answer.await())
        // The tunnel's report and an import both show the refusal; the import waits on it.
        val report = launch { access.noticed() }
        assertFalse(access.ask())
        val retry = async { access.allowedInSettings() }
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        // The user leaves by the launcher, allows access on the app's info page and comes back.
        granted = true
        access.resumed()
        assertTrue("the import runs once more", retry.await())
        assertNull("access is allowed: no refusal stays", access.prompt.value)
        report.cancel()
        assertNull(access.prompt.value)
    }

    @Test
    fun continuingAWithdrawnReasonAsksTheSystemNothing() = runTest(UnconfinedTestDispatcher()) {
        var requests = 0
        val host = LocalNetworkHost(access, request = { requests++ }, openSettings = {})
        val report = launch { access.noticed() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        // The tunnel's report clears as the user taps Continue on the reason still on screen.
        report.cancel()
        host.proceed()
        assertEquals(0, requests)
        val asking = async { access.ask() }
        host.proceed()
        host.proceed()
        assertEquals("one system question for one reason", 1, requests)
        access.answered(true)
        assertTrue(asking.await())
    }
}
