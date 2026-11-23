package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.LocalRvTesting
import dev.doona.android.prototype.rv.LocalRvTestingNodes
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvShot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The node picker 活動's pull-down opens, at rest (`proto-rv-node-picker-*`, the baseline it had none of), while a latency
 * test of every node runs (`-node-picker-testing-*`) and while one node's own test runs (`-node-picker-testing-one-*`);
 * each light, dark, fs115 and fs200, its first screen, at animator scale 0 so the busy bars rest half full.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodePickerShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun pickerLight() = shot("node-picker", dark = false)

    @Test fun pickerDark() = shot("node-picker", dark = true)

    @Test fun pickerFs115() = shot("node-picker", fontScale = RV_FS115)

    @Test fun pickerFs200() = shot("node-picker", fontScale = RV_FS200)

    @Test fun testingLight() = shot("node-picker-testing", dark = false, testing = true)

    @Test fun testingDark() = shot("node-picker-testing", dark = true, testing = true)

    @Test fun testingFs115() = shot("node-picker-testing", fontScale = RV_FS115, testing = true)

    @Test fun testingFs200() = shot("node-picker-testing", fontScale = RV_FS200, testing = true)

    @Test fun testingOneLight() = shot("node-picker-testing-one", dark = false, only = setOf(ONE))

    @Test fun testingOneDark() = shot("node-picker-testing-one", dark = true, only = setOf(ONE))

    @Test fun testingOneFs115() = shot("node-picker-testing-one", fontScale = RV_FS115, only = setOf(ONE))

    @Test fun testingOneFs200() = shot("node-picker-testing-one", fontScale = RV_FS200, only = setOf(ONE))

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, testing: Boolean = false, only: Set<String> = emptySet()) {
        rvAnimatorScale(0f)
        compose.rvShot(name, dark, fontScale = fontScale) {
            CompositionLocalProvider(LocalRvTesting provides testing, LocalRvTestingNodes provides only) {
                RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {})
            }
        }
    }

    private companion object {
        /** The first node of the first subscription other than the current one: in the first screen's first card. */
        val ONE = RvData.nodes.first { it.subscription == RvData.subscriptions.first().name && it.id != RvData.CURRENT_ID }.id
    }
}
