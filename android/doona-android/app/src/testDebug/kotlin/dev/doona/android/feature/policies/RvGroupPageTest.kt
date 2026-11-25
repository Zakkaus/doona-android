package dev.doona.android.feature.policies

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import dev.doona.android.designsystem.rv.rvShownName
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.samples.RvGroupFullSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The group page's approved features (s1125d) one by one, on the sample's data: the chips under the search field, the
 * subscription chip's filter, the sections folded above two subscriptions, the 分開選擇 row, the 套用到 row and the
 * emoji display rule, and the order 依名稱.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvGroupPageTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun `the two chips stand under the search field`() {
        page(RvGroupFullSample.two, apart = true)
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chips = listOf("全部訂閱", "依延遲").map { compose.onNode(hasText(it), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot }
        chips.forEach { assertTrue("a chip at ${it.top} is under the field ending at ${field.bottom}", it.top >= field.bottom) }
    }

    @Test
    fun `a subscription picked in its chip shows that section alone`() {
        val sample = RvGroupFullSample.five
        page(sample, apart = true, filter = RvGroupFilter(subscription = sample.subscriptions[MERIDIAN].name))
        assertEquals(1, shown(node(sample, MERIDIAN_NODE)))
        assertEquals("Orchard Link holds TCP's node but is not picked", 0, shown(node(sample, ORCHARD_NODE)))
        assertEquals("its 訂閱 row and the chip", 2, shown(sample.subscriptions[MERIDIAN].name))
    }

    @Test
    fun `with more than two subscriptions only the sections holding a chosen node start open`() {
        val sample = RvGroupFullSample.five
        page(sample, apart = true)
        assertEquals("TCP's node's section", 1, shown(node(sample, ORCHARD_NODE)))
        assertEquals("UDP's node's section", 1, shown(node(sample, TIDEWATER_NODE)))
        assertEquals("a section with no chosen node", 0, shown(node(sample, HARBOR_NODE)))
    }

    @Test
    fun `with two subscriptions every section is open`() {
        val sample = RvGroupFullSample.two
        page(sample, apart = false)
        sample.group.members.forEach { assertTrue("${it.name} is listed", shown(rvShownName(it.name).text.ifEmpty { return@forEach }) >= 1) }
    }

    @Test
    fun `the 分開選擇 row turns the switch`() {
        val apart = mutableListOf<Boolean>()
        page(RvGroupFullSample.two, apart = false, actions = RvGroupActions(apart = { apart += it }))
        compose.onNode(hasText("分開選擇 TCP/UDP"), useUnmergedTree = true).performClick()
        assertEquals(listOf(true), apart)
    }

    @Test
    fun `套用到 stands while the switch is on`() {
        page(RvGroupFullSample.two, apart = true)
        assertEquals(1, shown("套用到"))
    }

    @Test
    fun `套用到 is not there while the switch is off`() {
        page(RvGroupFullSample.two, apart = false)
        assertEquals(0, shown("套用到"))
    }

    @Test
    fun `套用到 is not there in a Score group, which picks each network's node itself`() {
        page(RvGroupFullSample.two, apart = true, score = true)
        assertEquals(0, shown("套用到"))
    }

    @Test
    fun `a 套用到 choice is handed to the page`() {
        val applied = mutableListOf<RvApplyTo>()
        page(RvGroupFullSample.two, apart = true, actions = RvGroupActions(apply = { applied += it }))
        // The control comes before the list, where UDP's row may begin with the same word.
        compose.onAllNodes(hasText("UDP"), useUnmergedTree = true)[0].performClick()
        assertEquals(listOf(RvApplyTo.Udp), applied)
    }

    @Test
    fun `a name's known emoji is drawn as our icon and left out of the name`() {
        val apple = RvGroupFullSample.two.group.members[APPLE].name
        assertTrue(apple.startsWith("🍎 "))
        page(RvGroupFullSample.two, apart = true)
        assertTrue("the name without its emoji", shown(apple.removePrefix("🍎 ")) >= 1)
        assertEquals("the emoji is not in the text", 0, shown(apple))
    }

    @Test
    fun `an emoji we do not know stays in the name`() {
        val sample = RvGroupFullSample.five
        val unknown = sample.group.members[UNKNOWN].name
        assertTrue(unknown.startsWith("🦄 "))
        page(sample, apart = true, filter = RvGroupFilter(subscription = sample.group.members[UNKNOWN].subscription))
        assertEquals(1, shown(unknown))
    }

    @Test
    fun `a tap on a node hands its id to the page`() {
        val taps = mutableListOf<String>()
        val sample = RvGroupFullSample.two
        page(sample, apart = false, actions = RvGroupActions(tap = { taps += it }))
        compose.onNode(hasText(node(sample, PLAIN_TWO)), useUnmergedTree = true).performClick()
        assertEquals(listOf(sample.group.members[PLAIN_TWO].id), taps)
    }

    @Test
    fun `依名稱 reads the name as it is drawn, emoji left out, and puts untested nodes last`() {
        val base = RvData.nodes
        val untested = base[0].copy(id = "a", name = "Alpha", history = emptyList())
        val hongKong = base[1].copy(id = "h", name = "🇭🇰 Hong Kong", history = listOf(50))
        val beta = base[2].copy(id = "b", name = "beta", history = listOf(30))
        assertEquals(listOf("b", "h", "a"), rvSectionOrder(listOf(untested, hongKong, beta), RvNodeSort.Name).map { it.id })
    }

    private fun page(
        sample: RvGroupFullSample.Sample,
        apart: Boolean,
        score: Boolean = false,
        filter: RvGroupFilter = RvGroupFilter(),
        actions: RvGroupActions = RvGroupActions(),
    ) {
        val choice = RvApplyChoice(sample.tcpId, if (apart) sample.udpId else sample.tcpId, apart)
        compose.rvContent(dark = false) {
            RvGroupPage(RvGroupContent(sample.group, sample.subscriptions, choice, RvApplyTo.Tcp, score, RvData.nowEpochMs), actions, filter = filter)
        }
    }

    private fun node(sample: RvGroupFullSample.Sample, index: Int): String = sample.group.members[index].name

    private fun shown(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().size

    private companion object {
        // Members whose names carry no emoji (the sample gives every third from the second one), by subscription.
        const val HARBOR_NODE = 2
        const val ORCHARD_NODE = 18
        const val MERIDIAN_NODE = 24
        const val TIDEWATER_NODE = 36
        const val PLAIN_TWO = 6
        const val MERIDIAN = 3

        // The sample's emoji names: 🍎 Apple at the fifth member, an unknown 🦄 at the eleventh (of five subscriptions).
        const val APPLE = 4
        const val UNKNOWN = 10
    }
}
