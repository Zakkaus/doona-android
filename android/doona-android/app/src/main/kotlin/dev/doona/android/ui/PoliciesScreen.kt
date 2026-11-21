package dev.doona.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.model.GroupDetail
import dev.doona.android.model.Member
import dev.doona.android.model.PolicyGroup
import dev.doona.android.model.PolicyKind
import dev.doona.android.model.Tone
import dev.doona.android.theme.DoonaTheme

@Composable
private fun kindLabel(kind: PolicyKind): String = stringResource(
    when (kind) {
        PolicyKind.Selector -> R.string.policy_kind_selector
        PolicyKind.Fixed -> R.string.policy_kind_fixed
        PolicyKind.UrlTest -> R.string.policy_kind_urltest
        PolicyKind.Fallback -> R.string.policy_kind_fallback
    },
)

/** 策略 (doona `src/features/policies/Policies.tsx`): the kind filter, then one card per group. */
@Composable
fun PoliciesScreen(groups: List<PolicyGroup>, counts: Triple<Int, Int, Int>, onOpen: (PolicyGroup) -> Unit, onHub: (Hub) -> Unit) {
    val space = DoonaTheme.space
    var kind by remember { mutableIntStateOf(0) }
    HubPage(
        hub = Hub.Routing,
        onHub = onHub,
        actions = {
            IconAction(R.drawable.ic_add_circle, stringResource(R.string.group_new_group))
            IconAction(R.drawable.ic_search, stringResource(R.string.ui_search))
            IconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options))
        },
    ) {
        PageTitle(stringResource(R.string.nav_policies))
        Gap(space.s4)
        PageTabs(listOf(stringResource(R.string.nav_policies), stringResource(R.string.nav_nodes), stringResource(R.string.nav_rules)), selected = 0)
        Gap(space.s6)
        Segmented(
            listOf(
                stringResource(R.string.ui_all_count, counts.first),
                stringResource(R.string.policy_kind_manual, counts.second),
                stringResource(R.string.policy_kind_auto, counts.third),
            ),
            selected = kind,
            onSelect = { kind = it },
        )
        Gap(space.s6)
        Column(verticalArrangement = Arrangement.spacedBy(space.s3)) {
            groups.forEach { GroupCard(it) { onOpen(it) } }
        }
    }
}

/** Group heading facts: kind badge, then the available and untested counts as small lights (§7, Badge, Status light). */
@Composable
private fun GroupFacts(group: PolicyGroup) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.space.s3),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.s1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(kindLabel(group.kind))
        StatusLight(Tone.Ok, stringResource(R.string.policy_healthy, group.healthy), small = true)
        if (group.untested > 0) StatusLight(Tone.Neutral, stringResource(R.string.policy_untested, group.untested), small = true)
    }
}

/**
 * The folded group card: name and facts, then the current member (TCP and UDP apart when they differ) with its
 * latency. Tapping opens the group's page.
 */
@Composable
private fun GroupCard(group: PolicyGroup, onOpen: () -> Unit) {
    val c = DoonaTheme.colors
    DoonaCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.s2)) {
                Text(group.name, style = DoonaTheme.type.cardTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GroupFacts(group)
            }
            HGap(DoonaTheme.space.s3)
            DoonaIcon(R.drawable.ic_chevron_right, c.textSecondary, size = DoonaTheme.size.iconSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CurrentSummary(group, DoonaTheme.type.label, Modifier.weight(1f))
            HGap(DoonaTheme.space.s3)
            Latency(group.tcp.latencyMs)
        }
    }
}

/** `目前` and the current member; split as `TCP … UDP …` when the two networks differ (doona `rp-split-summary`). */
@Composable
private fun CurrentSummary(group: PolicyGroup, style: TextStyle, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.policy_current), style = style, color = c.text, maxLines = 1, softWrap = false)
        HGap(DoonaTheme.space.s3)
        val udp = group.udp
        if (udp == null) {
            NodeName(group.tcp, style, modifier = Modifier.weight(1f, fill = false))
        } else {
            NetMember(stringResource(R.string.ui_tcp), group.tcp, style, Modifier.weight(1f, fill = false))
            HGap(DoonaTheme.space.s3)
            NetMember(stringResource(R.string.ui_udp), udp, style, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun NetMember(net: String, member: Member, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(net, style = style, color = DoonaTheme.colors.textSecondary, maxLines = 1, softWrap = false)
        HGap(DoonaTheme.space.s2)
        NodeName(member, style)
    }
}

/**
 * A group's own page, pushed over the list. doona opens a group in place on its card; on a phone the open group
 * then fills the screen, so here it is a page of its own with a back arrow, and its members are not framed twice.
 */
@Composable
fun GroupScreen(detail: GroupDetail, onBack: () -> Unit, onHub: (Hub) -> Unit) {
    val space = DoonaTheme.space
    val c = DoonaTheme.colors
    val group = detail.group
    var net by remember { mutableIntStateOf(0) }
    var aliveOnly by remember { mutableStateOf(false) }
    HubPage(
        hub = Hub.Routing,
        onHub = onHub,
        onBack = onBack,
        actions = {
            IconAction(R.drawable.ic_search, stringResource(R.string.policy_filter))
            IconAction(R.drawable.ic_edit, stringResource(R.string.policy_edit))
            IconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options))
        },
    ) {
        PageTitle(group.name)
        Gap(space.s2)
        GroupFacts(group)
        Gap(space.s6)
        CurrentSummary(group, DoonaTheme.type.label)
        Gap(space.s3)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s2)) {
            detail.includes.forEach { TagChip(it) }
        }
        Gap(space.s1)
        Text(stringResource(R.string.group_every_node), style = DoonaTheme.type.caption, color = c.textSecondary)
        Gap(space.s6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                listOf(stringResource(R.string.policy_both), stringResource(R.string.ui_tcp), stringResource(R.string.ui_udp)),
                selected = net,
                onSelect = { net = it },
                modifier = Modifier.weight(1f),
            )
            HGap(space.s3)
            Text(
                pluralStringResource(R.plurals.policy_members, detail.memberCount, detail.memberCount),
                style = DoonaTheme.type.body.copy(fontFeatureSettings = DoonaTheme.type.tabular),
                color = c.textSecondary,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s3)) {
            PickerButton(stringResource(R.string.policy_all_regions), onClick = {})
            PickerButton(stringResource(R.string.policy_by_latency), onClick = {}, filled = true)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.policy_alive_only), style = DoonaTheme.type.body, color = c.text, maxLines = 1, modifier = Modifier.weight(1f))
            HGap(space.s3)
            DoonaSwitch(aliveOnly, { aliveOnly = it }, stringResource(R.string.policy_alive_only))
        }
        Gap(space.s3)
        Column(verticalArrangement = Arrangement.spacedBy(space.s2)) {
            val groupLabel = stringResource(R.string.ui_group)
            detail.members.forEach { NodeTile(it, current = it.name == group.tcp.name, groupLabel = groupLabel) }
        }
    }
}
