package dev.doona.android.feature.policies

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChoiceLine
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSourceExcerpt
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.rvInkStart
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState

/** A rule being edited: how it matches, the value it matches (none for GEOIP CN and private ranges), where, if on. */
@Immutable
internal data class RvRuleDraft(val kind: RvMatchKind, val value: String, val targetId: String, val enabled: Boolean = true, val must: Boolean = false) {
    val needsValue: Boolean get() = kind !in NO_VALUE

    /** Why the value cannot be applied, or null: missing, or not a domain or IP range of the kind's form. */
    val problem: Int?
        get() = when {
            !needsValue -> null
            value.isBlank() -> if (kind == RvMatchKind.Cidr) R.string.rv_rule_edit_need_cidr else R.string.rv_rule_edit_need_domain
            kind == RvMatchKind.Cidr && !CIDR.matches(value) -> R.string.rv_rule_edit_bad_cidr
            kind != RvMatchKind.Cidr && !DOMAIN.matches(value) -> R.string.rv_rule_edit_bad_domain
            else -> null
        }

    companion object {
        /**
         * The draft of a rule: its kind, value, target and whether it is on. A domain set opens as a suffix on its first
         * domain; the last rule has no match to edit (its page is the main node's group page).
         */
        fun of(rule: RvPolicyRule): RvRuleDraft {
            val (kind, value) = when (val match = rule.match) {
                is RvRuleMatch.Value -> match.kind to match.value
                is RvRuleMatch.Domains -> RvMatchKind.Suffix to match.first
                is RvRuleMatch.GeoIp -> RvMatchKind.GeoIpCn to ""
                is RvRuleMatch.GeoSite -> RvMatchKind.Suffix to match.category
                RvRuleMatch.Lan -> RvMatchKind.Private to ""
                RvRuleMatch.Rest -> error("the last rule has no match to edit")
            }
            return RvRuleDraft(kind, value, rule.group?.id ?: RV_DIRECT, rule.state != RvRuleState.Off)
        }
    }
}

/**
 * A native honk statement on its page: its place, [index] of [count] in its list, and with [statement] the lines as
 * written, for a match the form cannot represent (config plan: such a form opens its exact source instead). A statement
 * written over several lines is [sourceOnly]: the page shows its lines and nothing to change (owner, q-rule-multiline
 * 2026-11-26), since a write checks one line.
 */
@Immutable
internal data class RvNativeRuleAt(val index: Int, val count: Int, val statement: RvDiffFile? = null, val sourceOnly: Boolean = false)

private val NO_VALUE = setOf(RvMatchKind.GeoIpCn, RvMatchKind.Private)

/** The slider's two sets of kinds: a domain's three, as the quick rule offers them, and an address's three. */
private val DOMAIN_KINDS = listOf(RvMatchKind.Full, RvMatchKind.Suffix, RvMatchKind.Keyword)
internal val ADDRESS_KINDS = listOf(RvMatchKind.Cidr, RvMatchKind.GeoIpCn, RvMatchKind.Private)
private val CIDR = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d|[12]\d|3[0-2])""")
private val DOMAIN = Regex("""[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*""")

/**
 * A rule's page (全部規則 → a rule), in the quick rule's grammar: the large title; 比對方式 as the quick rule's slider
 * over the three kinds of the rule's own set (a domain's: 完整網域, 網域後綴, 關鍵字; an address's: IP 範圍, GEOIP CN, 私有
 * IP 位址), then the value in the library's text field (none for GEOIP CN and private ranges), and one line on
 * what the rule covers, or what is wrong with the value; 送往, the quick rule's list; a card with 啟用 and 刪除規則,
 * which asks first; and 套用 floating at the bottom, off while the value is wrong. A [native] honk statement adds 拒絕
 * to 送往, 所有模式都生效 (`must`) under 啟用 (which pauses and resumes it) and 位置 with 上移 and 下移 ([onMove] gets the
 * new place); a match the form cannot represent is shown as its source lines instead of the slider, and a line opens
 * the editor there ([onOpenSource]). A [banner] (a write honk did not take, a failed read) heads the page.
 */
@Composable
internal fun RvRuleEditPage(
    draft: RvRuleDraft,
    onChange: (RvRuleDraft) -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    targets: List<RvPolicyGroup>,
    native: RvNativeRuleAt? = null,
    onMove: (Int) -> Unit = {},
    onOpenSource: (RvDiffAt) -> Unit = {},
    banner: RvNotice? = null,
) {
    val form = RvRuleForm(stringResource(R.string.rv_rule_edit_title), draft, onChange, native, must = native != null)
    RvRuleFormPage(form, onBack, onApply, onDelete, onMove, onOpenSource, banner) { toggles ->
        RvSectionTitle(stringResource(R.string.rv_policy_send_to), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvTargetCard(targets, draft.targetId, block = native != null) { onChange(draft.copy(targetId = it)) }
            toggles()
        }
    }
}

/**
 * What a rule's page shows around its action: the [title], the [draft]'s match, and with [must] the 所有模式都生效 row
 * under 啟用; [privateLine] says what a private range covers in this list.
 */
@Immutable
internal class RvRuleForm(
    val title: String,
    val draft: RvRuleDraft,
    val onChange: (RvRuleDraft) -> Unit,
    val native: RvNativeRuleAt?,
    val must: Boolean,
    @param:StringRes val privateLine: Int = R.string.rv_rule_edit_private_line,
)

/**
 * A rule's page: the large title, 依據 (the match fields, or the statement's source lines), then [action], which places
 * the card of 啟用 and 刪除規則 it is handed (`toggles`); 位置 for a native statement; 套用 floating at the bottom, off
 * while the value is wrong; and the sheet 刪除規則 asks with.
 */
@Composable
internal fun RvRuleFormPage(
    form: RvRuleForm,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onOpenSource: (RvDiffAt) -> Unit,
    banner: RvNotice?,
    action: @Composable (toggles: @Composable () -> Unit) -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    var confirming by rememberSaveable { mutableStateOf(false) }
    val draft = form.draft
    val statement = form.native?.statement
    val sourceOnly = form.native?.sourceOnly == true
    RvScrollPage(
        form.title,
        insets,
        onBack = onBack,
        // A statement the form shows as source keeps its match, so only the form's own value can hold 套用 back.
        bottomAction = if (sourceOnly) {
            null
        } else {
            { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply, enabled = statement != null || draft.problem == null) }
        },
    ) { titleBlock ->
        RvLargeTitle(form.title, titleBlock, insets)
        RvBannerSlot(banner, RvSpace.none)
        RvSectionTitle(stringResource(R.string.rv_policy_match_by), Modifier.padding(horizontal = RvSpace.gutter), first = banner == null)
        if (statement == null) {
            MatchFields(draft, form.onChange, form.privateLine)
        } else {
            RvGutter { RvSourceExcerpt(statement.source, statement.hunks.first(), onOpenSource) }
            RvFootnote(stringResource(if (sourceOnly) R.string.rv_rule_edit_multiline else R.string.rv_rule_edit_match_in_source))
        }
        if (!sourceOnly) {
            action { Toggles(form) { confirming = true } }
            form.native?.let { Position(it, onMove) }
        }
    }
    RvConfirmSheet(
        visible = confirming,
        title = stringResource(R.string.rv_rule_delete_title),
        body = stringResource(R.string.rv_rule_delete_body, draft.value.ifBlank { stringResource(draft.kind.label) }),
        confirm = stringResource(R.string.rv_rule_delete_confirm),
        dismiss = stringResource(R.string.rv_rule_delete_cancel),
        onConfirm = {
            confirming = false
            onDelete()
        },
        onDismiss = { confirming = false },
        bodyKeepWords = false,
    )
}

/** A rule's page whose first read failed: there is no rule to edit, so under the [title] stands the [banner] alone. */
@Composable
internal fun RvRuleFormFailed(title: String, banner: RvNotice, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(banner)
    }
}

/** The card of 啟用 (which pauses and resumes a native statement), 所有模式都生效 when the form has it, and 刪除規則. */
@Composable
private fun Toggles(form: RvRuleForm, onDelete: () -> Unit) {
    val draft = form.draft
    RvCard {
        RvSwitchRow(R.drawable.rv_sym_rule, stringResource(R.string.rv_rule_edit_enabled), draft.enabled) { form.onChange(draft.copy(enabled = it)) }
        if (form.must) {
            RvSwitchRow(R.drawable.rv_sym_vpn_lock, stringResource(R.string.rv_rule_edit_must), draft.must) { form.onChange(draft.copy(must = it)) }
        }
        RvRow({ RvIconDisc(R.drawable.rv_sym_delete) }, stringResource(R.string.rv_rule_edit_delete), null, onClick = onDelete)
    }
}

/** 位置: 上移 and 下移 where the statement can go, and its place under the card; none for the only statement. */
@Composable
private fun Position(at: RvNativeRuleAt, onMove: (Int) -> Unit) {
    // The only statement of its list has nowhere to go.
    if (at.count < 2) return
    RvSectionTitle(stringResource(R.string.rv_rule_edit_position), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter {
        RvCard {
            if (at.index > 1) RvRow({ RvIconDisc(R.drawable.rv_sym_arrow_upward) }, stringResource(R.string.rv_rule_edit_move_up), null, onClick = { onMove(at.index - 1) })
            if (at.index < at.count) RvRow({ RvIconDisc(R.drawable.rv_sym_arrow_downward) }, stringResource(R.string.rv_rule_edit_move_down), null, onClick = { onMove(at.index + 1) })
        }
    }
    RvFootnote(stringResource(R.string.rv_rule_edit_position_line, at.index, at.count))
}

/**
 * The kind slider over the three kinds of the rule's set, the value field when the kind takes one, and the line under
 * them; the slider sits [RvSpace.controlStack] over the field, two controls stacked in one.
 */
@Composable
private fun MatchFields(draft: RvRuleDraft, onChange: (RvRuleDraft) -> Unit, @StringRes privateLine: Int) {
    val kinds = if (draft.kind in DOMAIN_KINDS) DOMAIN_KINDS else ADDRESS_KINDS
    Column(Modifier.padding(horizontal = RvSpace.gutter)) {
        RvSegmented(kinds.map { stringResource(it.label) }, kinds.indexOf(draft.kind), { onChange(draft.copy(kind = kinds[it])) })
        Spacer(Modifier.height(RvSpace.controlStack))
        val problem = draft.problem?.let { stringResource(it) }
        if (draft.needsValue) {
            // The kind slider right above names what the field holds, so it has no label of its own (Addendum 3).
            RvTextField(
                draft.value,
                { onChange(draft.copy(value = it.trim())) },
                label = null,
                name = stringResource(
                    when (draft.kind) {
                        RvMatchKind.Cidr -> R.string.rv_policy_match_cidr
                        RvMatchKind.Keyword -> R.string.rv_policy_match_keyword
                        else -> R.string.rv_rule_edit_domain
                    },
                ),
                placeholder = stringResource(if (draft.kind == RvMatchKind.Cidr) R.string.rv_rule_edit_cidr_hint else R.string.rv_rule_edit_domain_hint),
                supporting = if (problem == null) draft.covers(privateLine) else null,
                error = problem,
            )
        } else {
            val line = draft.covers(privateLine)
            RvChoiceLine(line, Modifier.rvInkStart(line, Rv.type.rowSub))
        }
    }
}

/** What the rule covers, as the quick rule says it under its choice. */
@Composable
private fun RvRuleDraft.covers(@StringRes privateLine: Int): String = when (kind) {
    RvMatchKind.Full -> stringResource(R.string.rv_policy_match_full_line, value)
    RvMatchKind.Suffix -> stringResource(R.string.rv_policy_match_suffix_line, value)
    RvMatchKind.Keyword -> stringResource(R.string.rv_policy_match_keyword_line, value)
    RvMatchKind.Cidr -> stringResource(R.string.rv_rule_edit_cidr_line, value)
    RvMatchKind.GeoIpCn -> stringResource(R.string.rv_rule_edit_geoip_line)
    RvMatchKind.Private -> stringResource(privateLine)
}
