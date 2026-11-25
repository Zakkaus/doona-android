package dev.doona.android.feature.permissions

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The order of the two permissions (owner, q-perm): the VPN said before its consent, the notification after a connection. */
class PermissionAsksTest {
    private val saved = SavedStateHandle()
    private val asks = PermissionAsks(saved)

    private var asked = false
    private var granted = false
    private val notify = NotifyState { !granted && !asked }
    private var records = 0

    private fun status(running: Boolean) = runBlocking {
        asks.onStatus(running, notify) {
            records++
            asked = true
        }
    }

    @Test
    fun aConnectionThatNeedsTheConsentExplainsTheVpnFirst() {
        assertEquals(PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
        assertEquals("繼續 opens the system's consent", PermissionStep.AskConsent, asks.confirm())
        assertNull(asks.showing.value)
        assertEquals("given, the activity connects", PermissionStep.Connect, asks.consentAnswered(given = true))
        assertNull(asks.refused.value)
    }

    @Test
    fun aGivenConsentConnectsWithoutAnExplanation() {
        assertEquals(PermissionStep.Connect, asks.connect(consentNeeded = false))
        assertNull(asks.showing.value)
    }

    @Test
    fun aRepeatedConnectJoinsThePendingOneUntilTheConsentIsAnswered() {
        asks.connect(consentNeeded = true)
        asks.confirm()
        assertEquals(PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertNull("the explanation does not show again over the system's dialog", asks.showing.value)
        asks.consentAnswered(given = true)
        assertEquals(PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
    }

    @Test
    fun aCancelledExplanationAsksNothingAndShowsAgainOnTheNextConnect() {
        asks.connect(consentNeeded = true)
        asks.dismiss()
        assertNull(asks.showing.value)
        assertNull("nothing was refused", asks.refused.value)
        assertEquals(PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
    }

    @Test
    fun aRefusedConsentIsToldUntilTheNextConnect() {
        asks.connect(consentNeeded = true)
        asks.confirm()
        assertEquals(PermissionStep.Wait, asks.consentAnswered(given = false))
        assertEquals(Permission.Vpn, asks.refused.value)
        asks.connect(consentNeeded = false)
        assertNull(asks.refused.value)
    }

    @Test
    fun theNotificationIsExplainedOnceAConnectionIsUpAndOnlyOnce() {
        status(running = false)
        assertNull("not before a connection", asks.showing.value)
        status(running = true)
        assertEquals(Permission.Notify, asks.showing.value)
        assertEquals(1, records)
        asks.dismiss()
        status(running = false)
        status(running = true)
        assertNull("暫時不要 does not come back", asks.showing.value)
        assertEquals(1, records)
    }

    @Test
    fun theNotificationsContinueOpensTheSystemsDialogAndARefusalIsTold() {
        status(running = true)
        assertEquals(PermissionStep.AskNotify, asks.confirm())
        asks.notifyAnswered(granted = false)
        assertEquals(Permission.Notify, asks.refused.value)
        asks.clearRefusal()
        assertNull(asks.refused.value)
    }

    @Test
    fun aGrantedNotificationPermissionIsNotAskedFor() {
        granted = true
        status(running = true)
        assertNull(asks.showing.value)
        assertEquals(0, records)
    }

    @Test
    fun theExplanationAndThePendingConnectSurviveARecreatedActivity() {
        asks.connect(consentNeeded = true)
        val again = PermissionAsks(saved)
        assertEquals(Permission.Vpn, again.showing.value)
        again.confirm()
        assertEquals("still pending", PermissionStep.Wait, PermissionAsks(saved).connect(consentNeeded = true))
    }
}
