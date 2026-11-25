package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
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
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.settings.RvSettingsState
import dev.doona.android.prototype.rv.settings.rvSampleSettings

/**
 * 檢視生效設定 (rule-mode R3's `effectiveConfig`, read-only), from 全部規則 and from 設定 → 進階, in node detail's grammar:
 * the large title, then one card a section, each under its section title: 模式 with the main node; 策略組, each group
 * with how it picks, its first members and the node it uses; 規則, counted as D34 counts them, with the way to the list;
 * DNS; 依 App; and 來源, the template and the subscriptions the nodes come from. It ends with 複製 and 匯出 (the text
 * carries no credentials, D21). When the core cut the text at 1 MiB ([truncated]) a line under the title says so; a
 * [banner] (the config could not be read) stands in place of the cards, in [RvGutter]'s slot. The groups, rules and
 * sources are [config]'s.
 */
@Composable
internal fun RvEffectiveConfigPage(
    policy: RvPolicyState,
    settings: RvSettingsState,
    onBack: () -> Unit,
    onRules: () -> Unit = {},
    onCopy: () -> Unit = {},
    onExport: () -> Unit = {},
    truncated: Boolean = false,
    banner: RvNotice? = null,
    config: RvEffectiveConfig,
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
            if (banner == null) ModeCard(policy, config.main)
        }
        if (banner == null) {
            Section(R.string.rv_policy_groups) { GroupsCard(policy, listOf(config.main) + config.groups) }
            Section(R.string.rv_policy_rules) { RulesCard(config.rules, onRules) }
            Section(R.string.rv_settings_dns) {
                RvCard {
                    RvStatRow(stringResource(R.string.rv_settings_dns_server), stringResource(settings.dns.label) + SEPARATOR + settings.dns.address, valueKeepWords = false)
                    RvStatRow(stringResource(R.string.rv_effective_dns_via), stringResource(R.string.rv_main_node))
                    RvStatRow(stringResource(R.string.rv_policy_lan), stringResource(R.string.rv_effective_lan_value))
                }
            }
            Section(R.string.rv_effective_per_app) {
                RvCard {
                    RvStatRow(stringResource(R.string.rv_effective_apps_in), pluralStringResource(R.plurals.rv_effective_apps, settings.proxiedApps, settings.proxiedApps))
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

@Composable
private fun ModeCard(policy: RvPolicyState, main: RvPolicyGroup) {
    val mode = when (policy.mode) {
        RvRouteMode.Rule -> R.string.rv_mode_rule
        RvRouteMode.Global -> R.string.mode_global
        RvRouteMode.Direct -> R.string.mode_direct
    }
    RvCard {
        RvStatRow(stringResource(R.string.rv_effective_mode), stringResource(mode))
        RvStatRow(stringResource(R.string.rv_main_node), policy.group(main).selected.name, valueKeepWords = false)
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
                stringResource(group.name),
                members,
                titleEnd = { RvText(group.selected.name, Rv.type.rowValue, align = TextAlign.End) },
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
        RvSeeAll(pluralStringResource(R.plurals.rv_policy_see_all_rules, rules.size, rules.size), onRules)
    }
}

@Composable
private fun rulesCount(n: Int) = pluralStringResource(R.plurals.rv_rules_count, n, n)

/** Where the routing comes from: the template, and each subscription with its nodes and when it was refreshed. */
@Composable
private fun SourceCard(config: RvEffectiveConfig) {
    val format = rememberRvFormat()
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_template), stringResource(config.template))
        config.subscriptions.forEach {
            val nodes = pluralStringResource(R.plurals.rv_policy_node_count, it.nodes, it.nodes)
            RvStatRow(it.name, nodes + SEPARATOR + stringResource(R.string.rv_updated, format.time(it.updatedAtEpochMs)))
        }
    }
}

/**
 * What the effective configuration holds beyond the page's own state: the main node's group, every other group, all the
 * rules in the profile's order, the template's name and the subscriptions the nodes come from.
 */
@Immutable
internal class RvEffectiveConfig(
    val main: RvPolicyGroup,
    val groups: List<RvPolicyGroup>,
    val rules: List<RvPolicyRule>,
    @StringRes val template: Int,
    val subscriptions: List<RvSubscription>,
)

private const val MEMBERS_SHOWN = 2

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvEffectiveConfigPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvEffectiveConfigPageSample(RvPolicyState(), rvSampleSettings(), onBack = {}) }
}
