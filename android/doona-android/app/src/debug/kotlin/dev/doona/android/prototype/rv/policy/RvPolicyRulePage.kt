package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvControlCard
import dev.doona.android.designsystem.rv.RvControlLine
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rvInkStart
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.text

/**
 * The quick rule, opened from a connection (doona web's one rule dialog, Karing's 選擇分流類型), in node detail's
 * grammar: the large title; the connection as a key-stats card, with the rule it matched and the
 * node it went through; how the new rule matches, with what that choice covers; where it sends, the groups drawn as 活動
 * draws a destination; where it goes in the list, before the rule that matched; and 套用 floating at the bottom.
 */
@Composable
internal fun RvPolicyRulePage(
    connection: RvConnection,
    rule: RvQuickRule,
    onChange: (RvQuickRule) -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_policy_new_rule)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter { ConnectionCard(connection) }
        RvSectionTitle(stringResource(R.string.rv_policy_match_by), Modifier.padding(horizontal = RvSpace.gutter))
        MatchBy(connection.host, rule.matchBy) { onChange(rule.copy(matchBy = it)) }
        RvSectionTitle(stringResource(R.string.rv_policy_send_to), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvTargetCard(rvSampleTargets, rule.targetId) { onChange(rule.copy(targetId = it)) }
            PositionCard(connection.rule)
        }
    }
}

/** Where the connection went, from which app, the rule that sent it and the node it went through. */
@Composable
private fun ConnectionCard(connection: RvConnection) {
    val group = connection.rule.group ?: RvPolicyData.main
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_target), connection.host + PORT + connection.port, valueKeepWords = false)
        RvStatRow(stringResource(R.string.rv_policy_app), connection.app, valueKeepWords = false)
        RvStatRow(stringResource(R.string.rv_policy_matched), connection.rule.match.title(), valueKeepWords = false)
        RvStatRow(stringResource(R.string.rv_policy_via), group.name.text() + SEPARATOR + group.selected.name, valueKeepWords = false)
    }
}

/**
 * 完整網域 | 網域後綴 | 關鍵字 in a control card, as 策略's mode, and one line saying what the chosen way covers for this
 * host, its ink starting on the pixel of the track's edge.
 */
@Composable
private fun MatchBy(host: String, matchBy: RvMatchBy, onMatchBy: (RvMatchBy) -> Unit) {
    val ways = RvMatchBy.entries
    RvGutter {
        RvControlCard(caption = null) {
            RvSegmented(ways.map { stringResource(it.label) }, ways.indexOf(matchBy), { onMatchBy(ways[it]) })
            val line = stringResource(matchBy.line, matchBy.of(host))
            RvControlLine(line, Modifier.rvInkStart(line, Rv.type.rowSub))
        }
    }
}

/** Where the rule goes: before the rule the connection matched, so it is tried first. */
@Composable
private fun PositionCard(matched: RvPolicyRule) {
    val before = matched.match.title()
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_rule) },
            stringResource(R.string.rv_policy_position),
            stringResource(R.string.rv_policy_position_value, before),
            onClick = NOT_WIRED,
        ) { RvChevron() }
    }
}

private const val PORT = ":"

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPolicyRulePagePreview() {
    val dark = isSystemInDarkTheme()
    var rule by remember { mutableStateOf(RvQuickRule()) }
    RvTheme(rvSystemScheme(dark), dark) { RvPolicyRulePage(RvPolicyData.connection, rule, { rule = it }, onBack = {}, onApply = {}) }
}
