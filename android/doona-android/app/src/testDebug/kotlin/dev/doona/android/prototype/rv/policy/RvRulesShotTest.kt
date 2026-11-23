package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The rule pages 策略 pushes, light and dark, each in one capture of the whole page: all rules (`proto-rv-rules-*`), the
 * same in edit mode (`proto-rv-rules-edit-*`) and with two rules held (`proto-rv-rules-pending-*`), the template picker
 * with 依服務分流 picked (`proto-rv-template-*`), its impact review (`proto-rv-template-impact-*`) and the effective
 * config (`proto-rv-effective-config-*`); a rule's page (`proto-rv-rule-edit-*`) and the same with an IP range it
 * cannot apply (`proto-rv-rule-edit-error-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRulesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_RULES)
    fun rulesLight() = compose.rvShot("rules", dark = false, before = { assertWhole() }) { Rules() }

    @Test
    @Config(qualifiers = WHOLE_RULES)
    fun rulesDark() = compose.rvShot("rules", dark = true, before = { assertWhole() }) { Rules() }

    @Test
    @Config(qualifiers = WHOLE_EDIT)
    fun editLight() = compose.rvShot("rules-edit", dark = false, before = { assertWhole() }) { Rules(editing = true) }

    @Test
    @Config(qualifiers = WHOLE_EDIT)
    fun editDark() = compose.rvShot("rules-edit", dark = true, before = { assertWhole() }) { Rules(editing = true) }

    @Test
    @Config(qualifiers = WHOLE_PENDING)
    fun pendingLight() = compose.rvShot("rules-pending", dark = false, before = { assertWhole() }) { Rules(RvPolicyData.pending) }

    @Test
    @Config(qualifiers = WHOLE_PENDING)
    fun pendingDark() = compose.rvShot("rules-pending", dark = true, before = { assertWhole() }) { Rules(RvPolicyData.pending) }

    @Test
    fun templateLight() = compose.rvShot("template", dark = false, before = { assertWhole() }) { Template() }

    @Test
    fun templateDark() = compose.rvShot("template", dark = true, before = { assertWhole() }) { Template() }

    @Test
    @Config(qualifiers = WHOLE_IMPACT)
    fun impactLight() = compose.rvShot("template-impact", dark = false, before = { assertWhole() }) { Impact() }

    @Test
    @Config(qualifiers = WHOLE_IMPACT)
    fun impactDark() = compose.rvShot("template-impact", dark = true, before = { assertWhole() }) { Impact() }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE)
    fun effectiveLight() = compose.rvShot("effective-config", dark = false, before = { assertWhole() }) { Effective() }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE)
    fun effectiveDark() = compose.rvShot("effective-config", dark = true, before = { assertWhole() }) { Effective() }

    @Test
    @Config(qualifiers = WHOLE_EDIT_RULE)
    fun ruleEditLight() = compose.rvShot("rule-edit", dark = false, before = { assertWhole() }) { Edit(RvRuleDraft.of(RvPolicyData.allRules[GITHUB])) }

    @Test
    @Config(qualifiers = WHOLE_EDIT_RULE)
    fun ruleEditDark() = compose.rvShot("rule-edit", dark = true, before = { assertWhole() }) { Edit(RvRuleDraft.of(RvPolicyData.allRules[GITHUB])) }

    @Test
    @Config(qualifiers = WHOLE_ERROR_RULE)
    fun ruleErrorLight() = compose.rvShot("rule-edit-error", dark = false, before = { assertWhole() }) { Edit(BAD_RANGE) }

    @Test
    @Config(qualifiers = WHOLE_ERROR_RULE)
    fun ruleErrorDark() = compose.rvShot("rule-edit-error", dark = true, before = { assertWhole() }) { Edit(BAD_RANGE) }

    @Composable
    private fun Edit(draft: RvRuleDraft) = RvRuleEditPage(draft, onChange = {}, onBack = {}, onApply = {}, onDelete = {})

    @Composable
    private fun Effective() = RvEffectiveConfigPage(RvPolicyState(), rvSampleSettings(), onBack = {})

    @Composable
    private fun Rules(pending: List<RvPolicyRule> = emptyList(), editing: Boolean = false) =
        RvRulesPage(RvPolicyData.allRules, pending, editing, onBack = {})

    @Composable
    private fun Template() = RvTemplatePage(RvTemplateData.cnDirect, RvTemplateData.services, onSelect = {}, onBack = {}, onReview = {})

    @Composable
    private fun Impact() = RvTemplateImpactPage(RvTemplateData.impact, onBack = {}, onApply = {})

    private companion object {
        /** Windows as tall as each whole page, so one capture holds it to its end. */
        const val WHOLE_RULES = "+h1679dp"
        const val WHOLE_EDIT = "+h1415dp"
        const val WHOLE_PENDING = "+h1969dp"
        const val WHOLE_IMPACT = "+h1447dp"
        const val WHOLE_EDIT_RULE = "+h1093dp"
        const val WHOLE_ERROR_RULE = "+h1121dp"

        /** github.com, the second of the user's rules. */
        const val GITHUB = 5

        /** Telegram's range with a prefix past /32: the field says how to write it, and 套用 is off. */
        val BAD_RANGE = RvRuleDraft(RvMatchKind.Cidr, "91.108.56.0/33", "main")
        const val WHOLE_EFFECTIVE = "+h2098dp"
    }
}
