package dev.doona.android.feature.model

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/**
 * A rule template, as doona web's routing modes offer them: its name, one line on what it does (none for a template the
 * app has no words for), its glyph, and, when the core cannot build it yet, why ([unsupported]); such a template is listed
 * but cannot be chosen.
 */
@Immutable
internal data class RvTemplate(
    val id: String,
    val name: RvLabel,
    @StringRes val line: Int?,
    @DrawableRes val glyph: Int,
    @StringRes val unsupported: Int? = null,
)

/**
 * The template the core names [id]: its words and glyph where the app has them, else the id itself with the generic
 * glyph. [needsGeosite] lists it as the core cannot build it, for want of GeoSite data.
 */
internal fun rvTemplate(id: String, needsGeosite: Boolean = false): RvTemplate {
    val unsupported = R.string.rv_template_needs_geosite.takeIf { needsGeosite }
    val known = TEMPLATE_TEXTS[id] ?: return RvTemplate(id, RvLabel.Text(id), null, R.drawable.rv_sym_rule, unsupported)
    return RvTemplate(id, RvLabel.Res(known.name), known.line, known.glyph, unsupported)
}

private class TemplateText(@StringRes val name: Int, @StringRes val line: Int, @DrawableRes val glyph: Int)

/** The words and glyph of each template doona web offers, by the id the core gives it. */
private val TEMPLATE_TEXTS = mapOf(
    "cn-direct" to TemplateText(R.string.rv_policy_template_value, R.string.rv_template_cn_direct_line, R.drawable.rv_sym_arrow_right_alt),
    "global" to TemplateText(R.string.rv_template_global, R.string.rv_template_global_line, R.drawable.rv_settings_ic_language),
    "single" to TemplateText(R.string.rv_template_single, R.string.rv_template_single_line, R.drawable.rv_sym_vpn_lock),
    "services" to TemplateText(R.string.rv_template_services, R.string.rv_template_services_line, R.drawable.rv_sym_apps),
    "regions" to TemplateText(R.string.rv_template_regions, R.string.rv_template_regions_line, R.drawable.rv_sym_account_tree),
    "homebound" to TemplateText(R.string.rv_template_homebound, R.string.rv_template_homebound_line, R.drawable.rv_sym_directions),
    "gfw" to TemplateText(R.string.rv_template_gfw, R.string.rv_template_gfw_line, R.drawable.rv_sym_block),
)

/**
 * Where 規則's rules come from: a [Template], a subscription by its [Subscription.name], or the configuration [File] the
 * user writes by hand.
 */
@Immutable
internal sealed interface RvRuleSource {
    data class Template(val template: RvTemplate) : RvRuleSource

    data class Subscription(val name: String) : RvRuleSource

    data object File : RvRuleSource
}

/** What applying a template does to a group: adds it, keeps it as it is, or removes it. */
internal enum class RvChange(@StringRes val label: Int) {
    Add(R.string.rv_policy_change_add),
    Keep(R.string.rv_policy_change_keep),
    Change(R.string.rv_policy_change_change),
    Remove(R.string.rv_policy_change_remove),
}

/** A group in the impact review: its name, how it picks, its node count, and what the new template does to it. */
@Immutable
internal class RvImpactGroup(val name: RvLabel, val kind: RvGroupKind, val nodes: Int, val change: RvChange)

/** A rule in the impact review: the rule as the new template writes it, what happens to it, and its old target. */
@Immutable
internal class RvImpactRule(val rule: RvPolicyRule, val change: RvChange, @StringRes val was: Int? = null)

/**
 * The review doona web shows before a template replaces the rules (RuleTemplates.tsx's impact dialog): the rule source
 * before and the template after, the rules it adds, changes and removes, the user's own rules it keeps in place, and its
 * groups. A count is null, and a list empty, when the core does not tell it.
 */
@Immutable
internal class RvImpact(
    val from: RvRuleSource,
    val to: RvTemplate,
    val added: Int?,
    val changed: Int?,
    val removed: Int?,
    val kept: Int?,
    val groups: List<RvImpactGroup>,
    val rules: List<RvImpactRule>,
)
