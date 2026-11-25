package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvUnknownPlaceNodes
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main node's picker under 地區 when some nodes' names give no country (`proto-rv-policy-picker-unknown-*`): one
 * Taiwan card, then 其他 with the three nodes, the fallback disc on its head row and on each node, and 其他 in place of a
 * country after the protocol. Light, dark, fs115 and fs200. Premium 01 is the fastest node and 其他 still comes last.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvUnknownPlaceShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun light() = shot(dark = false)

    @Test fun dark() = shot(dark = true)

    @Test fun fs115() = shot(fontScale = RV_FS115)

    @Test fun fs200() = shot(fontScale = RV_FS200)

    private fun shot(dark: Boolean = false, fontScale: Float = 1f) = compose.rvShot("policy-picker-unknown", dark, fontScale = fontScale) { Picker() }

    @Composable
    private fun Picker() = RvNodePickerPageSample(rvUnknownPlaceNodes(), RvData.subscriptions, "tw-01", RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {})
}
