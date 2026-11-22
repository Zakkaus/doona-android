package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import dev.doona.android.prototype.rv.rvScrollBy
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The main node's picker in each order, light and dark, in the phone's window: 延遲 (`proto-rv-policy-picker-*`), 穩定
 * (`-stability-*`, each row's second trailing line how many tests answered), 名稱 (`-name-*`), and 地區
 * (`-region-*`, a card per country across both subscriptions; `-region-scrolled-*` shows 香港's card, whose nodes come
 * from both, and 日本's under it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeListShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun latencyLight() = compose.rvShot("policy-picker", dark = false) { Picker(RvNodeSort.Latency) }

    @Test
    fun latencyDark() = compose.rvShot("policy-picker", dark = true) { Picker(RvNodeSort.Latency) }

    @Test
    fun stabilityLight() = compose.rvShot("policy-picker-stability", dark = false) { Picker(RvNodeSort.Stability) }

    @Test
    fun stabilityDark() = compose.rvShot("policy-picker-stability", dark = true) { Picker(RvNodeSort.Stability) }

    @Test
    fun nameLight() = compose.rvShot("policy-picker-name", dark = false) { Picker(RvNodeSort.Name) }

    @Test
    fun nameDark() = compose.rvShot("policy-picker-name", dark = true) { Picker(RvNodeSort.Name) }

    @Test
    fun regionLight() = compose.rvShot("policy-picker-region", dark = false) { Picker(RvNodeSort.Region) }

    @Test
    fun regionDark() = compose.rvShot("policy-picker-region", dark = true) { Picker(RvNodeSort.Region) }

    @Test
    fun regionScrolledLight() = compose.rvShot("policy-picker-region-scrolled", dark = false, before = { rvScrollBy(REGION_SCROLL) }) {
        Picker(RvNodeSort.Region)
    }

    @Test
    fun regionScrolledDark() = compose.rvShot("policy-picker-region-scrolled", dark = true, before = { rvScrollBy(REGION_SCROLL) }) {
        Picker(RvNodeSort.Region)
    }

    @Composable
    private fun Picker(sort: RvNodeSort) = RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, sort, onSort = {}, onPick = {}, onBack = {}, onTest = {})

    private companion object {
        /** Scrolled this far, 臺灣's card ends under the bar and 香港's follows in full. */
        const val REGION_SCROLL = 600f
    }
}
