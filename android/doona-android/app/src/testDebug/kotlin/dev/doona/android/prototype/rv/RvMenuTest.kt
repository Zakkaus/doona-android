package dev.doona.android.prototype.rv

import android.content.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.shareLink
import dev.doona.android.prototype.rv.nodes.RV_PICKER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rows' long-press menus (brief rv-gestures G3), on a clock the test drives: a node row's menu opens at 500 ms with
 * the long press's haptic tick and its items do what they say; a subscription is deleted only after its confirm sheet; every
 * item is also a TalkBack action on the row; the share link carries the node's address.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvMenuTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val taps = RvTaps()
    private val taipei = RvData.nodes.first { it.id == "tw-04" }

    @Test
    fun aLongPressOpensTheMenuAtFiveHundredMilliseconds() {
        app()
        inPicker(taipei.name).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(BEFORE_MS)
        compose.onAllNodesWithTag(RV_MENU).assertCountIs(0, "not yet at 450 ms")
        compose.mainClock.advanceTimeBy(AFTER_MS - BEFORE_MS)
        compose.onAllNodesWithTag(RV_MENU).assertCountIs(1, "open at 550 ms")
        assertEquals(listOf(HapticFeedbackType.LongPress), taps.kinds)
        inPicker(taipei.name).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.MENU_MS.toLong())
        compose.onNodeWithText(rvText(R.string.rv_menu_set_main)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "設為主節點 picks it, as a tap does, and closes the picker")
        compose.onNode(hasText(taipei.name) and hasText(rvText(R.string.rv_main_node))).assertExists()
    }

    @Test
    fun aSubscriptionIsDeletedOnlyAfterItsQuestion() {
        app()
        val lantern = RvData.subscriptions.first { it.id != RvData.subscriptions.first().id }
        val member = RvData.nodes.first { it.subscription == lantern.name }
        longPress(lantern.name)
        compose.onNodeWithText(rvText(R.string.rv_menu_delete)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.MENU_MS.toLong())
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.rv_sub_delete_title, lantern.name)).assertExists()
        inPicker(member.name).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_menu_delete)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        compose.onAllNodesWithText(member.name).assertCountIs(0, "its nodes fold away with it")
        compose.onAllNodesWithText(lantern.name).assertCountIs(0, "the subscription is gone")
    }

    @Test
    fun everyItemIsATalkBackActionOnTheRow() {
        app()
        val labels = inPicker(taipei.name).fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }
        val expected = listOf(R.string.rv_menu_set_main, R.string.rv_test_latency, R.string.rv_menu_details, R.string.rv_menu_copy_link).map(::rvText)
        assertEquals(expected, labels)
    }

    @Test
    fun copyingTheShareLinkPutsItOnTheClipboard() {
        app()
        val copy = inPicker(taipei.name).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == rvText(R.string.rv_menu_copy_link) }
        compose.runOnIdle { copy.action() }
        compose.mainClock.advanceTimeByFrame()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        assertEquals(taipei.shareLink(), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun everyShareLinkCarriesTheNodesAddress() {
        RvData.nodes.forEach { node ->
            val link = node.shareLink()
            assertTrue(link, LINK.matches(link))
            assertTrue(link, link.contains("${node.host}:${node.port}"))
        }
    }

    private fun app() {
        compose.setContent { CompositionLocalProvider(LocalHapticFeedback provides taps) { RvPreviewApp(RvMotionOptions(), RvSampleStart(launch = RvLaunch.Nodes)) } }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
    }

    /** The picker's row with [text]: 活動 under it may show the same name. */
    private fun inPicker(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(RV_PICKER)))

    /** Scrolls the picker's row with [text] into view and holds it past the long press. */
    private fun longPress(text: String) {
        compose.mainClock.autoAdvance = true
        inPicker(text).performScrollTo()
        compose.mainClock.autoAdvance = false
        inPicker(text).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(AFTER_MS)
        inPicker(text).performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.MENU_MS.toLong())
    }

    private companion object {
        const val BEFORE_MS = 450L
        const val AFTER_MS = 550L
        val LINK = Regex("""^[a-z0-9]+://[a-z0-9.]+:\d+\?type=(tcp|ws|grpc|quic)&security=(tls|none)#\S+$""")
    }
}
