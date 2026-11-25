package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvScrollBy
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 策略 tab and the pages it pushes, light and dark, each in one capture of the whole page: 策略 in 規則
 * (`proto-rv-policy-*`), in 全域 (`proto-rv-policy-global-*`, the node picker under the mode, in the phone's window, and
 * `proto-rv-policy-global-scrolled-*`, scrolled to the second subscription) and in 直連 (`proto-rv-policy-direct-*`, the
 * mode alone), a selector's page (串流, `proto-rv-policy-group-select-*`), a url-test group's page (自動,
 * `proto-rv-policy-group-auto-*`) and the quick rule opened from a connection (`proto-rv-policy-flow-*`); the main node's
 * picker is RvNodeListShotTest's. 策略 at font scale 1.15 and 2.0 (`proto-rv-policy-fs115`, `-fs200`) and the quick rule at
 * 2.0 (`proto-rv-policy-flow-fs200`) are in the phone's window, for their sliders. A tap on a node in 全域 moves the check to it, and its order stays in the tab's state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPolicyShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun ruleLight() = compose.rvShot("policy", dark = false, before = { assertWhole() }) { Policy(RvRouteMode.Rule) }

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun ruleDark() = compose.rvShot("policy", dark = true, before = { assertWhole() }) { Policy(RvRouteMode.Rule) }

    @Test
    fun globalLight() = compose.rvShot("policy-global", dark = false) { Policy(RvRouteMode.Global) }

    @Test
    fun globalDark() = compose.rvShot("policy-global", dark = true) { Policy(RvRouteMode.Global) }

    @Test
    fun globalScrolledLight() = compose.rvShot("policy-global-scrolled", dark = false, before = { rvScrollBy(GLOBAL_SCROLL) }) { Policy(RvRouteMode.Global) }

    @Test
    fun globalScrolledDark() = compose.rvShot("policy-global-scrolled", dark = true, before = { rvScrollBy(GLOBAL_SCROLL) }) { Policy(RvRouteMode.Global) }

    @Test
    fun aTapInGlobalMovesTheCheck() {
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                var state by remember { mutableStateOf(RvPolicyState(mode = RvRouteMode.Global)) }
                RvPolicyScreen(state, onChange = { state = it })
            }
        }
        compose.onNodeWithText(MAIN_NODE).assertIsSelected()
        compose.onNodeWithText(OTHER_NODE).performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithText(OTHER_NODE).assertIsSelected()
        compose.onNodeWithText(MAIN_NODE).assertIsNotSelected()
    }

    /** The tab bar's pill is 全域's only search: the node list under the mode has none of its own (rule 9). */
    @Test
    fun globalHasOneSearch() {
        compose.setContent { RvTheme(rvSystemScheme(false), false) { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Global), onChange = {}) } }
        assertEquals(1, compose.onAllNodesWithText(SEARCH, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun anOrderInGlobalStaysInTheTabsState() {
        var state = RvPolicyState(mode = RvRouteMode.Global)
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                var shown by remember { mutableStateOf(state) }
                RvPolicyScreen(shown, onChange = {
                    shown = it
                    state = it
                })
            }
        }
        compose.onNodeWithText(LATENCY).assertIsSelected()
        compose.onNodeWithText(NAME).performClick().assertIsSelected()
        compose.onNodeWithText(LATENCY).assertIsNotSelected()
        assertEquals(RvNodeSort.Name, state.nodeSort)
    }

    /** The mode's slider (look E) at the large font sizes, in the phone's window: one size for the row, nothing cut. */
    @Test
    fun ruleFs115() = compose.rvShot("policy", dark = false, fontScale = RV_FS115) { Policy(RvRouteMode.Rule) }

    @Test
    fun ruleFs200() = compose.rvShot("policy", dark = false, fontScale = RV_FS200) { Policy(RvRouteMode.Rule) }

    /** 新增規則's match slider at font scale 2.0, in the phone's window. */
    @Test
    fun flowFs200() = compose.rvShot("policy-flow", dark = false, fontScale = RV_FS200) { Flow() }

    @Test
    fun directLight() = compose.rvShot("policy-direct", dark = false, before = { assertWhole() }) { Policy(RvRouteMode.Direct) }

    @Test
    fun directDark() = compose.rvShot("policy-direct", dark = true, before = { assertWhole() }) { Policy(RvRouteMode.Direct) }

    @Test
    @Config(qualifiers = WHOLE_SELECT)
    fun groupSelectLight() = compose.rvShot("policy-group-select", dark = false, before = { assertWhole() }) { Group(RvPolicyData.streaming) }

    @Test
    @Config(qualifiers = WHOLE_SELECT)
    fun groupSelectDark() = compose.rvShot("policy-group-select", dark = true, before = { assertWhole() }) { Group(RvPolicyData.streaming) }

    @Test
    @Config(qualifiers = WHOLE_AUTO)
    fun groupAutoLight() = compose.rvShot("policy-group-auto", dark = false, before = { assertWhole() }) { Group(RvPolicyData.auto) }

    @Test
    @Config(qualifiers = WHOLE_AUTO)
    fun groupAutoDark() = compose.rvShot("policy-group-auto", dark = true, before = { assertWhole() }) { Group(RvPolicyData.auto) }

    @Test
    @Config(qualifiers = WHOLE_FLOW)
    fun flowLight() = compose.rvShot("policy-flow", dark = false, before = { assertWhole() }) { Flow() }

    @Test
    @Config(qualifiers = WHOLE_FLOW)
    fun flowDark() = compose.rvShot("policy-flow", dark = true, before = { assertWhole() }) { Flow() }

    @Composable
    private fun Policy(mode: RvRouteMode) = RvPolicyScreen(RvPolicyState(mode = mode), onChange = {})

    @Composable
    private fun Group(group: RvPolicyGroup) = RvPolicyGroupPage(group, onBack = {}, onSelect = {}, onTest = {})

    @Composable
    private fun Flow() = RvPolicyRulePage(RvPolicyData.connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {})

    private companion object {
        /** Windows as tall as each whole page, so one capture holds it to its end (the tab's last card 28 dp above the nav). */
        const val WHOLE_RULE = "+h1990dp"
        const val WHOLE_SELECT = "+h1136dp"
        const val WHOLE_AUTO = "+h1149dp"
        const val WHOLE_FLOW = "+h1296dp"

        /** 全域 scrolled this far shows the second subscription's card under the bar (1048 with the in-list search pill, 56 taller). */
        const val GLOBAL_SCROLL = 992f

        /** The main node in the sample, and another node of the first subscription that a tap makes the main node. */
        const val MAIN_NODE = "Hong Kong 02 IPLC"
        const val OTHER_NODE = "Taipei 04"

        /** The search pill's placeholder. */
        const val SEARCH = "搜尋節點"

        /** Two of the node list's order chips. */
        const val LATENCY = "延遲"
        const val NAME = "名稱"
    }
}
