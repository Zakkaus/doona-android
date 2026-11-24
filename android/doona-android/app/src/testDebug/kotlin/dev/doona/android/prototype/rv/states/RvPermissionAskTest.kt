package dev.doona.android.prototype.rv.states

import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.prototype.rv.RV_CONNECT_MS
import dev.doona.android.prototype.rv.RvMotionOptions
import dev.doona.android.prototype.rv.RvPreviewApp
import dev.doona.android.prototype.rv.RvSampleStart
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first run's permission explanations ([RvPermissionAsks]): each comes before its system request, the notification's
 * only after the first connection, each once, and 取消 or 暫時不要 asks nothing and changes nothing. Then the same in the
 * sample: 連線 on the first run raises the VPN's sheet and stays 未連線 until 繼續.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPermissionAskTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val requests = mutableListOf<RvPermission>()
    private val asks = RvPermissionAsks(explained = false) { requests += it }

    @Test
    fun theVpnIsExplainedBeforeItsConsent() {
        asks.connect()
        assertEquals(RvPermission.Vpn, asks.showing)
        assertEquals(emptyList<RvPermission>(), requests)
        asks.confirm()
        assertNull(asks.showing)
        assertEquals(listOf(RvPermission.Vpn), requests)
    }

    @Test
    fun notificationsAreExplainedAfterTheFirstConnectionBeforeTheirRequest() {
        asks.connect()
        asks.confirm()
        asks.onStatus(Status.Starting)
        assertNull("nothing while connecting", asks.showing)
        asks.onStatus(Status.Running(0L))
        assertEquals(RvPermission.Notify, asks.showing)
        assertEquals(listOf(RvPermission.Vpn), requests)
        asks.confirm()
        assertEquals(listOf(RvPermission.Vpn, RvPermission.Notify), requests)
    }

    @Test
    fun aFailedStartIsNotTheFirstConnection() {
        asks.connect()
        asks.confirm()
        asks.onStatus(Status.Failed(FailureReason.StartFailed))
        assertNull(asks.showing)
        asks.connect()
        assertNull("the VPN was explained already", asks.showing)
        asks.onStatus(Status.Running(0L))
        assertEquals(RvPermission.Notify, asks.showing)
    }

    @Test
    fun eachIsShownOnce() {
        asks.connect()
        asks.confirm()
        asks.onStatus(Status.Running(0L))
        asks.confirm()
        asks.onStatus(Status.Stopped)
        asks.connect()
        assertNull(asks.showing)
        asks.onStatus(Status.Running(0L))
        assertNull(asks.showing)
        assertEquals(listOf(RvPermission.Vpn, RvPermission.Notify, RvPermission.Vpn), requests)
    }

    @Test
    fun cancelAsksNothingAndChangesNothing() {
        asks.connect()
        asks.dismiss()
        assertNull(asks.showing)
        assertEquals(emptyList<RvPermission>(), requests)
        asks.connect()
        assertEquals("the consent was never asked, so 連線 explains again", RvPermission.Vpn, asks.showing)
        asks.confirm()
        asks.onStatus(Status.Running(0L))
        asks.dismiss()
        assertEquals("暫時不要 asks nothing", listOf(RvPermission.Vpn), requests)
        asks.onStatus(Status.Stopped)
        asks.connect()
        asks.onStatus(Status.Running(0L))
        assertNull("the first connection has passed", asks.showing)
    }

    @Test
    fun explainedAlreadyGoesStraightToTheConsent() {
        val given = RvPermissionAsks(explained = true) { requests += it }
        given.connect()
        given.onStatus(Status.Running(0L))
        assertNull(given.showing)
        assertEquals(listOf(RvPermission.Vpn), requests)
    }

    @Test
    fun theSampleExplainsTheVpnThenTheNotificationOnItsFirstRun() {
        compose.setContent { RvPreviewApp(RvMotionOptions(), RvSampleStart(connected = false, firstRun = true)) }
        compose.mainClock.autoAdvance = false
        connectButton().performClick()
        compose.mainClock.advanceTimeBy(RvMotion.MENU_MS + FRAMES_MS)
        text(R.string.rv_perm_vpn_title).assertExists()
        compose.onNodeWithText(caption(R.string.status_stopped)).assertExists()
        text(R.string.rv_lan_ask_continue).performClick()
        compose.mainClock.advanceTimeBy(RV_CONNECT_MS + RvMotion.FADE_MS + RvMotion.MENU_MS)
        compose.onNodeWithText(caption(R.string.backend_connected)).assertExists()
        text(R.string.rv_perm_notify_title).assertExists()
    }

    @Test
    fun cancellingTheVpnExplanationLeavesTheSampleDisconnected() {
        compose.setContent { RvPreviewApp(RvMotionOptions(), RvSampleStart(connected = false, firstRun = true)) }
        compose.mainClock.autoAdvance = false
        connectButton().performClick()
        compose.mainClock.advanceTimeBy(RvMotion.MENU_MS + FRAMES_MS)
        text(R.string.rv_cancel).performClick()
        compose.mainClock.advanceTimeBy(RV_CONNECT_MS + RvMotion.FADE_MS + RvMotion.MENU_OUT_MS)
        compose.onNodeWithText(caption(R.string.status_stopped)).assertExists()
        // The sheet's window goes once the clock runs on past its fall.
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        text(R.string.rv_perm_vpn_title).assertDoesNotExist()
    }

    private fun connectButton(): SemanticsNodeInteraction = compose.onNode(hasText(rvText(R.string.rv_connect)) and !isSelectable())

    private fun text(@StringRes id: Int): SemanticsNodeInteraction = compose.onNodeWithText(rvText(id))

    /** 活動's caption in a state: the state, then the mode (規則). */
    private fun caption(@StringRes state: Int) = "${rvText(state)} · ${rvText(R.string.rv_mode_rule)}"

    private companion object {
        const val FRAMES_MS = 50L
    }
}
