package dev.doona.android.prototype.rv.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 統計 page with the 綜合評分 card (sample, not approved), whole, light, dark, fs115 and fs200
 * (`proto-rv-stats-score-*`): the Score groups' connections, their trials by outcome, and each network's choice.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvStatsScoreShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WINDOW)
    fun scoreLight() = shot(dark = false)

    @Test
    @Config(qualifiers = WINDOW)
    fun scoreDark() = shot(dark = true)

    @Test
    @Config(qualifiers = WINDOW_FS115)
    fun scoreFs115() = shot(fontScale = RV_FS115)

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun scoreFs200() = shot(fontScale = RV_FS200)

    /** The card counts the connections and the trials by outcome, and says each network's choice and state. */
    @Test
    @Config(qualifiers = WINDOW)
    fun cardSaysCountsAndChoices() {
        compose.rvContent(dark = false) { Page() }
        assertEquals(1, count("綜合評分"))
        assertEquals(1, count("152"))
        // The paged tile's label and the card's.
        assertEquals(2, count("連線次數"))
        assertEquals(1, count("驗證 14 次"))
        assertEquals(1, count("12 成功"))
        assertEquals(1, count("2 失敗"))
        assertEquals(1, count("TCP · 已選定"))
        assertEquals(1, count("UDP · 評估中 · 等待可比較的流量"))
    }

    private fun count(text: String) = compose.onAllNodes(shows(text), useUnmergedTree = true).fetchSemanticsNodes().size

    /** A text node showing [text], without the word joiners RvText binds words with. */
    private fun shows(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(WORD_JOINER, "") == text } == true
    }

    private fun shot(dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot(NAME, dark, before = { assertWhole() }, fontScale = fontScale) { Page() }

    @Composable
    private fun Page() = RvStatsScreen(rvStatsSample(), onBack = {}, score = rvStatsScore())

    private companion object {
        const val NAME = "stats-score"

        /** Windows as tall as the page whole, rounded up to a whole dp (measured: 2332.5, 2353.5, 2753). */
        const val WINDOW = "+h2333dp"
        const val WINDOW_FS115 = "+h2354dp"
        const val WINDOW_FS200 = "+h2753dp"

        const val WORD_JOINER = "⁠"
    }
}
