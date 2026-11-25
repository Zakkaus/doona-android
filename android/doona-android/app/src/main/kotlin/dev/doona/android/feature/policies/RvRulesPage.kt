package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLongPressMenu
import dev.doona.android.designsystem.rv.RvMenuItem
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleState

/**
 * What the list of all rules can do: open a rule, turn one off or on and delete one (its long-press menu), reorder, add,
 * apply what is held, drop it, and open the effective config.
 */
internal class RvRulesRoutes(
    val open: (Int) -> Unit = {},
    val toggle: (Int) -> Unit = {},
    val delete: (Int) -> Unit = {},
    val edit: (Boolean) -> Unit = {},
    val newRule: () -> Unit = {},
    val apply: () -> Unit = {},
    val discard: () -> Unit = {},
    val effective: () -> Unit = {},
)

/**
 * All rules (查看全部 on 策略), in the 策略 card's grammar: the large title and, unless reordering, one line on how rules
 * are tried; any rules held for a later 套用, with the button that applies them; every rule in order, each with where
 * it sends and today's bytes, an off or skipped rule saying so; how many are in use, off and skipped, as D34 counts
 * them; and the way to the effective config, which shows what the core compiled. The bar's pencil turns on [editing],
 * for reordering (first match wins, so order matters): a long press lifts a rule to drag it, and 完成 ends it. A rule
 * opens its page; a long press its menu. 刪除 asks in the library's confirm sheet. A [banner] (the source did not parse,
 * or the rules could not be read) stands in place of the cards, in [RvGutter]'s slot; the title, the bar and the bottom
 * action stay.
 */
@Composable
internal fun RvRulesPage(
    rules: List<RvPolicyRule>,
    pending: List<RvPolicyRule>,
    editing: Boolean,
    onBack: () -> Unit,
    routes: RvRulesRoutes = RvRulesRoutes(),
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_policy_rules)
    val reorder = stringResource(R.string.rv_rules_reorder)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        actions = if (editing) null else ({ glass -> RvGlassButton(glass, R.drawable.rv_sym_edit, reorder) { routes.edit(true) } }),
        bottomAction = { BottomAction(pending.size, editing, routes) },
    ) { titleBlock ->
        val order: (@Composable ColumnScope.() -> Unit)? = if (!editing && banner == null) ({ RvCaption(stringResource(R.string.rv_policy_rules_order)) }) else null
        RvPageHeader(title, titleBlock, insets, caption = order)
        RvGutter(banner = banner) {
            if (banner != null) return@RvGutter
            if (pending.isNotEmpty() && !editing) PendingCard(pending, routes.discard)
            RulesCard(rules, editing, routes)
            if (!editing) {
                CountsCard(rules)
                RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, stringResource(R.string.rv_effective_open), null, onClick = routes.effective) { RvChevron() } }
            }
        }
    }
}

/** The page's one action: 完成 while reordering, 套用 N 條 while rules are held, else 新增規則. */
@Composable
private fun BottomAction(held: Int, editing: Boolean, routes: RvRulesRoutes) {
    when {
        editing -> RvPrimaryButton(stringResource(R.string.rv_rules_done), { routes.edit(false) })
        held > 0 -> RvPrimaryButton(pluralStringResource(R.plurals.rv_rules_apply_held, held, held), routes.apply)
        else -> RvPrimaryButton(stringResource(R.string.rv_policy_new_rule), routes.newRule)
    }
}

/** Rules written but not applied yet (doona web's pending card): each as it will route, then a row that drops them. */
@Composable
private fun PendingCard(pending: List<RvPolicyRule>, onDiscard: () -> Unit) {
    val held = stringResource(R.string.rv_rules_held)
    RvCard {
        RvCardHeader(stringResource(R.string.rv_rules_pending))
        pending.forEach { RvRuleRow(it, end = held) }
        RvRow({ RvIconDisc(R.drawable.rv_sym_delete) }, stringResource(R.string.rv_rules_discard), null, onClick = onDiscard)
    }
}

/**
 * Every rule in order. A tap opens a rule; a long press opens its menu (編輯, 停用 or 啟用, 刪除, which asks first). The
 * last rule only routes the rest, so it has no menu. In edit mode nothing opens and the rows keep their full width; a
 * long press is to lift a row and drag it (the gestures lane's `Modifier.rvReorder`, adopted here when that lane is
 * merged; the last rule stays last).
 */
@Composable
private fun RulesCard(rules: List<RvPolicyRule>, editing: Boolean, routes: RvRulesRoutes) {
    var deleting by rememberSaveable { mutableStateOf<Int?>(null) }
    RvCard {
        rules.forEachIndexed { i, rule ->
            val last = i == rules.lastIndex
            when {
                editing -> RvRuleRow(rule)
                last -> RvRuleRow(rule, Modifier.rvRowPress(Role.Button, { routes.open(i) }))
                else -> RvLongPressMenu(ruleMenu(rule, { routes.open(i) }, { routes.toggle(i) }, { deleting = i }), { routes.open(i) }) { RvRuleRow(rule, it) }
            }
        }
    }
    val target = deleting?.let(rules::getOrNull)
    RvConfirmSheet(
        visible = target != null,
        title = stringResource(R.string.rv_rule_delete_title),
        body = stringResource(R.string.rv_rule_delete_body, target?.match?.title().orEmpty()),
        confirm = stringResource(R.string.rv_rule_delete_confirm),
        dismiss = stringResource(R.string.rv_rule_delete_cancel),
        onConfirm = {
            deleting?.let(routes.delete)
            deleting = null
        },
        onDismiss = { deleting = null },
        bodyKeepWords = false,
    )
}

/** A rule's long-press menu: 編輯, 停用 or 啟用, then 刪除 set apart and marked destructive. */
@Composable
private fun ruleMenu(rule: RvPolicyRule, onEdit: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit): List<RvMenuItem> {
    val off = rule.state == RvRuleState.Off
    return listOf(
        RvMenuItem(stringResource(R.string.rv_rule_menu_edit), R.drawable.rv_sym_edit, onClick = onEdit),
        RvMenuItem(stringResource(if (off) R.string.rv_rule_menu_on else R.string.rv_rule_menu_off), R.drawable.rv_sym_block, onClick = onToggle),
        RvMenuItem(stringResource(R.string.rv_rule_menu_delete), R.drawable.rv_sym_delete, destructive = true, separated = true, onClick = onDelete),
    )
}

/** How many rules route, how many the user turned off, and how many the core skips; the note under it says what skipping means. */
@Composable
private fun CountsCard(rules: List<RvPolicyRule>) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_rules_in_use), count(rules, RvRuleState.On))
        RvStatRow(stringResource(R.string.rv_rules_off), count(rules, RvRuleState.Off))
        RvStatRow(stringResource(R.string.rv_rules_skipped), count(rules, RvRuleState.Skipped))
    }
}

@Composable
private fun count(rules: List<RvPolicyRule>, state: RvRuleState): String {
    val n = rules.count { it.state == state }
    return pluralStringResource(R.plurals.rv_rules_count, n, n)
}
