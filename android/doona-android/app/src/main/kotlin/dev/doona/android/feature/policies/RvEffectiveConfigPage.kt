package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.text

/**
 * 檢視生效設定 (rule-mode R3's `effectiveConfig`, read-only), from 全部規則 and from 設定 → 進階, in node detail's grammar:
 * the large title, then one card a section, each under its section title: 模式 with the main node; 策略組, each group
 * with how it picks, its first members and the node it uses; 規則, counted as D34 counts them, with the way to the list;
 * DNS; 依 App; and 來源, the template and the subscriptions the nodes come from. Under the rules card a row opens 路由測試
 * ([onTrace]). It ends with 複製 and 匯出 (the text carries no credentials, D21). When the core cut the text at 1 MiB
 * ([truncated]) a line under the title says so; a
 * [banner] (the config could not be read) stands in [RvGutter]'s slot: in place of the cards before the first read,
 * above them after it. What the cards say is [config]'s, which is null until the first read: the title stands alone.
 */
@Composable
internal fun RvEffectiveConfigPage(
    policy: RvPolicyState,
    onBack: () -> Unit,
    onRules: () -> Unit = {},
    onCopy: () -> Unit = {},
    onExport: () -> Unit = {},
    truncated: Boolean = false,
    banner: RvNotice? = null,
    config: RvEffectiveConfig?,
    onTrace: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_effective_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        if (truncated) {
            RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_effective_truncated), R.drawable.rv_nodes_ic_warning) })
        } else {
            RvLargeTitle(title, titleBlock, insets)
        }
        RvGutter(banner = banner) {
            config?.let { ModeCard(policy, it.main) }
        }
        if (config != null) {
            Section(R.string.rv_policy_groups) { GroupsCard(policy, listOfNotNull(config.main) + config.groups) }
            Section(R.string.rv_policy_rules) {
                RulesCard(config.rules, onRules)
                // q-trace-entry = 1 (review page v85): one row under the rules card.
                RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_call_split) }, stringResource(R.string.rv_trace_title), null, onClick = onTrace) }
            }
            Section(R.string.rv_settings_dns) {
                RvCard {
                    RvStatRow(stringResource(R.string.rv_settings_dns_server), dnsLine(config.dns), valueKeepWords = false)
                    RvStatRow(stringResource(R.string.rv_effective_dns_via), config.dnsVia.text())
                    RvStatRow(stringResource(R.string.rv_policy_lan), stringResource(R.string.rv_effective_lan_value))
                }
            }
            Section(R.string.rv_effective_per_app) {
                RvCard {
                    RvStatRow(stringResource(R.string.rv_effective_apps_in), pluralStringResource(R.plurals.rv_effective_apps, config.proxiedApps, config.proxiedApps))
                    RvStatRow(stringResource(R.string.rv_effective_apps_rest), stringResource(R.string.rv_effective_apps_rest_value))
                }
            }
            Section(R.string.rv_effective_source) {
                SourceCard(config)
                RvCard {
                    RvRow({ RvIconDisc(R.drawable.rv_sym_content_copy) }, stringResource(R.string.rv_effective_copy), null, onClick = onCopy)
                    RvRow({ RvIconDisc(R.drawable.rv_sym_ios_share) }, stringResource(R.string.rv_effective_export), null, onClick = onExport)
                }
            }
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    RvSectionTitle(stringResource(title), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter { content() }
}

/**
 * The DNS servers, each by its name where it is one 設定 offers (Cloudflare · 1.1.1.1), else by its address alone. The
 * core writes plain DNS's port, 53, which the row leaves out.
 */
@Composable
private fun dnsLine(servers: List<String>): String = servers.map { server ->
    val address = server.removeSuffix(DNS_PORT)
    RvDnsUpstream.entries.firstOrNull { it.address == address }?.let { stringResource(it.label) + SEPARATOR + address } ?: address
}.joinToString(stringResource(R.string.rv_effective_list_separator))

private const val DNS_PORT = ":53"

/** The mode, and the node the main group uses when the configuration has a main group. */
@Composable
private fun ModeCard(policy: RvPolicyState, main: RvPolicyGroup?) {
    val mode = when (policy.mode) {
        RvRouteMode.Rule -> R.string.rv_mode_rule
        RvRouteMode.Global -> R.string.mode_global
        RvRouteMode.Direct -> R.string.mode_direct
    }
    RvCard {
        RvStatRow(stringResource(R.string.rv_effective_mode), stringResource(mode))
        main?.let { RvStatRow(stringResource(R.string.rv_main_node), policy.group(it).selected?.name.orEmpty(), valueKeepWords = false) }
    }
}

/** Each group: its kind's glyph (how it picks), its name over its first members and their count, and the node it uses. */
@Composable
private fun GroupsCard(policy: RvPolicyState, groups: List<RvPolicyGroup>) {
    RvCard {
        groups.forEach {
            val group = policy.group(it)
            val shown = group.members.take(MEMBERS_SHOWN).joinToString(stringResource(R.string.rv_effective_list_separator)) { node -> node.short }
            val members = if (group.members.size > MEMBERS_SHOWN) stringResource(R.string.rv_effective_members_more, shown, group.members.size) else shown
            RvRow(
                { RvIconDisc(group.kind.glyph) },
                group.name.text(),
                members,
                titleEnd = group.selected?.let { node -> { RvText(node.name, Rv.type.rowValue, align = TextAlign.End) } },
                titleKeepWords = false,
                subtitleKeepWords = false,
            )
        }
    }
}

/** The rules as D34 counts them: in use, off, and skipped because the core cannot match them yet; then all of them. */
@Composable
private fun RulesCard(rules: List<RvPolicyRule>, onRules: () -> Unit) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_rules_in_use), rulesCount(rules.count { it.state == RvRuleState.On }))
        RvStatRow(stringResource(R.string.rv_rules_off), rulesCount(rules.count { it.state == RvRuleState.Off }))
        RvStatRow(stringResource(R.string.rv_rules_skipped), rulesCount(rules.count { it.state == RvRuleState.Skipped }))
        RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_rules, rules.size, rules.size), onClick = onRules)
    }
}

@Composable
private fun rulesCount(n: Int) = pluralStringResource(R.plurals.rv_rules_count, n, n)

/** Where the routing comes from: the template, and each subscription with its nodes and when it was refreshed. */
@Composable
private fun SourceCard(config: RvEffectiveConfig) {
    val format = rememberRvFormat()
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_template), config.source.text())
        config.subscriptions.forEach {
            val nodes = pluralStringResource(R.plurals.rv_policy_node_count, it.nodes, it.nodes)
            val updated = it.updatedAtEpochMs?.let { at -> stringResource(R.string.rv_updated, format.time(at)) } ?: RV_NO_VALUE
            RvStatRow(it.name, nodes + SEPARATOR + updated)
        }
    }
}

/**
 * What the effective configuration holds beyond the page's own state: the main node's group (null when the
 * configuration has none), every other group, all the rules in the profile's order, where the rules come from, the
 * subscriptions the nodes come from, the DNS servers' addresses and what their queries go through ([dnsVia]), and how many
 * apps 依 App lists. The lists are copied on construction, so a caller's mutable list cannot change the configuration
 * afterwards.
 */
@ConsistentCopyVisibility
@Immutable
internal data class RvEffectiveConfig private constructor(
    val main: RvPolicyGroup?,
    val groups: List<RvPolicyGroup>,
    val rules: List<RvPolicyRule>,
    val source: RvRuleSource,
    val subscriptions: List<RvSubscription>,
    val dns: List<String>,
    val dnsVia: RvLabel,
    val proxiedApps: Int,
) {
    companion object {
        operator fun invoke(
            main: RvPolicyGroup?,
            groups: List<RvPolicyGroup>,
            rules: List<RvPolicyRule>,
            source: RvRuleSource,
            subscriptions: List<RvSubscription>,
            dns: List<String>,
            dnsVia: RvLabel,
            proxiedApps: Int,
        ) = RvEffectiveConfig(main, groups.toList(), rules.toList(), source, subscriptions.toList(), dns.toList(), dnsVia, proxiedApps)
    }
}

private const val MEMBERS_SHOWN = 2
