package dev.doona.android.prototype.rv

import android.content.ClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_MENU
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.feature.nodes.RV_PICKER
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.shareLink
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
    fun aLongSubscriptionNameLeavesTheRangeChipReachableAtLargeTextSize() {
        var picked = false
        compose.rvContent(dark = false, fontScale = RV_FS200) {
            val ranges = listOf(RvMenuItem("This month", R.drawable.rv_sym_rule) { picked = true })
            RvFilterChipRow(RvChipPick("Long subscription name ".repeat(12).trim()), RvChipPick("This week", items = ranges))
        }
        compose.onNodeWithText("This week").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("This month").performTouchInput { click() }
        compose.runOnIdle { assertTrue(picked) }
    }

    @Test
    fun theLastItemOfATallPopupCanBeScrolledIntoViewAndTapped() {
        var picked = -1
        compose.rvContent(dark = false) {
            val subscriptions = List(30) { i -> RvMenuItem("Subscription $i", R.drawable.rv_sym_rule) { picked = i } }
            RvFilterChipRow(RvChipPick("All subscriptions", items = subscriptions), RvChipPick("This week"))
        }
        compose.onNodeWithText("All subscriptions").performTouchInput { click() }
        compose.onNodeWithText("Subscription 29").performScrollTo().assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(29, picked) }
    }

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
        val last = RvData.subscriptions.first()
        longPress(last.name)
        compose.onNodeWithText(rvText(R.string.rv_menu_delete)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.MENU_MS.toLong())
        compose.onNodeWithText(rvText(R.string.rv_menu_delete)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.MOVE_MS * 2L)
        compose.onAllNodesWithText(last.name).assertCountIs(0, "the last subscription is gone")
        compose.onNode(hasTestTag(dev.doona.android.designsystem.rv.RvEditPart.ADD) and hasAnyDescendant(hasText(rvText(R.string.rv_addnode))) and hasAnyAncestor(hasTestTag(RV_PICKER))).assertIsNotEnabled()
        inPicker(rvText(R.string.rv_add_subscription)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.PUSH_MS * 2L)
        compose.onNodeWithText(rvText(R.string.rv_add_clipboard)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_add_import)).performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText(rvText(R.string.rv_done)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.PUSH_MS * 2L)
        val imported = RvData.nodes.first { it.subscriptionId == last.id }
        inPicker(imported.name).assertExists()
        longPress(imported.name)
        compose.onNodeWithText(rvText(R.string.rv_menu_details)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.PUSH_MS * 2L)
        compose.onNodeWithText(imported.host, useUnmergedTree = true).assertExists()
    }

    @Test
    fun deletingASubscriptionSkipsItsDetailsOnBack() {
        val start = RvSampleStart(routes = listOf(ACTIVITY, "node:hk-02", "sub:s1", "subs"))
        compose.setContent { RvPreviewApp(start = start) }
        compose.onNodeWithText(RvData.subscriptions.first().name).performTouchInput { longClick() }
        compose.onNodeWithText(rvText(R.string.rv_menu_delete)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_menu_delete)).performClick()
        compose.onNodeWithContentDescription(rvText(R.string.ui_back)).performClick()
        val group = dev.doona.android.prototype.rv.policy.RvPolicyData.streaming
        val selected = group.members.filterNot { it.subscriptionId == "s1" }.minByOrNull { it.latencyMs ?: Int.MAX_VALUE }
        val line = selected?.name ?: dev.doona.android.designsystem.rv.RV_NO_VALUE
        compose.onNode(hasText(rvText(R.string.rv_rule_streaming)) and hasText(line)).assertExists()
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
