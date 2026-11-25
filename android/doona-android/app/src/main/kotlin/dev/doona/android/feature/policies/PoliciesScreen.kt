package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaBarAction
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaChevron
import dev.doona.android.designsystem.components.DoonaEmptyState
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaLatency
import dev.doona.android.designsystem.components.DoonaNodeName
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTabs
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaProgressSize
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaSmallStatusLight
import dev.doona.android.designsystem.components.DoonaTag
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.lineHeightOf
import dev.doona.android.designsystem.rv.Tone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.feature.messageRes
import dev.doona.android.icons.R as IconsR

/** The 策略 entry: builds the ViewModel and collects its state while the screen is started. */
@Composable
fun PoliciesRoute(engine: Engine, onOpenGroup: (String) -> Unit, onOpenSubscriptions: () -> Unit) {
    val viewModel: PoliciesViewModel = viewModel(factory = PoliciesViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PoliciesScreen(state, viewModel::onAction, onOpenGroup, onOpenSubscriptions)
}

/**
 * 策略 (doona `src/features/policies/Policies.tsx`): the kind filter, then one card per group; the bar tests every
 * group and opens the subscriptions. With no group yet, which means no subscription, it offers adding one.
 */
@Composable
fun PoliciesScreen(state: PoliciesUiState, onAction: (PoliciesAction) -> Unit, onOpenGroup: (String) -> Unit, onOpenSubscriptions: () -> Unit) {
    val space = DoonaTheme.spacing
    DoonaPage(
        actions = listOf(
            DoonaBarAction(IconsR.drawable.sym_schedule, stringResource(R.string.policy_test_all)) { onAction(PoliciesAction.TestAll) },
            DoonaBarAction(IconsR.drawable.sym_link, stringResource(R.string.sub_title), onOpenSubscriptions),
        ),
    ) {
        DoonaPageTitle(stringResource(R.string.nav_policies))
        DoonaGap(space.afterTitle)
        DoonaPageTabs(listOf(stringResource(R.string.nav_policies), stringResource(R.string.nav_nodes), stringResource(R.string.nav_rules)), selected = 0)
        DoonaGap(space.section)
        when {
            !state.ready -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DoonaProgress() }

            state.items.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
                ErrorBanner(state, onAction)
                DoonaEmptyState(
                    icon = IconsR.drawable.sym_share,
                    title = stringResource(R.string.policy_empty_title),
                    line = stringResource(R.string.policy_empty_line),
                    actionLabel = stringResource(R.string.sub_add),
                    onAction = onOpenSubscriptions,
                )
            }

            else -> {
                FilterBar(state, onAction)
                DoonaGap(space.section)
                Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
                    ErrorBanner(state, onAction)
                    state.visible.forEach { item -> GroupCard(item) { onOpenGroup(item.group.id) } }
                }
            }
        }
    }
}

@Composable
private fun ErrorBanner(state: PoliciesUiState, onAction: (PoliciesAction) -> Unit) {
    state.error?.let { code ->
        DoonaErrorBanner(stringResource(code.messageRes()), stringResource(R.string.ui_dismiss), { onAction(PoliciesAction.DismissError) })
    }
}

@Composable
private fun FilterBar(state: PoliciesUiState, onAction: (PoliciesAction) -> Unit) {
    DoonaSegmented(
        listOf(
            stringResource(R.string.ui_all_count, state.items.size),
            stringResource(R.string.policy_kind_manual, state.manual),
            stringResource(R.string.policy_kind_auto, state.items.size - state.manual),
        ),
        selected = state.filter.ordinal,
        onSelect = { onAction(PoliciesAction.SetFilter(GroupFilter.entries[it])) },
    )
}

@Composable
private fun kindLabel(kind: GroupKind): String = stringResource(
    when (kind) {
        GroupKind.Selector -> R.string.policy_kind_selector
        GroupKind.UrlTest -> R.string.policy_kind_urltest
        GroupKind.LoadBalance -> R.string.policy_kind_loadbalance
        GroupKind.Score -> R.string.policy_kind_score
        GroupKind.Fallback -> R.string.policy_kind_fallback
    },
)

/**
 * Group heading facts: kind tag, then the available, untested and failed counts as small lights (§7, DoonaTag,
 * Status light). A count of none is left out, so a group that was never tested says only that.
 */
@Composable
internal fun GroupFacts(group: Group) {
    val healthy = group.members.count { it.latencyMs != null && !it.failed }
    val untested = group.members.count { it.latencyMs == null && !it.failed }
    val failed = group.members.count { it.failed }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        DoonaTag(kindLabel(group.kind))
        DoonaSmallStatusLight(Tone.Ok, stringResource(R.string.policy_healthy, healthy))
        if (untested > 0) DoonaSmallStatusLight(Tone.Neutral, stringResource(R.string.policy_untested, untested))
        if (failed > 0) DoonaSmallStatusLight(Tone.Err, stringResource(R.string.policy_failed_count, failed))
    }
}

/** The folded group card: name and facts, then the current member with its latency. Tapping opens the group's page. */
@Composable
private fun GroupCard(item: GroupItem, onOpen: () -> Unit) {
    val c = DoonaTheme.colors
    val group = item.group
    DoonaCard(
        Modifier.fillMaxWidth(),
        onClick = onOpen,
        contentPadding = PaddingValues(DoonaTheme.spacing.cardInset),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
                DoonaText(group.name, style = DoonaTheme.type.blockTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                GroupFacts(group)
            }
            DoonaHGap(DoonaTheme.spacing.block)
            // The chevron is centred on the title's first line.
            Box(Modifier.height(lineHeightOf(DoonaTheme.type.blockTitle)), contentAlignment = Alignment.Center) {
                DoonaChevron(color = c.textSecondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CurrentSummary(item.current, DoonaTheme.type.label, Modifier.weight(1f))
            DoonaHGap(DoonaTheme.spacing.block)
            // A probe is running: the figure beside the spinner is the last one, until the new one arrives.
            if (group.testing) {
                DoonaProgress(size = DoonaProgressSize.Inline)
                DoonaHGap(DoonaTheme.spacing.item)
            }
            NodeLatency(item.node)
        }
    }
}

/** `目前` and the current member. */
@Composable
internal fun CurrentSummary(current: Member?, style: TextStyle, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DoonaText(stringResource(R.string.policy_current), style = style, color = DoonaTheme.colors.text, maxLines = 1, softWrap = false)
        DoonaHGap(DoonaTheme.spacing.block)
        DoonaNodeName(current?.name ?: "—", style, modifier = Modifier.weight(1f, fill = false))
    }
}

/** A node's latency; a node whose last probe failed says so, where the latency would show a dash that an untested one shows too. */
@Composable
internal fun NodeLatency(node: Member?, modifier: Modifier = Modifier) {
    if (node?.failed == true) {
        DoonaSmallStatusLight(Tone.Err, stringResource(R.string.policy_failed), modifier, style = DoonaTheme.type.latency)
    } else {
        DoonaLatency(node?.latencyMs, modifier)
    }
}
