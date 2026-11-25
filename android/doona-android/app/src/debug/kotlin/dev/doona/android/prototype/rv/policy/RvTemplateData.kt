package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.feature.model.RvChange
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvImpact
import dev.doona.android.feature.model.RvImpactGroup
import dev.doona.android.feature.model.RvImpactRule
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvTemplate
import dev.doona.android.feature.model.rvTemplate

/** The templates and the sample review: from the current 國內直連 to 依服務分流. */
internal object RvTemplateData {
    val cnDirect = rvTemplate("cn-direct")
    val services = rvTemplate("services")
    private val regions = rvTemplate("regions")
    private val homebound = rvTemplate("homebound")
    private val gfw = rvTemplate("gfw", needsGeosite = true)

    val all: List<RvTemplate> = listOf(cnDirect, services, regions, homebound, gfw)

    /** Moving from 國內直連 to 依服務分流: four service rules and two groups come in, AI widens, nothing goes. */
    val impact: RvImpact = run {
        val main = RvPolicyData.main
        val nodes = main.members.size
        RvImpact(
            from = RvRuleSource.Template(cnDirect),
            to = services,
            added = ADDED,
            changed = 1,
            removed = 0,
            kept = KEPT,
            groups = listOf(
                RvImpactGroup(RvLabel.Res(R.string.rv_template_group_youtube), RvGroupKind.Select, RvPolicyData.streaming.members.size, RvChange.Add),
                RvImpactGroup(RvLabel.Res(R.string.rv_template_group_telegram), RvGroupKind.Auto, nodes, RvChange.Add),
                RvImpactGroup(RvLabel.Res(R.string.rv_rule_streaming), RvGroupKind.Select, RvPolicyData.streaming.members.size, RvChange.Keep),
                RvImpactGroup(RvLabel.Res(R.string.rv_rule_ai), RvGroupKind.Select, RvPolicyData.ai.members.size, RvChange.Keep),
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
