package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.feature.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A node's page after its last test failed with `local_network` (`proto-rv-node-detail-local-network-*`): sg-03 at an
 * address on the local network, its earlier tests answered and the last did not, so 延遲紀錄 shows a dash and the last test
 * as the empty ring, and 上次測試 keeps its time. The banner over 延遲紀錄 is the one every failed action shows
 * (`RvBannerTest`): kind Error, what failed, the engine's reason for the code, 重試. Light and dark hold the whole page,
 * fs200 its first screen. A node never tested is
 * `proto-rv-node-detail-probes0-*` (`RvDataRealismShotTest`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeLocalNetworkShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun light() = shot(dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun dark() = shot(dark = true)

    @Test fun fs200() = shot(fontScale = RV_FS200)

    private fun shot(dark: Boolean = false, fontScale: Float = 1f) =
        // The text sizes of fs200 outgrow the window, so only the default size is held to fit it.
        compose.rvShot("node-detail-local-network", dark, before = { if (fontScale == 1f) assertWhole() }, fontScale = fontScale) { Page() }

    @Composable
    private fun Page() {
        val node = RvData.nodes.first { it.id == RV_NODES_DETAIL }
        RvNodeDetail(
            node.copy(host = LAN_HOST, history = node.history.dropLast(1) + null),
            current = false,
            testedAtEpochMs = rvTestTimes(),
            onBack = {},
            onUse = {},
            onTest = {},
            banner = RvNotice(
                RvNoticeKind.Error,
                stringResource(R.string.rv_failed_test_latency),
                stringResource(R.string.error_local_network),
                stringResource(R.string.rv_retry),
                {},
            ),
        )
    }

    private companion object {
        /** The whole page (1309.5 dp): the page with no header chip, as `-node-detail-probes0-*` has it, the banner and its card gap. */
        const val WHOLE = "+h1310dp"

        /** A private address (RFC 1918), which the engine classes as the local network. */
        const val LAN_HOST = "192.168.1.20"
    }
}
