package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.doona.android.designsystem.rv.RV_NAV_POLICY
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.feature.nodes.RvNodePicker
import dev.doona.android.feature.nodes.RvNodePickerPage
import dev.doona.android.feature.policies.RvPolicyRoutes
import dev.doona.android.feature.policies.RvRuleDraft
import dev.doona.android.feature.policies.RvRuleEditPage
import dev.doona.android.feature.policies.RvRulesPage
import dev.doona.android.feature.policies.RvRulesRoutes
import dev.doona.android.feature.policies.RvTemplateImpactPage
import dev.doona.android.prototype.rv.NODE
import dev.doona.android.prototype.rv.SEARCH
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodePickerPageSample
import dev.doona.android.prototype.rv.nodes.RvNodePickerSample

/** The preview's 策略 state across its pages: the tab's own state, the quick rule, the template picked, held rules. */
@Stable
internal class RvPolicyPreviewState(pending: List<RvPolicyRule> = emptyList()) {
    var policy by mutableStateOf(RvPolicyState())
    var quickRule by mutableStateOf(RvQuickRule())
    var template by mutableStateOf(RvTemplateData.cnDirect)
    var editing by mutableStateOf(false)
    var pending by mutableStateOf(pending)
    var rules by mutableStateOf(RvPolicyData.allRules)
    var draft: RvRuleDraft? by mutableStateOf(null)

    // The services ticked on the 新增服務規則 sample.
    var services by mutableStateOf(RV_SERVICE_SAMPLE)

    // The main node's picker keeps its own order, apart from 全域's (in the policy state), as each screen remembers its last.
    var pickerSort by mutableStateOf(RvNodeSort.Latency)

    /** Turns rule [i] off, or on again; a skipped rule stays skipped. */
    fun toggle(i: Int) {
        val r = rules[i]
        val state = when (r.state) {
            RvRuleState.On -> RvRuleState.Off
            RvRuleState.Off -> RvRuleState.On
            RvRuleState.Skipped -> RvRuleState.Skipped
        }
        rules = rules.toMutableList().also { it[i] = RvPolicyRule(r.match, r.group, r.bytes, state) }
    }

    fun delete(i: Int) {
        rules = rules.filterIndexed { j, _ -> j != i }
    }

    fun tick(service: String) {
        services = if (service in services) services - service else services + service
    }
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
    const val EDIT = "edit"
    const val PICKER = "picker"

    /** The search page over the main node's picker: a picked node becomes the main group's choice. */
    const val PICKER_SEARCH = "picker-search"

    /** The 新增服務規則 sample (owner-fix2 item 14), reached by its route only. */
    const val SERVICES = "services"
    val all = setOf(POLICY, GROUP, RULE, RULES, TEMPLATE, IMPACT, EFFECTIVE, EDIT, PICKER, PICKER_SEARCH, SERVICES)
}

/**
 * The 策略 tab's pages in the preview: the tab pushes a group's page, the main node's picker, all rules, the template
 * picker and the quick rule;
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

        r.PICKER -> {
            val main = state.policy.group(RvPolicyData.main)
            RvNodePickerPageSample(
                RvData.nodes,
                RvData.subscriptions,
                main.selectedId,
                state.pickerSort,
                onSort = { state.pickerSort = it },
                onPick = { state.policy = state.policy.choose(main.id, it) },
                onBack = pop,
                onTest = {},
                onSearch = { push(r.PICKER_SEARCH) },
            )
        }

        r.PICKER_SEARCH -> {
            val main = state.policy.group(RvPolicyData.main)
            RvNodePickerSample(
                RvData.nodes,
                RvData.subscriptions,
                main.selectedId,
                onPick = {
                    state.policy = state.policy.choose(main.id, it)
                    pop()
                },
                onDetail = { push("$NODE:$it") },
                onClose = pop,
                searchPage = true,
            )
        }

        r.RULE -> {
            // `rule:<i>` starts from the i-th open connection (連線 → 新增規則); `rule` from the sample.
            val from = route.substringAfter(':', "").toIntOrNull()?.let(RvPolicyData.connections::get) ?: RvPolicyData.connection
            RvPolicyRulePage(from, state.quickRule, { state.quickRule = it }, onBack = pop, onApply = pop)
        }

        r.RULES -> {
            val routes = RvRulesRoutes(
                // The last rule routes the rest to the main node, so it opens the main node's group.
                open = { i -> { if (i == state.rules.lastIndex) push("${r.GROUP}:${RvPolicyData.main.id}") else push("${r.EDIT}:$i") } },
                toggle = state::toggle,
                delete = state::delete,
                edit = { state.editing = it },
                newRule = { push(r.RULE) },
                apply = { state.pending = emptyList() },
                discard = { state.pending = emptyList() },
                effective = { push(r.EFFECTIVE) },
            )
            RvRulesPage(state.rules, state.pending, state.editing, onBack = pop, routes = routes)
        }

        r.EDIT -> {
            val i = route.substringAfter(':').toInt()
            val draft = state.draft ?: RvRuleDraft.of(state.rules[i])
            val leave = {
                state.draft = null
                pop()
            }
            RvRuleEditPage(draft, { state.draft = it }, onBack = leave, onApply = leave, onDelete = {
                state.delete(i)
                leave()
            }, targets = rvSampleTargets)
        }

        r.TEMPLATE -> RvTemplatePageSample(RvTemplateData.cnDirect, state.template, { state.template = it }, onBack = pop, onReview = { push(r.IMPACT) })

        r.EFFECTIVE -> RvEffectiveConfigSample(state.policy, settings, onBack = pop, onRules = { push(r.RULES) })

        r.IMPACT -> RvTemplateImpactPage(RvTemplateData.impact, onBack = pop, onApply = { onTab(RV_NAV_POLICY) })

        r.SERVICES -> RvServiceRulesPage(onBack = pop, selected = state.services, onToggle = state::tick)

        else -> {
            val routes = RvPolicyRoutes(
                group = { push("${r.GROUP}:$it") },
                mainNode = { push(r.PICKER) },
                rules = { push(r.RULES) },
                template = { push(r.TEMPLATE) },
                newRule = { push(r.RULE) },
                tab = onTab,
                search = { push(SEARCH) },
            )
            RvPolicyScreenSample(state.policy, { state.policy = it }, routes)
        }
    }
}
