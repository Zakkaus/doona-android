package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.nodes.RV_NODES_DETAIL
import dev.doona.android.prototype.rv.subs.RvSubAdd
import dev.doona.android.prototype.rv.subs.RvSubAddSample
import dev.doona.android.prototype.rv.subs.RvSubDetail
import dev.doona.android.prototype.rv.subs.RvSubDetailSample
import dev.doona.android.prototype.rv.subs.RvSubDetailState
import dev.doona.android.prototype.rv.subs.rvAddSample
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What a real engine gives that the sample's dataset never did, on the pages that read it; each light, dark and fs200:
 * 新增訂閱 while the download has no known end (`proto-rv-sub-adding-unknown-*`: the busy bar in the row's trailing slot,
 * as 訂閱's row has it while it updates, and a dash for the byte count); a node with no probe record
 * (`-node-detail-probes0-*`: a dash for the figure and 上次測試, the plot's grid and labels with 尚無資料 at its centre)
 * and with one (`-node-detail-probes1-*`: its figure and time, no median, the chart's frame with the one dot where the
 * latest of many sits, its value in the axis pill, its marker and time), these two also at fs115;
 * a subscription whose provider sent no quota (`-sub-detail-noquota-*`) and
 * one in the SIP008 format (`-sub-sip008-*`). Light and dark hold the whole page, the larger sizes its first 915 dp. The
 * busy bar runs at animator scale 0, so it rests half full (recorded twice, byte-identical).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvDataRealismShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun addingUnknownLight() = shot("sub-adding-unknown", dark = false, busy = true) { Adding() }

    @Test fun addingUnknownDark() = shot("sub-adding-unknown", dark = true, busy = true) { Adding() }

    @Test fun addingUnknownFs200() = shot("sub-adding-unknown", fontScale = RV_FS200, busy = true) { Adding() }

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

    @Test
    @Config(qualifiers = WHOLE_SUB)
    fun noQuotaLight() = shot("sub-detail-noquota", dark = false) { Detail(NO_QUOTA) }

    @Test
    @Config(qualifiers = WHOLE_SUB)
    fun noQuotaDark() = shot("sub-detail-noquota", dark = true) { Detail(NO_QUOTA) }

    @Test fun noQuotaFs200() = shot("sub-detail-noquota", fontScale = RV_FS200) { Detail(NO_QUOTA) }

    @Test
    @Config(qualifiers = WHOLE_SUB)
    fun sip008Light() = shot("sub-sip008", dark = false) { Detail(SIP008) }

    @Test
    @Config(qualifiers = WHOLE_SUB)
    fun sip008Dark() = shot("sub-sip008", dark = true) { Detail(SIP008) }

    @Test fun sip008Fs200() = shot("sub-sip008", fontScale = RV_FS200) { Detail(SIP008) }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, busy: Boolean = false, content: @Composable () -> Unit) {
        if (busy) rvAnimatorScale(0f)
        // The larger text outgrows the window, so only the default size is held to fit it.
        compose.rvShot(name, dark, before = { if (fontScale == 1f) assertWhole() }, fontScale = fontScale, content = content)
    }

    @Composable
    private fun Adding() = RvSubAddSample(rvAddSample("importing-unknown"), {}, onPaste = {}, onImport = {}, onBack = {})

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

    @Composable
    private fun Detail(sub: RvSubscription) = RvSubDetailSample(sub, RvSubDetailState(), {}, onBack = {}, onUpdate = {})

    private companion object {
        /** The detail pages are taller than the phone: the windows of `proto-rv-sub-detail-*` and `-node-detail-*`. */
        const val WHOLE_SUB = "+h1087dp"
        const val WHOLE_NODE = "+h1220dp"

        val HARBOR = RvData.subscriptions.first()

        /** Harbor Cloud as a provider that sends no quota has it; its end date stays, as one `expire` alone can arrive. */
        val NO_QUOTA = HARBOR.copy(usedBytes = 0, totalBytes = 0)

        /** Harbor Cloud as a SIP008 server list. */
        val SIP008 = HARBOR.copy(format = RvSubFormat.Sip008)
    }
}
