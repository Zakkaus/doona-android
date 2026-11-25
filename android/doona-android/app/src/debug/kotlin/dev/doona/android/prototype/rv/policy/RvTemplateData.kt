package dev.doona.android.prototype.rv.policy

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.feature.model.RvChange
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvImpact
import dev.doona.android.feature.model.RvImpactGroup
import dev.doona.android.feature.model.RvImpactRule
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvTemplate

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
