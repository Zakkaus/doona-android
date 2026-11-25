package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBanner
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The banner's height does not depend on the glyphs of its text: a title and a reason of one line each stand as tall in
 * Latin, CJK, mixed and symbol text, because [RvBanner] sets both in their role's Latin line box. Without the line box a
 * glyph from a fallback font makes its line taller and moves everything under the banner (per-app save-failed, 4 dp).
 * [spread] is the check; the red case plants the banner without the line box and the check must fail on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvBannerBoxTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aBannersHeightDoesNotDependOnItsGlyphs() {
        val heights = heights { title, reason -> RvBanner(notice(title, reason)) }
        assertTrue("the banner's height changes with its glyphs: $heights", spread(heights) <= TOLERANCE)
    }

    @Test
    fun theCheckFailsOnABannerWithoutTheLineBox() {
        val heights = heights { title, reason -> BannerWithoutLineBox(title, reason) }
        assertTrue("the check does not see the planted banner: $heights", spread(heights) > TOLERANCE)
    }

    /** The largest difference between the banner heights, in dp. */
    private fun spread(heights: List<Float>) = heights.max() - heights.min()

    /** The banner's card height in dp for each of [SAMPLES], one line each, shown by [banner]. */
    private fun heights(banner: @Composable (String, String) -> Unit): List<Float> {
        var shown by mutableStateOf(SAMPLES.first())
        compose.rvContent(dark = false) { banner(shown.first, shown.second) }
        return SAMPLES.map { sample ->
            shown = sample
            compose.waitForIdle()
            val card = compose.onAllNodesWithTag(RvPart.CARD, useUnmergedTree = true).fetchSemanticsNodes().single()
            card.size.height / card.layoutInfo.density.density
        }
    }

    private fun notice(title: String, reason: String) = RvNotice(RvNoticeKind.Error, title, reason, "OK", {})

    /** The banner as it was before the line box: the same card, padding and text styles, the texts in their fonts' own line boxes. */
    @Composable
    private fun BannerWithoutLineBox(title: String, reason: String) {
        RvCard {
            Column(Modifier.fillMaxWidth().padding(RvSpace.rowPadding)) {
                RvText(title, Rv.type.rowTitle, maxLines = 2)
                RvText(reason, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = 3)
            }
        }
    }

    private companion object {
        const val TOLERANCE = 0.5f

        /** Title and reason, one line each: Latin, Traditional Chinese, mixed, Japanese, Arabic, symbols. */
        val SAMPLES = listOf(
            "Save failed" to "Could not save the change",
            "儲存失敗" to "無法儲存這項變更",
            "Save 失敗" to "Could not 儲存 the change",
            "保存に失敗しました" to "変更を保存できません",
            "فشل الحفظ" to "تعذر حفظ التغيير",
            "⚠ Save ✗" to "→ 🙂 ✓",
        )
    }
}
