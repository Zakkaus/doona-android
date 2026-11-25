package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.nodes.RvNodePickerPage
import dev.doona.android.feature.nodes.RvNodesPage
import dev.doona.android.feature.nodes.RvNodesPageState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodePickerPageSample
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyScreenSample
import dev.doona.android.prototype.rv.subs.RvSubsActions
import dev.doona.android.prototype.rv.subs.RvSubsPage
import dev.doona.android.prototype.rv.subs.RvSubsState
import dev.doona.android.prototype.rv.subs.rvSubsStateSample
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pages that list what a subscription brings, before there is one: 策略 (`proto-rv-policy-empty-*`), the main node's
 * picker (`-policy-picker-empty-*`), 節點 (`-nodes-page-empty-*`) and 訂閱 (`-subs-empty-*`), each with the empty state
 * 活動 draws in place of its cards; light, dark, fs115 and fs200.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNoSubscriptionShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun policyLight() = shot("policy-empty", dark = false) { Policy() }

    @Test fun policyDark() = shot("policy-empty", dark = true) { Policy() }

    @Test fun policyFs115() = shot("policy-empty", fontScale = RV_FS115) { Policy() }

    @Test fun policyFs200() = shot("policy-empty", fontScale = RV_FS200) { Policy() }

    @Test fun pickerLight() = shot("policy-picker-empty", dark = false) { Picker() }

    @Test fun pickerDark() = shot("policy-picker-empty", dark = true) { Picker() }

    @Test fun pickerFs115() = shot("policy-picker-empty", fontScale = RV_FS115) { Picker() }

    @Test fun pickerFs200() = shot("policy-picker-empty", fontScale = RV_FS200) { Picker() }

    @Test fun nodesLight() = shot("nodes-page-empty", dark = false) { Nodes() }

    @Test fun nodesDark() = shot("nodes-page-empty", dark = true) { Nodes() }

    @Test fun nodesFs115() = shot("nodes-page-empty", fontScale = RV_FS115) { Nodes() }

    @Test fun nodesFs200() = shot("nodes-page-empty", fontScale = RV_FS200) { Nodes() }

    @Test fun subsLight() = shot("subs-empty", dark = false) { Subs() }

    @Test fun subsDark() = shot("subs-empty", dark = true) { Subs() }

    @Test fun subsFs115() = shot("subs-empty", fontScale = RV_FS115) { Subs() }

    @Test fun subsFs200() = shot("subs-empty", fontScale = RV_FS200) { Subs() }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, fontScale = fontScale, content = content)

    @Composable
    private fun Policy() = RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}, noSubscription = {})

    @Composable
    private fun Picker() =
        RvNodePickerPageSample(emptyList(), emptyList(), RvData.CURRENT_ID, RvNodeSort.Latency, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {})

    @Composable
    private fun Nodes() = RvNodesPage(RvNodesPageState(emptyList(), RvData.CURRENT_ID, emptyList()), onBack = {}, onOpen = {}, onAllNodes = {})

    @Composable
    private fun Subs() = RvSubsPage(rvSubsStateSample(subscriptions = emptyList()), RvSubsActions())
}
