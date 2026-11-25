package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.nodes.ui.RvApplyTo
import dev.doona.android.feature.policies.RvApplyChoice
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The sample of nbtap, whole pages: `proto-rv-samples-nbtap-<approved|sample>-<light|dark|fs200>`. The approved page is the same composable without the 套用到 card, so the red diff holds the card alone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNbtapSampleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val group = RvPolicyData.streaming
    private val apart = RvApplyChoice(group.selectedId, group.byLatency.first { it.place == RvPlace.HK }.id, apart = true)

    @Test
    @Config(qualifiers = "+h1354dp")
    fun tapApprovedLight() = compose.rvShot("samples-nbtap-approved", dark = false, before = { assertWhole() }) { Tap(false) }

    @Test
    @Config(qualifiers = "+h1478dp")
    fun tapSampleLight() = compose.rvShot("samples-nbtap-sample", dark = false, before = { assertWhole() }) { Tap(true) }

    @Test
    @Config(qualifiers = "+h1354dp")
    fun tapApprovedDark() = compose.rvShot("samples-nbtap-approved", dark = true, before = { assertWhole() }) { Tap(false) }

    @Test
    @Config(qualifiers = "+h1478dp")
    fun tapSampleDark() = compose.rvShot("samples-nbtap-sample", dark = true, before = { assertWhole() }) { Tap(true) }

    @Test
    @Config(qualifiers = "+h2252dp")
    fun tapApprovedFs200() = compose.rvShot("samples-nbtap-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Tap(false) }

    @Test
    @Config(qualifiers = "+h2377dp")
    fun tapSampleFs200() = compose.rvShot("samples-nbtap-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Tap(true) }

    @androidx.compose.runtime.Composable
    private fun Tap(control: Boolean) =
        RvGroupSamplePage(group, apart, if (control) RvApplyTo.Both else null, rows = ROWS)

    private companion object {
        const val ROWS = 8
    }
}
