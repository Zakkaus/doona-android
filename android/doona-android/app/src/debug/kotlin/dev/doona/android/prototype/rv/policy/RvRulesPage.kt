package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvCardHeader
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvChevronSize
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvStatRow
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.rvSystemScheme

/** What the list of all rules can do: open a rule, its long-press menu, reorder, add, apply what is held, drop it. */
internal class RvRulesRoutes(
    val open: (Int) -> Unit = {},
    val menu: (Int) -> Unit = {},
    val edit: (Boolean) -> Unit = {},
    val newRule: () -> Unit = {},
    val apply: () -> Unit = {},
    val discard: () -> Unit = {},
    val effective: () -> Unit = {},
)

/**
 * All rules (查看全部 on 策略), in the 策略 card's grammar: the large title and one line on how rules are tried; any rules
 * held for a later 套用, with the button that applies them; every rule in order, each with where it sends and today's
 * bytes, an off or skipped rule saying so; how many are in use, off and skipped, as D34 counts them; and the way to the
 * effective config, which shows what the core compiled. The bar's
 * pencil turns on [editing]: each rule but the last shows a drag handle (first match wins, so order matters; the drag
 * itself is the gestures lane's), the line says how, and 完成 ends it. A rule opens its page; a long press its menu.
 */
@Composable
internal fun RvRulesPage(
    rules: List<RvPolicyRule>,
    pending: List<RvPolicyRule>,
    editing: Boolean,
    onBack: () -> Unit,
    routes: RvRulesRoutes = RvRulesRoutes(),
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
        RvLargeTitle(title, titleBlock, insets)
        val line = if (editing) R.string.rv_rules_edit_line else R.string.rv_policy_rules_order
        RvText(stringResource(line), Rv.type.rowSub, Modifier.padding(horizontal = RvSpace.gutter), color = Rv.colors.textSecondary)
        Spacer(Modifier.height(RvSpace.titleToCard))
        RvGutter {
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

/** Every rule in order; in edit mode each but the last carries a drag handle and nothing opens. */
@Composable
private fun RulesCard(rules: List<RvPolicyRule>, editing: Boolean, routes: RvRulesRoutes) {
    RvCard {
        rules.forEachIndexed { i, rule ->
            val last = i == rules.lastIndex
            val press = if (editing) {
                Modifier
            } else {
                Modifier.combinedClickable(
                    interactionSource = null,
                    indication = ripple(color = Rv.colors.stateLayer),
                    role = Role.Button,
                    onLongClick = if (last) null else ({ routes.menu(i) }),
                ) { routes.open(i) }
            }
            val handle: (@Composable () -> Unit)? = if (editing && !last) ({ RvGlyph(R.drawable.rv_sym_drag_handle, RvChevronSize.box, Rv.colors.textSecondary) }) else null
            RvRuleRow(rule, press, trailing = handle)
        }
    }
}

/** How many rules route, how many the user turned off, and how many the core skips, with what skipping means. */
@Composable
private fun CountsCard(rules: List<RvPolicyRule>) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_rules_in_use), count(rules, RvRuleState.On))
        RvStatRow(stringResource(R.string.rv_rules_off), count(rules, RvRuleState.Off))
        RvStatRow(stringResource(R.string.rv_rules_skipped), count(rules, RvRuleState.Skipped))
        RvText(
            stringResource(R.string.rv_rules_skipped_line),
            Rv.type.rowSub,
            Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding),
            color = Rv.colors.textSecondary,
        )
    }
}

@Composable
private fun count(rules: List<RvPolicyRule>, state: RvRuleState): String {
    val n = rules.count { it.state == state }
    return pluralStringResource(R.plurals.rv_rules_count, n, n)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvRulesPagePreview() {
    val dark = isSystemInDarkTheme()
    var editing by remember { mutableStateOf(false) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvRulesPage(RvPolicyData.allRules, emptyList(), editing, onBack = {}, routes = RvRulesRoutes(edit = { editing = it }))
    }
}
