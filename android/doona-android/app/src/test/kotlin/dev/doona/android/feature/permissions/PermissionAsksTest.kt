package dev.doona.android.feature.permissions

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The order of the two permissions (owner, q-perm): the VPN said before its consent, the notification after a connection. */
class PermissionAsksTest {
    private val saved = SavedStateHandle()
    private val asks = PermissionAsks(saved)

    private var asked = false
    private var granted = false
    private val notify = NotifyState { !granted && !asked }
    private var records = 0
    private var storeFails = false

    private fun status(running: Boolean, on: PermissionAsks = asks) = runBlocking {
        on.onStatus(running, notify) {
            if (storeFails) throw IOException("disk full")
            records++
            asked = true
        }
    }

    /** What a new process gets back: the saved state alone, as Android restores it into a recreated activity. */
    private fun afterProcessDeath() = PermissionAsks(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))

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
    fun aCancelledExplanationAsksNothingIsToldAndShowsAgainOnTheNextConnect() {
        asks.connect(consentNeeded = true)
        asks.dismiss()
        assertNull(asks.showing.value)
        assertEquals("取消 is a refusal", Permission.Vpn, asks.refused.value)
        assertEquals(PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
        assertNull("the next 連線 clears it", asks.refused.value)
    }

    @Test
    fun anExplanationClosedByBackRefusesNothing() {
        asks.connect(consentNeeded = true)
        asks.close()
        assertNull(asks.showing.value)
        assertNull(asks.refused.value)
        assertEquals("the 連線 ended", PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
        asks.close()
        status(running = true)
        assertEquals(Permission.Notify, asks.showing.value)
        asks.close()
        status(running = true)
        assertNull(asks.refused.value)
        assertEquals("the notification's counts as asked", 1, records)
    }

    @Test
    fun notNowOnTheNotificationsExplanationIsToldAndSurvivesProcessDeath() {
        status(running = true)
        asks.dismiss()
        assertEquals(Permission.Notify, asks.refused.value)
        assertEquals(Permission.Notify, afterProcessDeath().refused.value)
    }

    @Test
    fun aRefusedConsentIsToldUntilTheNextConnect() {
        asks.connect(consentNeeded = true)
        asks.confirm()
        assertEquals(PermissionStep.Wait, asks.consentAnswered(given = false))
        assertEquals(Permission.Vpn, asks.refused.value)
        assertEquals("kept for 活動 in a new process", Permission.Vpn, afterProcessDeath().refused.value)
        asks.connect(consentNeeded = false)
        assertNull(asks.refused.value)
    }

    @Test
    fun theNotificationIsExplainedOnceAConnectionIsUpAndOnlyOnce() {
        status(running = false)
        assertNull("not before a connection", asks.showing.value)
        status(running = true)
        assertEquals(Permission.Notify, asks.showing.value)
        assertEquals("not stored before the user answers", 0, records)
        asks.dismiss()
        status(running = false)
        assertEquals(1, records)
        status(running = true)
        assertNull("暫時不要 does not come back", asks.showing.value)
        assertEquals(1, records)
    }

    @Test
    fun anActivityThatDiesBeforeTheAnswerShowsTheExplanationAgainAndStoresNothing() {
        status(running = true)
        val restored = afterProcessDeath()
        assertEquals(Permission.Notify, restored.showing.value)
        assertEquals(0, records)
        restored.dismiss()
        status(running = true, on = restored)
        assertEquals(1, records)
        assertNull(restored.showing.value)
    }

    @Test
    fun anAnswerGivenBeforeTheActivityDiesIsStoredByTheNextOne() {
        status(running = true)
        asks.dismiss()
        val restored = afterProcessDeath()
        assertTrue(restored.unrecorded.value)
        status(running = false, on = restored)
        assertEquals(1, records)
        assertFalse(restored.unrecorded.value)
    }

    @Test
    fun theSystemsDialogIsNotExplainedOverAndItsAnswerIsStored() {
        status(running = true)
        assertEquals(PermissionStep.AskNotify, asks.confirm())
        status(running = true)
        assertNull("not explained again while the system's dialog is open", asks.showing.value)
        assertEquals(0, records)
        val restored = afterProcessDeath()
        status(running = true, on = restored)
        assertNull("nor in a recreated activity", restored.showing.value)
        restored.notifyAnswered(granted = true)
        status(running = true, on = restored)
        assertEquals(1, records)
    }

    @Test
    fun aConnectDuringTheNotificationsExplanationWaitsBehindIt() {
        status(running = true)
        assertEquals("a 連線 from the tile", PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals("the notification's explanation stays", Permission.Notify, asks.showing.value)
        val restored = afterProcessDeath()
        restored.dismiss()
        assertEquals("the VPN's comes next", Permission.Vpn, restored.showing.value)
        assertEquals(PermissionStep.AskConsent, restored.confirm())
        assertNull(restored.showing.value)
        status(running = true, on = restored)
        assertEquals(1, records)
    }

    @Test
    fun theNotificationsContinueStillLeadsToTheWaitingVpnExplanation() {
        status(running = true)
        asks.connect(consentNeeded = true)
        assertEquals(PermissionStep.AskNotify, asks.confirm())
        assertEquals(Permission.Vpn, asks.showing.value)
        asks.dismiss()
        assertNull(asks.showing.value)
        assertEquals("the 連線 ended with its explanation", PermissionStep.Wait, asks.connect(consentNeeded = true))
        assertEquals(Permission.Vpn, asks.showing.value)
    }

    @Test
    fun anAnswerThatCannotBeStoredIsKeptAndStoredNextTime() {
        status(running = true)
        asks.dismiss()
        storeFails = true
        assertThrows(IOException::class.java) { status(running = true) }
        assertTrue(asks.unrecorded.value)
        assertNull("not explained again meanwhile", asks.showing.value)
        storeFails = false
        status(running = true)
        assertEquals(1, records)
        assertNull(asks.showing.value)
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
