package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import dev.doona.android.prototype.rv.nodes.RvNodePickerPage
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyGroupPage
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The node rows of a policy group's page (`proto-rv-policy-group-testing-*`, the whole page as `-policy-group-select-*`)
 * and of the main node's picker (`-policy-picker-testing-*`) while a latency test of every node runs
 * ([LocalRvTesting]): the pull-down picker's form, a busy bar in place of the latency and its change. Each light, dark,
 * fs115 and fs200, at animator scale 0, so the bars rest half full. 測試此組 and 測試延遲 have no busy form and keep
 * their look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeRowsTestingShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun pickerLight() = shot("policy-picker-testing", dark = false) { Picker() }

    @Test fun pickerDark() = shot("policy-picker-testing", dark = true) { Picker() }

    @Test fun pickerFs115() = shot("policy-picker-testing", fontScale = RV_FS115) { Picker() }

    @Test fun pickerFs200() = shot("policy-picker-testing", fontScale = RV_FS200) { Picker() }

    @Test
    @Config(qualifiers = GROUP)
    fun groupLight() = shot("policy-group-testing", dark = false, whole = true) { Group() }

    @Test
    @Config(qualifiers = GROUP)
    fun groupDark() = shot("policy-group-testing", dark = true, whole = true) { Group() }

    @Test
    @Config(qualifiers = GROUP_FS115)
    fun groupFs115() = shot("policy-group-testing", fontScale = RV_FS115, whole = true) { Group() }

    @Test
    @Config(qualifiers = GROUP_FS200)
    fun groupFs200() = shot("policy-group-testing", fontScale = RV_FS200, whole = true) { Group() }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, whole: Boolean = false, content: @Composable () -> Unit) {
        rvAnimatorScale(0f)
        compose.rvShot(name, dark, before = { if (whole) assertWhole() }, fontScale = fontScale, content = content)
    }

    @Composable
    private fun Picker() = CompositionLocalProvider(LocalRvTesting provides true) {
        RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Latency, onSort = {}, onPick = {}, onBack = {}, onTest = {})
    }

    @Composable
    private fun Group() = CompositionLocalProvider(LocalRvTesting provides true) {
        RvPolicyGroupPage(RvPolicyData.streaming, onBack = {}, onSelect = {}, onTest = {})
    }

    private companion object {
        /** Windows as tall as the group page whole, so one capture holds it to its end (measured: 1123.5, 1126 and 1520 dp). */
        const val GROUP = "+h1124dp"
        const val GROUP_FS115 = "+h1126dp"
        const val GROUP_FS200 = "+h1774dp"
    }
}
