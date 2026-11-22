package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import dev.doona.android.designsystem.components.DoonaBadge
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaIconAction
import dev.doona.android.designsystem.components.DoonaLatency
import dev.doona.android.designsystem.components.DoonaNodeName
import dev.doona.android.designsystem.components.DoonaNodeTile
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTabs
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaPicker
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaStatusLight
import dev.doona.android.designsystem.components.DoonaSwitch
import dev.doona.android.designsystem.components.DoonaTagChip
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member

@Composable
private fun kindLabel(kind: GroupKind): String = stringResource(
    when (kind) {
        GroupKind.Selector -> R.string.policy_kind_selector
        GroupKind.UrlTest -> R.string.policy_kind_urltest
        GroupKind.Fallback -> R.string.policy_kind_fallback
    },
)

private val Group.current: Member? get() = members.firstOrNull { it.id == selectedId }

/** 策略 (doona `src/features/policies/Policies.tsx`): the kind filter, then one card per group. */
@Composable
fun PoliciesScreen(groups: List<Group>, onOpen: (String) -> Unit) {
    val space = DoonaTheme.spacing
    var kind by remember { mutableIntStateOf(0) }
    val manual = groups.count { it.kind.manual }
    DoonaPage(
        actions = {
            DoonaIconAction(R.drawable.ic_add_circle, stringResource(R.string.group_new_group))
            DoonaIconAction(R.drawable.ic_search, stringResource(R.string.ui_search))
            DoonaIconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options))
        },
    ) {
        DoonaPageTitle(stringResource(R.string.nav_policies))
        DoonaGap(space.afterTitle)
        DoonaPageTabs(listOf(stringResource(R.string.nav_policies), stringResource(R.string.nav_nodes), stringResource(R.string.nav_rules)), selected = 0)
        DoonaGap(space.section)
        DoonaSegmented(
            listOf(
                stringResource(R.string.ui_all_count, groups.size),
                stringResource(R.string.policy_kind_manual, manual),
                stringResource(R.string.policy_kind_auto, groups.size - manual),
            ),
            selected = kind,
            onSelect = { kind = it },
        )
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            groups.forEach { GroupCard(it) { onOpen(it.id) } }
        }
    }
}

/** Group heading facts: kind badge, then the available and untested counts as small lights (§7, DoonaBadge, Status light). */
@Composable
private fun GroupFacts(group: Group) {
    val healthy = group.members.count { it.latencyMs != null && !it.failed }
    val untested = group.members.count { it.latencyMs == null && !it.failed }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        DoonaBadge(kindLabel(group.kind))
        DoonaStatusLight(Tone.Ok, stringResource(R.string.policy_healthy, healthy), small = true)
        if (untested > 0) DoonaStatusLight(Tone.Neutral, stringResource(R.string.policy_untested, untested), small = true)
    }
}

/** The folded group card: name and facts, then the current member with its latency. Tapping opens the group's page. */
@Composable
private fun GroupCard(group: Group, onOpen: () -> Unit) {
    val c = DoonaTheme.colors
    DoonaCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
                DoonaText(group.name, style = DoonaTheme.type.cardTitle, color = c.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GroupFacts(group)
            }
            DoonaHGap(DoonaTheme.spacing.block)
            DoonaIcon(R.drawable.ic_chevron_right, c.text.secondary, size = DoonaTheme.size.iconSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CurrentSummary(group, DoonaTheme.type.label, Modifier.weight(1f))
            DoonaHGap(DoonaTheme.spacing.block)
            DoonaLatency(group.current?.latencyMs)
        }
    }
}

/** `目前` and the current member. */
@Composable
private fun CurrentSummary(group: Group, style: TextStyle, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DoonaText(stringResource(R.string.policy_current), style = style, color = c.text.primary, maxLines = 1, softWrap = false)
        DoonaHGap(DoonaTheme.spacing.block)
        DoonaNodeName(group.current?.name ?: "—", style, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * A group's own page, pushed over the list. doona opens a group in place on its card; on a phone the open group
 * then fills the screen, so here it is a page of its own with a back arrow, and its members are not framed twice.
 */
@Composable
fun GroupScreen(group: Group, onBack: () -> Unit) {
    val space = DoonaTheme.spacing
    val c = DoonaTheme.colors
    // doona's default order: by latency, untested last.
    val members = group.members.sortedBy { it.latencyMs ?: Int.MAX_VALUE }
    var net by remember { mutableIntStateOf(0) }
    var aliveOnly by remember { mutableStateOf(false) }
    DoonaPage(
        onBack = onBack,
        actions = {
            DoonaIconAction(R.drawable.ic_search, stringResource(R.string.policy_filter))
            DoonaIconAction(R.drawable.ic_edit, stringResource(R.string.policy_edit))
            DoonaIconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options))
        },
    ) {
        DoonaPageTitle(group.name)
        DoonaGap(space.item)
        GroupFacts(group)
        DoonaGap(space.section)
        CurrentSummary(group, DoonaTheme.type.label)
        DoonaGap(space.block)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(space.item)) {
            group.members.filter { it.isGroup }.forEach { DoonaTagChip(it.name) }
        }
        DoonaGap(space.tight)
        DoonaText(stringResource(R.string.group_every_node), style = DoonaTheme.type.caption, color = c.text.secondary)
        DoonaGap(space.section)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DoonaSegmented(
                listOf(stringResource(R.string.policy_both), stringResource(R.string.ui_tcp), stringResource(R.string.ui_udp)),
                selected = net,
                onSelect = { net = it },
                modifier = Modifier.weight(1f),
            )
            DoonaHGap(space.block)
            DoonaText(
                pluralStringResource(R.plurals.policy_members, members.size, members.size),
                style = DoonaTheme.type.body.copy(fontFeatureSettings = DoonaTheme.type.tabular),
                color = c.text.secondary,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.block)) {
            DoonaPicker(stringResource(R.string.policy_all_regions), onClick = {})
            DoonaPicker(stringResource(R.string.policy_by_latency), onClick = {}, filled = true)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            DoonaText(stringResource(R.string.policy_alive_only), style = DoonaTheme.type.body, color = c.text.primary, maxLines = 1, modifier = Modifier.weight(1f))
            DoonaHGap(space.block)
            DoonaSwitch(aliveOnly, { aliveOnly = it }, stringResource(R.string.policy_alive_only))
        }
        DoonaGap(space.block)
        Column(verticalArrangement = Arrangement.spacedBy(space.item)) {
            val groupLabel = stringResource(R.string.ui_group)
            members.forEach { DoonaNodeTile(it.name, it.latencyMs, current = it.id == group.selectedId, groupLabel = groupLabel.takeIf { _ -> it.isGroup }) }
        }
    }
}
