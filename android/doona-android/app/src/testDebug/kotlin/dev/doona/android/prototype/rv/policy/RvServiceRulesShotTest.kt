package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 新增服務規則 in the two forms of [RvServiceLayout] for the owner (owner-fix2 item 14): A, every service in one card
 * (`proto-rv-rules-service-a-*`), and B, doona web's five sections, each a title over its card (`-b-*`); each light,
 * dark, fs115 and fs200, in a window that holds the whole page. Both list the same services in the same order, and B's
 * section titles are web's, in web's order.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvServiceRulesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_A)
    fun aLight() = shot(RvServiceLayout.Flat, dark = false)

    @Test
    @Config(qualifiers = WHOLE_A)
    fun aDark() = shot(RvServiceLayout.Flat, dark = true)

    @Test
    @Config(qualifiers = WHOLE_A)
    fun aFs115() = shot(RvServiceLayout.Flat, dark = false, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = WHOLE_A_FS200)
    fun aFs200() = shot(RvServiceLayout.Flat, dark = false, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE_B)
    fun bLight() = shot(RvServiceLayout.Sections, dark = false)

    @Test
    @Config(qualifiers = WHOLE_B)
    fun bDark() = shot(RvServiceLayout.Sections, dark = true)

    @Test
    @Config(qualifiers = WHOLE_B_FS115)
    fun bFs115() = shot(RvServiceLayout.Sections, dark = false, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = WHOLE_B_FS200)
    fun bFs200() = shot(RvServiceLayout.Sections, dark = false, fontScale = RV_FS200)

    @Test
    fun bothListTheSameServicesInWebsOrder() {
        val expected = RvServiceData.all.map { s -> s.label?.let(::rvText) ?: s.name }
        val (a, b) = both(::services)
        assertEquals("A", expected, a)
        assertEquals("B", expected, b)
    }

    @Test
    fun bHasWebsFiveSectionsInItsOrder() {
        val web = listOf(R.string.rv_service_games, R.string.rv_service_media, R.string.rv_service_social, R.string.rv_rule_ai, R.string.rv_service_developer)
        val (a, b) = both(::sections)
        assertEquals("B", web.map(::rvText), b)
        assertEquals("A", emptyList<String>(), a)
    }

    @Test
    fun aServiceWithARuleSaysWhereItSendsAndCannotBeTicked() {
        compose.rvContent(dark = false) { RvServiceRulesPage(RvServiceLayout.Sections, onBack = {}) }
        val has = RuntimeEnvironment.getApplication().getString(R.string.rv_service_has_rule, rvText(R.string.rv_main_node))
        compose.onNode(isToggleable() and hasText("Google") and hasText(has)).assertIsNotEnabled()
    }

    /** What [read] finds on the page in A, then, in the same window, in B. */
    private fun both(read: () -> List<String>): Pair<List<String>, List<String>> {
        var layout by mutableStateOf(RvServiceLayout.Flat)
        compose.rvContent(dark = false) { RvServiceRulesPage(layout, onBack = {}) }
        val a = read()
        compose.runOnIdle { layout = RvServiceLayout.Sections }
        return a to read()
    }

    /** The titles of the rows to tick, top to bottom. */
    private fun services(): List<String> = compose.onAllNodes(isToggleable()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text }

    /** The section titles, top to bottom. */
    private fun sections(): List<String> = compose.onAllNodes(hasAnyAncestor(hasTestTag(RvPart.SECTION)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }

    private fun shot(layout: RvServiceLayout, dark: Boolean, fontScale: Float = 1f) {
        val name = if (layout == RvServiceLayout.Flat) "a" else "b"
        compose.rvShot("rules-service-$name", dark, before = { assertWhole() }, fontScale = fontScale) { RvServiceRulesPage(layout, onBack = {}) }
    }

    private companion object {
        // Each window is as tall as its page, so the capture holds every row; A at fs115 keeps 1.0's height.
        const val WHOLE_A = "+h2359dp"
        const val WHOLE_A_FS200 = "+h2840dp"
        const val WHOLE_B = "+h2644dp"
        const val WHOLE_B_FS115 = "+h2655dp"
        const val WHOLE_B_FS200 = "+h3209dp"
    }
}
