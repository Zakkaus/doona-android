package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 策略 tab and the pages it pushes, light and dark, each in one capture of the whole page: 策略 in 規則
 * (`proto-rv-policy-*`) and in 全域 (`proto-rv-policy-global-*`), a selector's page (串流, `proto-rv-policy-group-select-*`),
 * a url-test group's page (自動, `proto-rv-policy-group-auto-*`), and the quick rule opened from a connection
 * (`proto-rv-policy-flow-*`).
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
    @Config(qualifiers = WHOLE_GLOBAL)
    fun globalLight() = compose.rvShot("policy-global", dark = false, before = { assertWhole() }) { Policy(RvRouteMode.Global) }

    @Test
    @Config(qualifiers = WHOLE_GLOBAL)
    fun globalDark() = compose.rvShot("policy-global", dark = true, before = { assertWhole() }) { Policy(RvRouteMode.Global) }

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
    private fun Flow() = RvPolicyRulePage(RvPolicyData.connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {}, onHold = {})

    private companion object {
        /** Windows as tall as each whole page, so one capture holds it to its end (the tab's last card 28 dp above the nav). */
        const val WHOLE_RULE = "+h1962dp"
        const val WHOLE_GLOBAL = "+h1300dp"
        const val WHOLE_SELECT = "+h1120dp"
        const val WHOLE_AUTO = "+h1145dp"
        const val WHOLE_FLOW = "+h1185dp"
    }
}
