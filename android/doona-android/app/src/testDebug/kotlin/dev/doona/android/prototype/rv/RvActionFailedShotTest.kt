package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A failed action as the banner at the top of the page body, where it happened: 策略 after 切換模式 met a `busy` core
 * (`proto-rv-policy-action-failed-*`), and the node picker after a node switch met an `internal` error
 * (`-node-picker-action-failed-*`). The other pages and reasons draw the same banner with their own title and reason, the
 * list is in pv-d-STATE.md. Each light, dark, fs115 and fs200, its first screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActionFailedShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun policyLight() = shot("policy-action-failed", dark = false) { Policy() }

    @Test fun policyDark() = shot("policy-action-failed", dark = true) { Policy() }

    @Test fun policyFs115() = shot("policy-action-failed", fontScale = RV_FS115) { Policy() }

    @Test fun policyFs200() = shot("policy-action-failed", fontScale = RV_FS200) { Policy() }

    @Test fun pickerLight() = shot("node-picker-action-failed", dark = false) { Picker() }

    @Test fun pickerDark() = shot("node-picker-action-failed", dark = true) { Picker() }

    @Test fun pickerFs115() = shot("node-picker-action-failed", fontScale = RV_FS115) { Picker() }

    @Test fun pickerFs200() = shot("node-picker-action-failed", fontScale = RV_FS200) { Picker() }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, fontScale = fontScale, content = content)

    @Composable
    private fun Policy() = RvPolicyScreen(RvPolicyState(), onChange = {}, notice = { Failed(R.string.rv_failed_switch_mode, R.string.error_busy) })

    @Composable
    private fun Picker() = RvNodePicker(
        RvData.nodes,
        RvData.subscriptions,
        RvData.CURRENT_ID,
        onPick = {},
        onDetail = {},
        onClose = {},
        notice = { Failed(R.string.rv_failed_switch_node, R.string.error_internal) },
    )

    /** The one banner every failed action shows: what failed, the engine's reason, 重試. */
    @Composable
    private fun Failed(title: Int, reason: Int) = RvBanner(stringResource(title), stringResource(reason), stringResource(R.string.rv_retry), {})
}
