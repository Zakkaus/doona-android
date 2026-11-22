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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ripple
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
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvBarSpacer
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRadio
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSectionTitle
import dev.doona.android.prototype.rv.RvSeeAll
import dev.doona.android.prototype.rv.RvSegmented
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvStatRow
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * The quick rule, opened from a connection (doona web's one rule dialog, Karing's 選擇分流類型), in node detail's
 * grammar: the compact title beside the back disc; the connection as a key-stats card, with the rule it matched and the
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
    RvScrollPage(
        stringResource(R.string.rv_policy_new_rule),
        insets,
        onBack = onBack,
        compact = true,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply) },
    ) {
        RvBarSpacer(insets)
        Spacer(Modifier.height(RvSpace.gutter))
        RvGutter { ConnectionCard(connection) }
        RvSectionTitle(stringResource(R.string.rv_policy_match_by), Modifier.padding(horizontal = RvSpace.gutter))
        MatchBy(connection.host, rule.matchBy) { onChange(rule.copy(matchBy = it)) }
        RvSectionTitle(stringResource(R.string.rv_policy_send_to), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            TargetCard(rule.targetId) { onChange(rule.copy(targetId = it)) }
            PositionCard(connection.rule)
        }
    }
}

/** Where the connection went, from which app, the rule that sent it and the node it went through. */
@Composable
private fun ConnectionCard(connection: RvConnection) {
    val group = connection.rule.group ?: RvPolicyData.main
    RvCard {
        RvStatRow(stringResource(R.string.rv_policy_target), connection.host + PORT + connection.port)
        RvStatRow(stringResource(R.string.rv_policy_app), connection.app)
        RvStatRow(stringResource(R.string.rv_policy_matched), connection.rule.match.title())
        RvStatRow(stringResource(R.string.rv_policy_via), stringResource(group.name) + SEPARATOR + group.selected.name)
    }
}

/** 完整網域 | 網域後綴 | 關鍵字, and one line saying what the chosen way covers for this host. */
@Composable
private fun MatchBy(host: String, matchBy: RvMatchBy, onMatchBy: (RvMatchBy) -> Unit) {
    val ways = RvMatchBy.entries
    Column(Modifier.padding(horizontal = RvSpace.gutter)) {
        RvSegmented(ways.map { stringResource(it.label) }, ways.indexOf(matchBy), { onMatchBy(ways[it]) })
        RvText(stringResource(matchBy.line, matchBy.of(host)), Rv.type.rowSub, Modifier.padding(top = RvSpace.tight), color = Rv.colors.textSecondary)
    }
}

/**
 * The groups the rule can send to, then 直連: the first [TARGETS] groups, then the rest in place. A group is drawn by
 * where its traffic ends, as on 活動 and in the rules card.
 */
@Composable
private fun TargetCard(targetId: String, onTarget: (String) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    val groups = listOf(RvPolicyData.main) + RvPolicyData.groups
    val shown = if (all) groups else groups.take(TARGETS)
    RvCard {
        shown.forEach { Target(it, targetId == it.id) { onTarget(it.id) } }
        Target(null, targetId == RV_DIRECT) { onTarget(RV_DIRECT) }
        if (!all) {
            val more = groups.size - shown.size
            RvSeeAll(pluralStringResource(R.plurals.rv_policy_other_groups, more, more)) { all = true }
        }
    }
}

/** One destination: the group and the node it uses, or 直連; the whole row picks it. */
@Composable
private fun Target(group: RvPolicyGroup?, selected: Boolean, onSelect: () -> Unit) {
    val pick = Modifier.selectable(selected, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.RadioButton, onClick = onSelect)
    RvRow(
        { RvPolicyTarget(group) },
        group?.let { stringResource(it.name) } ?: stringResource(R.string.mode_direct),
        group?.selected?.name ?: stringResource(R.string.rv_policy_no_proxy),
        pick,
    ) { RvRadio(selected) }
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

/** The groups listed before 其他 N 個策略組: the main node, 自動, 串流 and AI. */
private const val TARGETS = 4
private const val PORT = ":"

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPolicyRulePagePreview() {
    val dark = isSystemInDarkTheme()
    var rule by remember { mutableStateOf(RvQuickRule()) }
    RvTheme(rvSystemScheme(dark), dark) { RvPolicyRulePage(RvPolicyData.connection, rule, { rule = it }, onBack = {}, onApply = {}) }
}
