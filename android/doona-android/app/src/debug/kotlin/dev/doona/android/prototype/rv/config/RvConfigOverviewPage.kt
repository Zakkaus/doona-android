package dev.doona.android.prototype.rv.config

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RvCaption
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvPageHeader
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSectionTitle
import dev.doona.android.prototype.rv.RvSeeAll
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvStatRow
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rvSystemScheme
import dev.doona.android.prototype.rv.settings.RvSettingsNavRow

/**
 * 設定檔 (config plan, configuration overview; `configRead`), under 策略 and linked from 設定, in node detail's grammar:
 * the large title over the applied revision; 檔案, the entry and each include as a plain navigation row naming the file
 * that includes it, its lines and what was skipped in it; 內容, the summary's counts, ending in 檢視生效設定 (the running
 * projection); 相容性, the skipped and partly applied records with the way into their list; 版本, history and import.
 */
@Composable
internal fun RvConfigOverviewPage(
    state: RvConfigState,
    onBack: () -> Unit,
    onFile: (String) -> Unit = {},
    onSkipped: () -> Unit = {},
    onEffective: () -> Unit = {},
    onHistory: () -> Unit = {},
    onImport: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_config_title)
    val applied = rvConfigWhen(state.appliedAtEpochMs)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_config_applied, state.rev, applied)) })
        RvSectionTitle(stringResource(R.string.rv_config_files), Modifier.padding(horizontal = RvSpace.gutter), first = true)
        RvGutter { FilesCard(state, onFile) }
        RvConfigSection(R.string.rv_config_contents) { ContentsCard(state.summary, onEffective) }
        RvConfigSection(R.string.rv_config_compat) { CompatCard(state, onSkipped) }
        RvConfigSection(R.string.rv_settings_version) {
            RvCard {
                RvSettingsNavRow(stringResource(R.string.rv_config_history), R.drawable.rv_sym_schedule, onHistory)
                RvSettingsNavRow(stringResource(R.string.rv_config_import), R.drawable.rv_sym_add, onImport)
            }
        }
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** A section of the configuration pages: the outside title over its cards on the gutter. */
@Composable
internal fun RvConfigSection(title: Int, content: @Composable () -> Unit) {
    RvSectionTitle(stringResource(title), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter { content() }
}

/** Each file of the tree: the entry first, then each include under the file that names it, as they load. */
@Composable
private fun FilesCard(state: RvConfigState, onFile: (String) -> Unit) {
    RvCard {
        state.files.forEach { file ->
            val origin = file.parent?.let { stringResource(R.string.rv_config_included_by, it) } ?: stringResource(R.string.rv_config_entry)
            val skipped = state.skippedIn(file.path)
            val parts = listOfNotNull(
                origin,
                pluralStringResource(R.plurals.rv_config_lines, file.lines, file.lines),
                if (skipped > 0) pluralStringResource(R.plurals.rv_config_skipped_count, skipped, skipped) else null,
            )
            RvSettingsNavRow(file.path, null, { onFile(file.path) }, subtitle = parts.joinToString(SEPARATOR))
        }
    }
}

/** The summary's counts, DNS request and response rules apart; then the running projection. */
@Composable
private fun ContentsCard(summary: RvConfigSummary, onEffective: () -> Unit) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_settings_group_subscription), summary.subscriptions.toString())
        RvStatRow(stringResource(R.string.rv_policy_nodes), summary.nodes.toString())
        RvStatRow(stringResource(R.string.rv_policy_groups), summary.groups.toString())
        RvStatRow(stringResource(R.string.rv_policy_rules), summary.trafficRules.toString())
        RvStatRow(stringResource(R.string.rv_config_dns_servers), summary.dnsUpstreams.toString())
        RvStatRow(stringResource(R.string.rv_config_dns_queries), summary.dnsRequestRules.toString())
        RvStatRow(stringResource(R.string.rv_config_dns_answers), summary.dnsResponseRules.toString())
        RvSeeAll(stringResource(R.string.rv_effective_open), onEffective)
    }
}

/** The support report's counts, skipped and applied in part, as 生效設定 counts rules; then the skipped list. */
@Composable
private fun CompatCard(state: RvConfigState, onSkipped: () -> Unit) {
    val skipped = state.skipped.size
    RvCard {
        RvStatRow(stringResource(R.string.rv_rules_skipped), pluralStringResource(R.plurals.rv_config_items, skipped, skipped))
        RvStatRow(stringResource(R.string.rv_config_constrained), pluralStringResource(R.plurals.rv_config_items, state.constrained, state.constrained))
        RvSeeAll(pluralStringResource(R.plurals.rv_config_see_all_items, skipped, skipped), onSkipped)
    }
}

/** When something happened: its time when it is the sample's today, else its date and time. */
@Composable
internal fun rvConfigWhen(epochMs: Long): String {
    val format = rememberRvFormat()
    return if (epochMs / MS_PER_DAY == RV_NOW / MS_PER_DAY) format.time(epochMs) else format.date(epochMs) + " " + format.time(epochMs)
}

private const val MS_PER_DAY = 86_400_000L

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvConfigOverviewPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvConfigOverviewPage(RvConfigData.skippedInclude, onBack = {}) }
}
