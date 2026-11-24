package dev.doona.android.prototype.rv.policy

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
 * 新增服務規則 (owner-fix2 item 14, the owner's B): doona web's five sections, each a title over its card
 * (`proto-rv-rules-service-*`), light, dark, fs115 and fs200, in a window that holds the whole page. The services come in
 * web's order under web's section titles, and the card above them says where the rules send with the rule pages' 送往.
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
    @Config(qualifiers = WHOLE)
    fun light() = shot(dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun dark() = shot(dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun fs115() = shot(dark = false, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun fs200() = shot(dark = false, fontScale = RV_FS200)

    @Test
    fun theServicesComeInWebsOrderUnderWebsFiveSections() {
        compose.rvContent(dark = false) { RvServiceRulesPage(onBack = {}) }
        assertEquals(RvServiceData.all.map { s -> s.label?.let(::rvText) ?: s.name }, services())
        val web = listOf(R.string.rv_service_games, R.string.rv_service_media, R.string.rv_service_social, R.string.rv_rule_ai, R.string.rv_service_developer)
        assertEquals(web.map(::rvText), sections())
    }

    @Test
    fun theRulesSendToTheMainNodeInTheRulePagesWords() {
        compose.rvContent(dark = false) { RvServiceRulesPage(onBack = {}) }
        compose.onNode(hasText(rvText(R.string.rv_policy_send_to)) and hasText(rvText(R.string.rv_main_node))).assertExists()
    }

    @Test
    fun aServiceWithARuleSaysWhereItSendsAndCannotBeTicked() {
        compose.rvContent(dark = false) { RvServiceRulesPage(onBack = {}) }
        val has = RuntimeEnvironment.getApplication().getString(R.string.rv_service_has_rule, rvText(R.string.rv_main_node))
        compose.onNode(isToggleable() and hasText("Google") and hasText(has)).assertIsNotEnabled()
    }

    /** The titles of the rows to tick, top to bottom. */
    private fun services(): List<String> = compose.onAllNodes(isToggleable()).fetchSemanticsNodes().map { it.config[SemanticsProperties.Text].first().text }

    /** The section titles, top to bottom. */
    private fun sections(): List<String> = compose.onAllNodes(hasAnyAncestor(hasTestTag(RvPart.SECTION)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString() }

    private fun shot(dark: Boolean, fontScale: Float = 1f) {
        compose.rvShot("rules-service", dark, before = { assertWhole() }, fontScale = fontScale) { RvServiceRulesPage(onBack = {}) }
    }

    private companion object {
        // Each window is as tall as its page, so the capture holds every row.
        const val WHOLE = "+h2644dp"
        const val WHOLE_FS115 = "+h2655dp"
        const val WHOLE_FS200 = "+h3209dp"
    }
}
