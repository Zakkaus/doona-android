package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.nodes.RV_NODES_DETAIL
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a real engine gives that the sample's dataset never did, on the pages that read it; each light, dark, fs115 and
 * fs200: a node with no probe record (`proto-rv-node-detail-probes0-*`: a dash for the figure and 上次測試, the plot's
 * grid and labels with 尚無資料 at its centre) and with one (`-node-detail-probes1-*`: its figure and time, no median, the
 * one dot at the plot's centre with its marker and time). Light and dark hold the whole page; fs115 and fs200 its first
 * 915 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvDataRealismShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_NODE)
    fun probes0Light() = shot("node-detail-probes0", dark = false) { Probes(0) }

    @Test
    @Config(qualifiers = WHOLE_NODE)
    fun probes0Dark() = shot("node-detail-probes0", dark = true) { Probes(0) }

    @Test fun probes0Fs115() = shot("node-detail-probes0", fontScale = RV_FS115) { Probes(0) }

    @Test fun probes0Fs200() = shot("node-detail-probes0", fontScale = RV_FS200) { Probes(0) }

    @Test
    @Config(qualifiers = WHOLE_NODE)
    fun probes1Light() = shot("node-detail-probes1", dark = false) { Probes(1) }

    @Test
    @Config(qualifiers = WHOLE_NODE)
    fun probes1Dark() = shot("node-detail-probes1", dark = true) { Probes(1) }

    @Test fun probes1Fs115() = shot("node-detail-probes1", fontScale = RV_FS115) { Probes(1) }

    @Test fun probes1Fs200() = shot("node-detail-probes1", fontScale = RV_FS200) { Probes(1) }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        // The larger text outgrows the window, so only the default size is held to fit it.
        compose.rvShot(name, dark, before = { if (fontScale == 1f) assertWhole() }, fontScale = fontScale, content = content)
    }

    /** The detail page's node, as the engine has it after [probes] probes: the first of its sample tests, or none. */
    @Composable
    private fun Probes(probes: Int) {
        val node = RvData.nodes.first { it.id == RV_NODES_DETAIL }
        RvNodeDetail(
            node.copy(history = node.history.take(probes)),
            current = false,
            testedAtEpochMs = rvTestTimes().take(probes),
            onBack = {},
            onUse = {},
            onTest = {},
        )
    }

    private companion object {
        /** The detail page is taller than the phone: the window of `proto-rv-node-detail-*`. */
        const val WHOLE_NODE = "+h1220dp"
    }
}
