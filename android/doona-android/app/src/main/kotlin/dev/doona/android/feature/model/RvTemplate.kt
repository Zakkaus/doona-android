package dev.doona.android.feature.model

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

/** doona's templates: the core builds 繞過中國大陸 (`cn-direct`) alone, so it is the one listed. */
internal val RV_CN_DIRECT = RvTemplate("cn-direct", R.string.rv_policy_template_value, R.string.rv_template_cn_direct_line, R.drawable.rv_sym_arrow_right_alt)

/** The templates 範本 lists, as the core builds them. */
internal val RV_TEMPLATES: List<RvTemplate> = listOf(RV_CN_DIRECT)

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
