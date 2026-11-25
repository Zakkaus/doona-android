package dev.doona.android.prototype.rv.policy

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
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
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGlassButton
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvRemovableLeading
import dev.doona.android.designsystem.rv.RvRemoveStyle
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvRowRemove
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvRemoveTrailing
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED

/** honk's five group policies (schema 4 §4), each with the label the templates use and a glyph. */
internal enum class RvHonkPolicy(@param:StringRes val label: Int, @param:DrawableRes val glyph: Int) {
    Selector(R.string.policy_kind_selector, R.drawable.rv_sym_touch_app),
    UrlTest(R.string.policy_kind_urltest, R.drawable.rv_sym_bolt),
    LoadBalance(R.string.policy_kind_loadbalance, R.drawable.rv_sym_call_split),
    Fallback(R.string.policy_kind_fallback, R.drawable.rv_sym_arrow_downward),
    Score(R.string.policy_kind_score, R.drawable.rv_sym_speed),
}

/** What a group member is: a node (with its flag), a group (with its policy), or honk's literal `direct` or `block`. */
internal sealed interface RvMemberRef {
    val id: String

    class Node(override val id: String, val name: String, val flag: String?) : RvMemberRef

    class Group(override val id: String, val name: String, val policy: RvHonkPolicy) : RvMemberRef

    data object Direct : RvMemberRef {
        override val id = "direct"
    }

    data object Block : RvMemberRef {
        override val id = "block"
    }
}

/** A group as its editor holds it: the authored name, policy, members in group order, and `default`, `final` and `exit`. */
internal data class RvGroupDraft(
    val name: String,
    val policy: RvHonkPolicy,
    val members: List<RvMemberRef>,
    val defaultId: String? = null,
    val finalId: String? = null,
    val exitId: String? = null,
)

/**
 * A honk group's editor (config-plan row 17), in the rule editor's grammar: the large title; the authored name; the
 * policy, any of honk's five, as the 送往 card's radio rows; the members in group order (nodes, nested groups, and
 * `direct` or `block` as honk writes them), each removable, with 新增成員 at the card's end; and `default`, `final` and
 * `exit`, each a row that opens its picker. [removeStyle] is the owner's open question: A puts the remove discs in edit
 * mode, which the bar's pencil turns on and 完成 ends; B shows a remove control at every member's end.
 */
@Composable
internal fun RvGroupEditPage(
    draft: RvGroupDraft,
    removeStyle: RvRemoveStyle,
    editing: Boolean,
    onChange: (RvGroupDraft) -> Unit,
    onEdit: (Boolean) -> Unit,
    onBack: () -> Unit,
    removing: RvMemberRef? = null,
    onRemoving: (RvMemberRef?) -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_group_edit_title)
    val editLabel = stringResource(R.string.rv_rule_menu_edit)
    val inEdit = removeStyle == RvRemoveStyle.Editing && editing
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        actions = if (removeStyle == RvRemoveStyle.Editing && !editing) ({ glass -> RvGlassButton(glass, R.drawable.rv_sym_edit, editLabel) { onEdit(true) } }) else null,
        bottomAction = {
            if (inEdit) RvPrimaryButton(stringResource(R.string.rv_rules_done), { onEdit(false) }) else RvPrimaryButton(stringResource(R.string.rv_policy_apply), NOT_WIRED)
        },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvSectionTitle(stringResource(R.string.rv_group_edit_name), Modifier.padding(horizontal = RvSpace.gutter), first = true)
        RvGutter { RvTextField(draft.name, { onChange(draft.copy(name = it)) }, label = null) }
        RvSectionTitle(stringResource(R.string.rv_group_edit_policy), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { PolicyCard(draft.policy) { onChange(draft.copy(policy = it)) } }
        RvSectionTitle(stringResource(R.string.rv_group_edit_members), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvCard {
                val shown = removeStyle == RvRemoveStyle.Trailing || inEdit
                draft.members.forEach { member ->
                    MemberRow(member, if (shown) RvRowRemove(removeStyle) { onRemoving(member) } else null)
                }
                if (shown) RvAddRow(stringResource(R.string.rv_group_edit_add_member), NOT_WIRED)
            }
        }
        RvSectionTitle(stringResource(R.string.rv_group_edit_options), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { OptionsCard(draft) }
    }
    val name = removing?.let { memberName(it) }.orEmpty()
    RvConfirmSheet(
        visible = removing != null,
        title = stringResource(R.string.rv_group_member_remove_title, name),
        body = stringResource(R.string.rv_group_member_remove_body),
        confirm = stringResource(R.string.rv_group_member_remove_confirm),
        dismiss = stringResource(R.string.rv_rule_delete_cancel),
        onConfirm = {
            onChange(draft.copy(members = draft.members.filterNot { it.id == removing?.id }))
            onRemoving(null)
        },
        onDismiss = { onRemoving(null) },
        bodyKeepWords = false,
    )
}

/** The five policies as radio rows, each with its glyph. */
@Composable
private fun PolicyCard(policy: RvHonkPolicy, onPolicy: (RvHonkPolicy) -> Unit) {
    RvCard {
        RvHonkPolicy.entries.forEach { p ->
            RvRow({ RvIconDisc(p.glyph) }, stringResource(p.label), null, Modifier.rvRowSelect(p == policy, { onPolicy(p) })) { RvRadio(p == policy) }
        }
    }
}

/** One member: a node by its flag, a group by its policy's glyph and label, 直連 and 拒絕 by theirs. */
@Composable
private fun MemberRow(member: RvMemberRef, remove: RvRowRemove?) {
    val name = memberName(member)
    val label = stringResource(R.string.rv_member_remove, name)
    val subtitle = when (member) {
        is RvMemberRef.Node -> stringResource(R.string.rv_group_member_node)
        is RvMemberRef.Group -> stringResource(R.string.rv_group_member_group, stringResource(member.policy.label))
        else -> null
    }
    RvRow(
        { RvRemovableLeading(remove, label) { MemberMark(member) } },
        name,
        subtitle,
        titleKeepWords = false,
        trailing = rvRemoveTrailing(remove, label),
    )
}

@Composable
private fun MemberMark(member: RvMemberRef) = when (member) {
    is RvMemberRef.Node -> RvFlag(member.flag, RvSize.rowDisc)
    is RvMemberRef.Group -> RvIconDisc(member.policy.glyph)
    RvMemberRef.Direct -> RvIconDisc(R.drawable.rv_sym_arrow_right_alt)
    RvMemberRef.Block -> RvIconDisc(R.drawable.rv_sym_block)
}

@Composable
private fun memberName(member: RvMemberRef): String = when (member) {
    is RvMemberRef.Node -> member.name
    is RvMemberRef.Group -> member.name
    RvMemberRef.Direct -> stringResource(R.string.mode_direct)
    RvMemberRef.Block -> stringResource(R.string.rv_policy_reject)
}

/** `default`, `final` and `exit`: each names a member, 直連 or 拒絕, or 未設定; a tap opens its picker. */
@Composable
private fun OptionsCard(draft: RvGroupDraft) {
    val all = draft.members + listOf(RvMemberRef.Direct, RvMemberRef.Block)

    @Composable
    fun value(id: String?) = all.firstOrNull { it.id == id }?.let { memberName(it) } ?: stringResource(R.string.rv_group_edit_unset)
    RvCard {
        RvRow({ RvIconDisc(R.drawable.rv_sym_check) }, stringResource(R.string.rv_group_edit_default), value(draft.defaultId), onClick = NOT_WIRED) { RvChevron() }
        RvRow({ RvIconDisc(R.drawable.rv_sym_directions) }, stringResource(R.string.rv_group_edit_final), value(draft.finalId), onClick = NOT_WIRED) { RvChevron() }
        RvRow({ RvIconDisc(R.drawable.rv_sym_vpn_lock) }, stringResource(R.string.rv_group_edit_exit), value(draft.exitId), onClick = NOT_WIRED) { RvChevron() }
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 1400)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvGroupEditPagePreview() {
    val dark = isSystemInDarkTheme()
    var draft by remember { mutableStateOf(RvNativeSample.overseas) }
    var editing by rememberSaveable { mutableStateOf(true) }
    RvTheme(rvSystemScheme(dark), dark) { RvGroupEditPage(draft, RvRemoveStyle.Editing, editing, { draft = it }, { editing = it }, onBack = {}) }
}
