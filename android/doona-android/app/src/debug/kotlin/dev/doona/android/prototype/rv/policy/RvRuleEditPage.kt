package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChoiceLine
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSwitch
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvInkStart
import dev.doona.android.designsystem.rv.rvRowToggle
import dev.doona.android.designsystem.rv.rvSystemScheme

/** A rule being edited: how it matches, the value it matches (none for GEOIP CN and private ranges), where, if on. */
@Immutable
internal data class RvRuleDraft(val kind: RvMatchKind, val value: String, val targetId: String, val enabled: Boolean = true) {
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

private val NO_VALUE = setOf(RvMatchKind.GeoIpCn, RvMatchKind.Private)

/** The slider's two sets of kinds: a domain's three, as the quick rule offers them, and an address's three. */
private val DOMAIN_KINDS = listOf(RvMatchKind.Full, RvMatchKind.Suffix, RvMatchKind.Keyword)
private val ADDRESS_KINDS = listOf(RvMatchKind.Cidr, RvMatchKind.GeoIpCn, RvMatchKind.Private)
private val CIDR = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})/(\d|[12]\d|3[0-2])""")
private val DOMAIN = Regex("""[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*""")

/**
 * A rule's page (全部規則 → a rule), in the quick rule's grammar: the large title; 比對方式 as the quick rule's slider
 * over the three kinds of the rule's own set (a domain's: 完整網域, 網域後綴, 關鍵字; an address's: IP 段, GEOIP CN, 私有
 * IP 位址), then the value in the library's text field (none for GEOIP CN and private ranges), and one line on
 * what the rule covers, or what is wrong with the value; 送往, the quick rule's list; a card with 啟用 and 刪除規則,
 * which asks first; and 套用 floating at the bottom, off while the value is wrong.
 */
@Composable
internal fun RvRuleEditPage(draft: RvRuleDraft, onChange: (RvRuleDraft) -> Unit, onBack: () -> Unit, onApply: () -> Unit, onDelete: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    var confirming by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.rv_rule_edit_title)
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_apply), onApply, enabled = draft.problem == null) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvSectionTitle(stringResource(R.string.rv_policy_match_by), Modifier.padding(horizontal = RvSpace.gutter), first = true)
        MatchFields(draft, onChange)
        RvSectionTitle(stringResource(R.string.rv_policy_send_to), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvTargetCard(rvSampleTargets, draft.targetId) { onChange(draft.copy(targetId = it)) }
            RvCard {
                val toggle = Modifier.rvRowToggle(draft.enabled) { onChange(draft.copy(enabled = it)) }
                RvRow({ RvIconDisc(R.drawable.rv_sym_rule) }, stringResource(R.string.rv_rule_edit_enabled), null, toggle) { RvSwitch(draft.enabled) }
                RvRow({ RvIconDisc(R.drawable.rv_sym_delete) }, stringResource(R.string.rv_rule_edit_delete), null, onClick = { confirming = true })
            }
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

/**
 * The kind slider over the three kinds of the rule's set, the value field when the kind takes one, and the line under
 * them; the slider sits [RvSpace.controlStack] over the field, two controls stacked in one.
 */
@Composable
private fun MatchFields(draft: RvRuleDraft, onChange: (RvRuleDraft) -> Unit) {
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
                supporting = if (problem == null) draft.covers() else null,
                error = problem,
            )
        } else {
            val line = draft.covers()
            RvChoiceLine(line, Modifier.rvInkStart(line, Rv.type.rowSub))
        }
    }
}

/** What the rule covers, as the quick rule says it under its choice. */
@Composable
private fun RvRuleDraft.covers(): String = when (kind) {
    RvMatchKind.Full -> stringResource(R.string.rv_policy_match_full_line, value)
    RvMatchKind.Suffix -> stringResource(R.string.rv_policy_match_suffix_line, value)
    RvMatchKind.Keyword -> stringResource(R.string.rv_policy_match_keyword_line, value)
    RvMatchKind.Cidr -> stringResource(R.string.rv_rule_edit_cidr_line, value)
    RvMatchKind.GeoIpCn -> stringResource(R.string.rv_rule_edit_geoip_line)
    RvMatchKind.Private -> stringResource(R.string.rv_rule_edit_private_line)
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvRuleEditPagePreview() {
    val dark = isSystemInDarkTheme()
    var draft by remember { mutableStateOf(RvRuleDraft.of(RvPolicyData.allRules[5])) }
    RvTheme(rvSystemScheme(dark), dark) { RvRuleEditPage(draft, { draft = it }, onBack = {}, onApply = {}, onDelete = {}) }
}
