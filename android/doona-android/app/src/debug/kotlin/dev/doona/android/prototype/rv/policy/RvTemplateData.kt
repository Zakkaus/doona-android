package dev.doona.android.prototype.rv.policy

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/**
 * A rule template, as doona web's routing modes offer them: its name, one line on what it does, its glyph, and, when
 * the core cannot build it yet, why ([unsupported]); such a template is listed but cannot be chosen.
 */
@Immutable
internal class RvTemplate(
    val id: String,
    @StringRes val name: Int,
    @StringRes val line: Int,
    @DrawableRes val glyph: Int,
    @StringRes val unsupported: Int? = null,
)

/** What applying a template does to a group: adds it, keeps it as it is, or removes it. */
internal enum class RvChange(@StringRes val label: Int) {
    Add(R.string.rv_policy_change_add),
    Keep(R.string.rv_policy_change_keep),
    Change(R.string.rv_policy_change_change),
    Remove(R.string.rv_policy_change_remove),
}

/** A group in the impact review: its name, how it picks, its node count, and what the new template does to it. */
@Immutable
internal class RvImpactGroup(@StringRes val name: Int, val kind: RvGroupKind, val nodes: Int, val change: RvChange)

/** A rule in the impact review: the rule as the new template writes it, what happens to it, and its old target. */
@Immutable
internal class RvImpactRule(val rule: RvPolicyRule, val change: RvChange, @StringRes val was: Int? = null)

/**
 * The review doona web shows before a template replaces the rules (RuleTemplates.tsx's impact dialog): the template
 * before and after, the rules it adds, changes and removes, the user's own rules it keeps in place, and its groups.
 */
@Immutable
internal class RvImpact(
    val from: RvTemplate,
    val to: RvTemplate,
    val added: Int,
    val changed: Int,
    val removed: Int,
    val kept: Int,
    val groups: List<RvImpactGroup>,
    val rules: List<RvImpactRule>,
)

/** The templates and the sample review: from the current 國內直連 to 依服務分流. */
internal object RvTemplateData {
    val cnDirect = RvTemplate("cn-direct", R.string.rv_policy_template_value, R.string.rv_template_cn_direct_line, R.drawable.rv_sym_arrow_right_alt)
    val services = RvTemplate("services", R.string.rv_template_services, R.string.rv_template_services_line, R.drawable.rv_sym_apps)
    private val regions = RvTemplate("regions", R.string.rv_template_regions, R.string.rv_template_regions_line, R.drawable.rv_sym_account_tree)
    private val homebound = RvTemplate("homebound", R.string.rv_template_homebound, R.string.rv_template_homebound_line, R.drawable.rv_sym_directions)
    private val gfw = RvTemplate("gfw", R.string.rv_template_gfw, R.string.rv_template_gfw_line, R.drawable.rv_sym_block, R.string.rv_template_needs_geosite)

    val all: List<RvTemplate> = listOf(cnDirect, services, regions, homebound, gfw)

    /** Moving from 國內直連 to 依服務分流: four service rules and two groups come in, AI widens, nothing goes. */
    val impact: RvImpact = run {
        val main = RvPolicyData.main
        val nodes = main.members.size
        RvImpact(
            from = cnDirect,
            to = services,
            added = ADDED,
            changed = 1,
            removed = 0,
            kept = KEPT,
            groups = listOf(
                RvImpactGroup(R.string.rv_template_group_youtube, RvGroupKind.Select, RvPolicyData.streaming.members.size, RvChange.Add),
                RvImpactGroup(R.string.rv_template_group_telegram, RvGroupKind.Auto, nodes, RvChange.Add),
                RvImpactGroup(R.string.rv_rule_streaming, RvGroupKind.Select, RvPolicyData.streaming.members.size, RvChange.Keep),
                RvImpactGroup(R.string.rv_rule_ai, RvGroupKind.Select, RvPolicyData.ai.members.size, RvChange.Keep),
            ),
            rules = listOf(
                RvImpactRule(RvPolicyRule(RvRuleMatch.Domains("youtube.com", YOUTUBE_DOMAINS), RvPolicyData.streaming, 0), RvChange.Add),
                RvImpactRule(RvPolicyRule(RvRuleMatch.Domains("telegram.org", TELEGRAM_DOMAINS), main, 0), RvChange.Add),
                RvImpactRule(RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Cidr, "91.108.4.0/22"), main, 0), RvChange.Add),
                RvImpactRule(RvPolicyRule(RvRuleMatch.Value(RvMatchKind.Cidr, "149.154.160.0/20"), main, 0), RvChange.Add),
                RvImpactRule(RvPolicyRule(RvRuleMatch.Domains("openai.com", AI_DOMAINS), RvPolicyData.ai, 0), RvChange.Change, R.string.rv_template_ai_was),
            ),
        )
    }
}

private const val ADDED = 4
private const val KEPT = 9
private const val YOUTUBE_DOMAINS = 8
private const val TELEGRAM_DOMAINS = 5
private const val AI_DOMAINS = 9
