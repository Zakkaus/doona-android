package dev.doona.android.prototype.rv.policy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvMenuPanel
import dev.doona.android.designsystem.rv.RvMenuSize
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvActivityScreenSample
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Choosing TCP and UDP apart in a selector (design B, the owner's pick of 2026-11-25), 串流 with Taipei 04 on both
 * networks or Hong Kong 01 on UDP, each light, dark, fs115 and fs200, whole: the group's page with the switch 分開選擇
 * TCP 與 UDP off (`proto-rv-netsel-group-*`) and on (`-netsel-group-apart-*`); the node picker as it is, opened from the
 * UDP row (`-netsel-picker-*`); the switch on and a tap on a node of 節點's card, which asks which network it is for, the
 * menu where its popup stands under the row's end (`-netsel-ask-*`); 活動 with 串流 apart, a network a line and the
 * group's glyph in place of a flag (`-netsel-activity-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNetworkShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    /** Where the asked node's row is, once the page is laid out; the menu stands under its end. */
    private var row by mutableStateOf<DpRect?>(null)

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = GROUP)
    fun groupLight() = page(GROUP_NAME, dark = false) { Group(RvNetworkSample.shared) }

    @Test
    @Config(qualifiers = GROUP)
    fun groupDark() = page(GROUP_NAME, dark = true) { Group(RvNetworkSample.shared) }

    @Test
    @Config(qualifiers = GROUP_FS115)
    fun groupFs115() = page(GROUP_NAME, fontScale = RV_FS115) { Group(RvNetworkSample.shared) }

    @Test
    @Config(qualifiers = GROUP_FS200)
    fun groupFs200() = page(GROUP_NAME, fontScale = RV_FS200) { Group(RvNetworkSample.shared) }

    @Test
    @Config(qualifiers = APART)
    fun apartLight() = page(APART_NAME, dark = false) { Group(RvNetworkSample.apart) }

    @Test
    @Config(qualifiers = APART)
    fun apartDark() = page(APART_NAME, dark = true) { Group(RvNetworkSample.apart) }

    @Test
    @Config(qualifiers = APART_FS115)
    fun apartFs115() = page(APART_NAME, fontScale = RV_FS115) { Group(RvNetworkSample.apart) }

    @Test
    @Config(qualifiers = APART_FS200)
    fun apartFs200() = page(APART_NAME, fontScale = RV_FS200) { Group(RvNetworkSample.apart) }

    @Test
    @Config(qualifiers = APART)
    fun askLight() = ask(dark = false)

    @Test
    @Config(qualifiers = APART)
    fun askDark() = ask(dark = true)

    @Test
    @Config(qualifiers = APART_FS115)
    fun askFs115() = ask(fontScale = RV_FS115)

    @Test
    @Config(qualifiers = APART_FS200)
    fun askFs200() = ask(fontScale = RV_FS200)

    @Test
    @Config(qualifiers = PICKER)
    fun pickerLight() = picker(dark = false)

    @Test
    @Config(qualifiers = PICKER)
    fun pickerDark() = picker(dark = true)

    @Test
    @Config(qualifiers = PICKER_FS115)
    fun pickerFs115() = picker(fontScale = RV_FS115)

    @Test
    @Config(qualifiers = PICKER_FS200)
    fun pickerFs200() = picker(fontScale = RV_FS200)

    @Test
    @Config(qualifiers = ACTIVITY)
    fun activityLight() = page(ACTIVITY_NAME, dark = false) { Activity() }

    @Test
    @Config(qualifiers = ACTIVITY)
    fun activityDark() = page(ACTIVITY_NAME, dark = true) { Activity() }

    @Test
    @Config(qualifiers = ACTIVITY_FS115)
    fun activityFs115() = page(ACTIVITY_NAME, fontScale = RV_FS115) { Activity() }

    @Test
    @Config(qualifiers = ACTIVITY_FS200)
    fun activityFs200() = page(ACTIVITY_NAME, fontScale = RV_FS200) { Activity() }

    /** The switch off: the switch and one row in 目前使用中, no chip, no network named. */
    @Test
    @Config(qualifiers = GROUP)
    fun sharedNamesNoNetwork() {
        compose.rvContent(dark = false) { Group(RvNetworkSample.shared) }
        assertEquals(1, count(rvText(R.string.rv_network_apart)))
        assertEquals(0, count("TCP / "))
        assertEquals(0, count("UDP / "))
        assertEquals(0, count("已選"))
    }

    /** The switch on: a row per network under it and both checked in 節點, each line beginning with its network. */
    @Test
    @Config(qualifiers = APART)
    fun apartNamesEachNetwork() {
        compose.rvContent(dark = false) { Group(RvNetworkSample.apart) }
        assertEquals(2, count("TCP / "))
        assertEquals(2, count("UDP / "))
    }

    /** The switch on, both networks on one node: that node's line keeps the prefix `TCP/UDP` that names both. */
    @Test
    @Config(qualifiers = APART)
    fun apartOnOneNodeNamesBoth() {
        compose.rvContent(dark = false) { Group(RvNetworkSample.apartSame) }
        assertEquals(1, count("TCP/UDP / "))
        assertEquals(1, count("TCP / "))
    }

    /** The switch on: a tap on a node asks first, and the answer picks it for that network alone, or for both. */
    @Test
    @Config(qualifiers = APART)
    fun apartTapAsksTheNetwork() {
        var choice by mutableStateOf(RvNetworkSample.apart)
        compose.rvContent(dark = false) { Page(choice) { choice = it } }
        val asked = RvNetworkSample.asked
        compose.onNodeWithText(asked.name).performClick()
        compose.waitForIdle()
        assertEquals(choice.udpId, RvNetworkSample.apart.udpId)
        compose.onNodeWithText(rvText(R.string.ui_udp)).performClick()
        compose.waitForIdle()
        assertEquals(RvNetworkSample.apart.tcpId, choice.tcpId)
        assertEquals(asked.id, choice.udpId)
        // Now UDP's choice, the node is in 目前使用中 too: tap its row on 節點's card, the one that selects.
        compose.onNode(hasText(asked.name) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_network_both)).performClick()
        compose.waitForIdle()
        assertEquals(asked.id, choice.tcpId)
        assertEquals(asked.id, choice.udpId)
    }

    /** The switch off: a tap picks for both at once, with no question; a screen reader gets no network actions. */
    @Test
    @Config(qualifiers = GROUP)
    fun sharedTapPicksBoth() {
        var choice by mutableStateOf(RvNetworkSample.shared)
        compose.rvContent(dark = false) { Page(choice) { choice = it } }
        val asked = RvNetworkSample.asked
        val row = compose.onNodeWithText(asked.name).fetchSemanticsNode()
        assertTrue(SemanticsActions.CustomActions !in row.config)
        compose.onNodeWithText(asked.name).performClick()
        compose.waitForIdle()
        assertEquals(asked.id, choice.tcpId)
        assertEquals(asked.id, choice.udpId)
    }

    /** Off, UDP goes back to TCP's node; on again, each network keeps that node until one is picked. */
    @Test
    fun switchOffJoinsTheNetworks() {
        val off = RvNetworkSample.apart.apart(false)
        assertEquals(RvNetworkSample.apart.tcpId, off.udpId)
        assertTrue(!off.apart && !off.differ)
        assertTrue(off.apart(true).apart)
    }

    /** The picker from the UDP row says the network in its caption and names no network on the rows. */
    @Test
    @Config(qualifiers = PICKER)
    fun pickerSaysItsNetwork() {
        rvAnimatorScale(0f)
        compose.rvContent(dark = false) { Picker() }
        assertEquals(1, count("UDP / "))
        assertEquals(0, count("TCP"))
    }

    /** 活動 gives each of 串流's networks a line of its own, with no separator at a line's end. */
    @Test
    @Config(qualifiers = ACTIVITY)
    fun activityNamesANetworkALine() {
        compose.rvContent(dark = false) { Activity() }
        assertTrue(lines().toString(), "TCP Taipei 04\nUDP Hong Kong 01" in lines())
    }

    /** Two nodes in one region keep the region's flag; two regions lead with the group's glyph (checked on the goldens). */
    @Test
    @Config(qualifiers = ACTIVITY)
    fun activityOneRegionNamesBoth() {
        val udp = RvNetworkSample.rulesOneRegion.first { RvLabel.Res(it.group) == RvNetworkSample.group.name }.udpNodeId
        compose.rvContent(dark = false) { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}, rules = RvNetworkSample.rulesOneRegion) }
        val name = RvNetworkSample.group.members.first { it.id == udp }.name
        assertTrue(lines().toString(), "TCP Taipei 04\nUDP $name" in lines())
    }

    // RvText binds a line's words (U+2060, no-break spaces); compare the words.
    private fun lines() = compose.onAllNodesWithText("UDP", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        .map { it.text.replace("⁠", "").replace(' ', ' ') }

    private fun count(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun page(name: String, dark: Boolean = false, fontScale: Float = 1f, before: () -> Unit = {}, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = {
            assertWhole()
            before()
        }, fontScale = fontScale, content = content)

    /** The picker whole, at animator scale 0 as the node picker's goldens. */
    private fun picker(dark: Boolean = false, fontScale: Float = 1f) {
        rvAnimatorScale(0f)
        page(PICKER_NAME, dark, fontScale) { Picker() }
    }

    /** The page with the switch on and the network menu of [RvNetworkSample.asked] open under its row's end. */
    private fun ask(dark: Boolean = false, fontScale: Float = 1f) = page(ASK_NAME, dark, fontScale, before = {
        row = compose.onNodeWithText(RvNetworkSample.asked.name).getBoundsInRoot()
        compose.waitForIdle()
    }) {
        Box(Modifier.fillMaxSize()) {
            Group(RvNetworkSample.apart)
            row?.let { at ->
                RvMenuPanel(
                    rvNetworkMenu {},
                    onPick = {},
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = maxOf(WIDTH - at.right, RvMenuSize.margin))
                        .offset(y = at.bottom + RvMenuSize.anchorGap),
                )
            }
        }
    }

    @Composable
    private fun Group(choice: RvNetworkChoice) =
        RvPolicyGroupPage(RvNetworkSample.group, onBack = {}, onSelect = {}, onTest = {}, networks = choice)

    /** The page as the app holds it: the switch and every pick change [choice] through [onChoice]. */
    @Composable
    private fun Page(choice: RvNetworkChoice, onChoice: (RvNetworkChoice) -> Unit) = RvPolicyGroupPage(
        RvNetworkSample.group,
        onBack = {},
        onSelect = { onChoice(choice.picked(it, null)) },
        onTest = {},
        networks = choice,
        onApart = { onChoice(choice.apart(it)) },
        onSelectFor = { id, network -> onChoice(choice.picked(id, network)) },
    )

    @Composable
    private fun Picker() = RvNetworkPicker(RvNetworkSample.group, RvNetworkSample.apart, RvNetwork.Udp, RvData.subscriptions, RvData.nowEpochMs)

    @Composable
    private fun Activity() = RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}, rules = RvNetworkSample.rules)

    private companion object {
        const val GROUP_NAME = "netsel-group"
        const val APART_NAME = "netsel-group-apart"
        const val ASK_NAME = "netsel-ask"
        const val PICKER_NAME = "netsel-picker"
        const val ACTIVITY_NAME = "netsel-activity"
        val WIDTH = 412.dp

        /** Windows as tall as each page whole, rounded up to a whole dp (measured: 1271.5, 1274, 2045; 1353.5, 1356, 2298). */
        const val GROUP = "+h1272dp"
        const val GROUP_FS115 = "+h1274dp"
        const val GROUP_FS200 = "+h2045dp"
        const val APART = "+h1354dp"
        const val APART_FS115 = "+h1356dp"
        const val APART_FS200 = "+h2298dp"

        /** The picker whole (measured: 2842, 3083, 5903.5). */
        const val PICKER = "+h2905dp"
        const val PICKER_FS115 = "+h3144dp"
        const val PICKER_FS200 = "+h5904dp"

        /** 活動 whole (measured: 1970.5, 2015, 3212). */
        const val ACTIVITY = "+h1971dp"
        const val ACTIVITY_FS115 = "+h2015dp"
        const val ACTIVITY_FS200 = "+h3212dp"
    }
}
