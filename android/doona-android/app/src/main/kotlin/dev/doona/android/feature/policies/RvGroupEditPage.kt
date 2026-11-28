package dev.doona.android.feature.policies

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRadioRow
import dev.doona.android.designsystem.rv.RvRemoveButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.honk.HonkGroupConfig
import dev.doona.android.honk.HonkGroupPolicy

/** The policies the editor offers, honk's `kind` each with the label the templates use and a glyph. */
internal enum class RvGroupPolicyKind(val kind: String, @param:StringRes val label: Int, @param:DrawableRes val glyph: Int) {
    Selector("selector", R.string.policy_kind_selector, R.drawable.rv_sym_touch_app),
    UrlTest("urltest", R.string.policy_kind_urltest, R.drawable.rv_sym_bolt),
    LoadBalance("loadbalance", R.string.policy_kind_loadbalance, R.drawable.rv_sym_call_split),
    Fallback("fallback", R.string.policy_kind_fallback, R.drawable.rv_sym_arrow_downward),
    Score("score", R.string.policy_kind_score, R.drawable.rv_sym_speed),
}

/** Which of the group's options a picker sets: `default` (a member's id) or `final` (an outbound's name). */
internal enum class RvGroupOption { Default, Final }

/**
 * The group editor, in the rule editor's grammar: the large title; 名稱 and 成員 as honk lists them, each row opening
 * the file that writes them ([onOpenSource]), since honk's group API does not change them; 策略, honk's five policies
 * as radio rows; 選項 with 預設 and 全部不可用時, each opening its picker ([onPick]); and 套用 at the bottom, on once
 * something changed. A [banner] (a write honk did not take, a failed read) heads the page.
 */
@Composable
internal fun RvGroupEditPage(
    name: String,
    members: List<RvGroupMember>,
    read: HonkGroupConfig,
    draft: HonkGroupConfig,
    onChange: (HonkGroupConfig) -> Unit,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onOpenSource: () -> Unit,
    onPick: (RvGroupOption) -> Unit,
    banner: RvNotice? = null,
    onName: ((String) -> Unit)? = null,
    nameError: String? = null,
    available: List<RvGroupMember> = emptyList(),
    onMembers: ((List<String>) -> Unit)? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(if (onName == null) R.string.rv_group_edit_title else R.string.rv_group_new)
    var adding by rememberSaveable { mutableStateOf(false) }
    if (adding) {
        BackHandler { adding = false }
        val add = stringResource(R.string.rv_group_edit_add_member)
        RvScrollPage(add, insets, onBack = { adding = false }) { block ->
            RvLargeTitle(add, block, insets)
            RvGutter {
                RvCard {
                    available.filterNot { it in members }.forEach { member ->
                        RvRow({ MemberMark(member) }, member.name, null, onClick = {
                            onMembers?.invoke(members.map { it.id } + member.id)
                            adding = false
                        })
                    }
                }
            }
        }
        return
    }
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        // honk-gap:group-create — there is no source writer for a new group and its options.
        bottomAction = { RvPrimaryButton(stringResource(if (onName == null) R.string.rv_policy_apply else R.string.rv_list_create), onApply, enabled = onName == null && draft != read) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvBannerSlot(banner, RvSpace.none)
        RvSectionTitle(stringResource(R.string.rv_group_edit_name), Modifier.padding(horizontal = RvSpace.gutter), first = banner == null)
        // honk-gap:group-edit
        RvGutter { RvTextField(name, onName ?: {}, label = null, name = stringResource(R.string.rv_group_edit_name), enabled = onName != null, error = nameError) }
        RvGutter {
            RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_edit) }, stringResource(if (onName == null) R.string.rv_config_edit_source else R.string.rv_config_title), null, onClick = onOpenSource) { RvChevron() } }
        }
        RvSectionTitle(stringResource(R.string.rv_group_edit_policy), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { PolicyCard(draft.policy.kind) { onChange(draft.copy(policy = policy(read, it))) } }
        RvSectionTitle(stringResource(R.string.rv_group_edit_members), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvCard {
                members.forEach { member -> MemberRow(member, onMembers?.let { change -> { change(members.filterNot { it.id == member.id }.map { it.id }) } }) }
                RvAddRow(stringResource(R.string.rv_group_edit_add_member), onMembers?.takeIf { members.size < available.size }?.let { { adding = true } })
            }
        }
        if (onName == null) RvFootnote(stringResource(R.string.rv_group_edit_in_source))
        RvSectionTitle(stringResource(R.string.rv_group_edit_options), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter {
            RvCard {
                val default = members.firstOrNull { it.id == draft.config.defaultMemberId }?.name
                RvRow({ RvIconDisc(R.drawable.rv_sym_check) }, stringResource(R.string.rv_group_edit_default), default ?: stringResource(R.string.rv_group_edit_unset), onClick = { onPick(RvGroupOption.Default) }) { RvChevron() }
                RvRow({ RvIconDisc(R.drawable.rv_sym_directions) }, stringResource(R.string.rv_group_edit_final), outboundName(draft.config.finalOutbound), onClick = { onPick(RvGroupOption.Final) }) { RvChevron() }
                // honk-gap:hops-exit
                RvRow({ RvIconDisc(R.drawable.rv_sym_vpn_lock) }, stringResource(R.string.rv_group_edit_exit), RV_NO_VALUE)
            }
        }
    }
}

/**
 * The picker of [option]: 未設定 and the group's members for 預設; 未設定, 直連, 拒絕 and the members for 全部不可用時,
 * as radio rows in one card. A tap sets it in [draft] and goes back ([onBack]).
 */
@Composable
internal fun RvGroupOptionPage(
    option: RvGroupOption,
    members: List<RvGroupMember>,
    draft: HonkGroupConfig,
    onChange: (HonkGroupConfig) -> Unit,
    onBack: () -> Unit,
    outbounds: List<String>,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(if (option == RvGroupOption.Default) R.string.rv_group_edit_default else R.string.rv_group_edit_final)
    val chosen = if (option == RvGroupOption.Default) draft.config.defaultMemberId else draft.config.finalOutbound
    // 預設 names a member by id, 全部不可用時 an outbound by name.
    val choices: List<Pair<String?, String>> = listOf(null to stringResource(R.string.rv_group_edit_unset)) + when (option) {
        RvGroupOption.Default -> members.map { it.id to it.name }
        RvGroupOption.Final -> (listOf(DIRECT to stringResource(R.string.mode_direct), BLOCK to stringResource(R.string.rv_policy_reject)) + outbounds.map { it to it }).distinctBy { it.first }
    }
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvGutter {
            RvCard {
                choices.forEach { (value, label) ->
                    val pick = {
                        onChange(
                            when (option) {
                                RvGroupOption.Default -> draft.copy(config = draft.config.copy(defaultMemberId = value))
                                RvGroupOption.Final -> draft.copy(config = draft.config.copy(finalOutbound = value))
                            },
                        )
                        onBack()
                    }
                    RvRadioRow({ ChoiceMark(value, members) }, label, value == chosen, titleKeepWords = false, onSelect = pick)
                }
            }
        }
    }
}

/** The five policies as radio rows, each with its glyph. */
@Composable
private fun PolicyCard(kind: String, onPolicy: (RvGroupPolicyKind) -> Unit) {
    RvCard {
        RvGroupPolicyKind.entries.forEach { p ->
            RvRadioRow(p.glyph, stringResource(p.label), p.kind == kind) { onPolicy(p) }
        }
    }
}

@Composable
private fun MemberRow(member: RvGroupMember, remove: (() -> Unit)?) {
    val subtitle = stringResource(if (member.group) R.string.rv_group_edit_member_group else R.string.rv_group_member_node)
    RvRow({ MemberMark(member) }, member.name, subtitle, titleKeepWords = false) {
        RvRemoveButton(stringResource(R.string.rv_list_remove, member.name), remove)
    }
}

@Composable
private fun MemberMark(member: RvGroupMember) = if (member.group) RvIconDisc(R.drawable.rv_sym_account_tree) else RvFlag(member.flag, RvSize.rowDisc)

/** A choice's mark: a member's, 直連's arrow, 拒絕's block, or nothing set. */
@Composable
private fun ChoiceMark(value: String?, members: List<RvGroupMember>) {
    val member = members.firstOrNull { it.id == value || it.name == value }
    when {
        member != null -> MemberMark(member)
        value == DIRECT -> RvIconDisc(R.drawable.rv_sym_arrow_right_alt)
        value == BLOCK -> RvIconDisc(R.drawable.rv_sym_block)
        else -> RvIconDisc(R.drawable.rv_sym_check)
    }
}

/** `final` as the page says it: 直連, 拒絕, the outbound's name, or 未設定. */
@Composable
private fun outboundName(value: String?): String = when (value) {
    null -> stringResource(R.string.rv_group_edit_unset)
    DIRECT -> stringResource(R.string.mode_direct)
    BLOCK -> stringResource(R.string.rv_policy_reject)
    else -> value
}

/** [kind] as honk's policy: the one read keeps the word its file writes; another is written as honk names it. */
private fun policy(read: HonkGroupConfig, kind: RvGroupPolicyKind): HonkGroupPolicy =
    if (read.policy.kind == kind.kind) read.policy else HonkGroupPolicy(kind.kind, kind.kind)

private const val DIRECT = "direct"
private const val BLOCK = "block"
