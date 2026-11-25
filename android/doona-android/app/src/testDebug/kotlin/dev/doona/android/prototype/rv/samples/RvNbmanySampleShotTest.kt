package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
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

/** The sample of nbmany, whole pages: `proto-rv-samples-nbmany-<approved|sample>-<light|dark|fs200>`. The approved page lists eight nodes and 查看全部 opens the rest in place; the sample lists two (Revolut, measured §7) and 全部節點 opens the picker. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNbmanySampleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val group = RvPolicyData.streaming

    @Test
    @Config(qualifiers = "+h1124dp")
    fun manyApprovedLight() = compose.rvShot("samples-nbmany-approved", dark = false, before = { assertWhole() }) { Many(false) }

    @Test
    fun manySampleLight() = compose.rvShot("samples-nbmany-sample", dark = false, before = { assertWhole() }) { Many(true) }

    @Test
    @Config(qualifiers = "+h1124dp")
    fun manyApprovedDark() = compose.rvShot("samples-nbmany-approved", dark = true, before = { assertWhole() }) { Many(false) }

    @Test
    fun manySampleDark() = compose.rvShot("samples-nbmany-sample", dark = true, before = { assertWhole() }) { Many(true) }

    @Test
    @Config(qualifiers = "+h1730dp")
    fun manyApprovedFs200() = compose.rvShot("samples-nbmany-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Many(false) }

    @Test
    fun manySampleFs200() = compose.rvShot("samples-nbmany-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Many(true) }

    @androidx.compose.runtime.Composable
    private fun Many(few: Boolean) =
        if (few) RvGroupSamplePage(group, null, null, rows = ROWS_FEW, more = RvMoreNodes.Picker) else RvGroupSamplePage(group, null, null, rows = ROWS, more = RvMoreNodes.InPlace)

    private companion object {
        const val ROWS = 8
        const val ROWS_FEW = 2
    }
}
