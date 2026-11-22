package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.doona.android.prototype.rv.RV_NAV_POLICY
import dev.doona.android.prototype.rv.settings.RvSettingsState

/** The preview's 策略 state across its pages: the tab's own state, the quick rule, the template picked, held rules. */
@Stable
internal class RvPolicyPreviewState(pending: List<RvPolicyRule> = emptyList()) {
    var policy by mutableStateOf(RvPolicyState())
    var quickRule by mutableStateOf(RvQuickRule())
    var template by mutableStateOf(RvTemplateData.cnDirect)
    var editing by mutableStateOf(false)
    var pending by mutableStateOf(pending)
}

/** The preview routes the 策略 tab owns; a group's route carries its id after a colon. */
internal object RvPolicyPreviewRoutes {
    const val POLICY = "policy"
    const val GROUP = "group"
    const val RULE = "rule"
    const val RULES = "rules"
    const val TEMPLATE = "template"
    const val IMPACT = "impact"
    const val EFFECTIVE = "effective"
    val all = setOf(POLICY, GROUP, RULE, RULES, TEMPLATE, IMPACT, EFFECTIVE)
}

/**
 * The 策略 tab's pages in the preview: the tab pushes a group's page, all rules, the template picker and the quick rule;
 * the picker pushes the impact review, whose 套用 returns to the tab; all rules opens the quick rule for a new rule and
 * the effective config, which 設定 → 進階 also opens.
 */
@Composable
internal fun RvPolicyPreview(
    route: String,
    state: RvPolicyPreviewState,
    settings: RvSettingsState,
    push: (String) -> Unit,
    pop: () -> Unit,
    onTab: (Int) -> Unit,
) {
    val r = RvPolicyPreviewRoutes
    when (route.substringBefore(':')) {
        r.GROUP -> {
            val group = state.policy.group(RvPolicyData.group(route.substringAfter(':')))
            RvPolicyGroupPage(group, onBack = pop, onSelect = { state.policy = state.policy.choose(group.id, it) }, onTest = {})
        }

        r.RULE -> {
            // `rule:<i>` starts from the i-th open connection (連線 → 新增規則); `rule` from the sample.
            val from = route.substringAfter(':', "").toIntOrNull()?.let(RvPolicyData.connections::get) ?: RvPolicyData.connection
            RvPolicyRulePage(from, state.quickRule, { state.quickRule = it }, onBack = pop, onApply = pop)
        }

        r.RULES -> {
            val routes = RvRulesRoutes(edit = { state.editing = it }, newRule = { push(r.RULE) }, apply = { state.pending = emptyList() }, discard = { state.pending = emptyList() }, effective = { push(r.EFFECTIVE) })
            RvRulesPage(RvPolicyData.allRules, state.pending, state.editing, onBack = pop, routes = routes)
        }

        r.TEMPLATE -> RvTemplatePage(RvTemplateData.cnDirect, state.template, { state.template = it }, onBack = pop, onReview = { push(r.IMPACT) })

        r.EFFECTIVE -> RvEffectiveConfigPage(state.policy, settings, onBack = pop, onRules = { push(r.RULES) })

        r.IMPACT -> RvTemplateImpactPage(RvTemplateData.impact, onBack = pop, onApply = { onTab(RV_NAV_POLICY) })

        else -> {
            val routes = RvPolicyRoutes(
                group = { push("${r.GROUP}:$it") },
                rules = { push(r.RULES) },
                template = { push(r.TEMPLATE) },
                newRule = { push(r.RULE) },
                tab = onTab,
            )
            RvPolicyScreen(state.policy, { state.policy = it }, routes)
        }
    }
}
