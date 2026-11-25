package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The title-to-subtitle gap sheet at 0, 2 and 4 dp, three phone widths side by side:
 * `proto-rv-samples-gap-sheet-<light|fs200|en-light|en-fs200>`. No approved column: the library's gap is 0, the left column.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w1236dp-h384dp-notnight-xhdpi")
class RvTitleGapShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val gaps = listOf(0.dp, 2.dp, 4.dp)

    @Test
    fun light() = compose.rvShot("samples-gap-sheet", dark = false, before = { assertWhole() }) { RvTitleGapSheet(gaps) }

    @Test
    @Config(qualifiers = "zh-rTW-w1236dp-h605dp-notnight-xhdpi")
    fun fs200() = compose.rvShot("samples-gap-sheet", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvTitleGapSheet(gaps) }

    @Test
    @Config(qualifiers = "en-w1236dp-h411dp-notnight-xhdpi")
    fun enLight() = compose.rvShot("samples-gap-sheet-en", dark = false, before = { assertWhole() }) { RvTitleGapSheet(gaps) }

    @Test
    @Config(qualifiers = "en-w1236dp-h700dp-notnight-xhdpi")
    fun enFs200() = compose.rvShot("samples-gap-sheet-en", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvTitleGapSheet(gaps) }
}
