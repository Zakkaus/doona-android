package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaButtonStyle
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaNodeTile
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaProgress
import dev.doona.android.designsystem.components.DoonaTag
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.messageRes
import dev.doona.android.icons.R as IconsR

/**
 * A group's page, pushed over the list. A group removed while its page is open, such as by a subscription update, leaves
 * the page. Before the engine has loaded, as after process death, a missing group is only not loaded yet.
 */
@Composable
fun GroupRoute(engine: Engine, groupId: String, onBack: () -> Unit, onOpenGroup: (String) -> Unit) {
    val viewModel: OldGroupViewModel = viewModel(factory = OldGroupViewModel.factory(engine, groupId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val detail = state.detail
    when {
        detail != null -> GroupScreen(detail, state.error, viewModel::onAction, onOpenGroup, onBack)
        state.ready -> LaunchedEffect(Unit) { onBack() }
        else -> GroupLoading(onBack)
    }
}

/** The page's frame over progress: the engine has not loaded, so the group is not known to exist yet. */
@Composable
private fun GroupLoading(onBack: () -> Unit) {
    DoonaPage(onBack = onBack) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DoonaProgress() }
    }
}

/**
 * A group's own page, pushed over the list. doona opens a group in place on its card; on a phone the open group then
 * fills the screen, so here it is a page of its own with a back arrow. It shows what the group uses now, the actions
 * that change it (test, and back to the group's own choice when the user has pinned one), the groups inside it, which
 * open their own page, and every member. Tapping a member pins it.
 */
@Composable
fun GroupScreen(detail: GroupDetail, error: ErrorCode?, onAction: (OldGroupAction) -> Unit, onOpenGroup: (String) -> Unit, onBack: () -> Unit) {
    val space = DoonaTheme.spacing
    DoonaPage(onBack = onBack) {
        DoonaPageTitle(detail.group.name)
        DoonaGap(space.item)
        GroupFacts(detail.group)
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            error?.let { code ->
                DoonaErrorBanner(stringResource(code.messageRes()), stringResource(R.string.ui_dismiss), { onAction(OldGroupAction.DismissError) })
            }
            CurrentRow(detail)
            Actions(detail, onAction)
        }
        DoonaGap(space.section)
        if (detail.subGroups.isNotEmpty()) {
            SubGroups(detail, onOpenGroup)
            DoonaGap(space.block)
        }
        Members(detail, onAction)
    }
}

/** What the group uses now and how fast it is; an automatic group the user has pinned says so. */
@Composable
private fun CurrentRow(detail: GroupDetail) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CurrentSummary(detail.current, DoonaTheme.type.label, Modifier.weight(1f))
        DoonaHGap(DoonaTheme.spacing.block)
        if (detail.canReturnToAutomatic) {
            DoonaTag(stringResource(R.string.policy_pinned))
            DoonaHGap(DoonaTheme.spacing.block)
        }
        NodeLatency(detail.currentNode)
    }
}

@Composable
private fun Actions(detail: GroupDetail, onAction: (OldGroupAction) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
        DoonaButton(
            stringResource(R.string.policy_test_group),
            { onAction(OldGroupAction.Test) },
            style = DoonaButtonStyle.Secondary,
            icon = IconsR.drawable.sym_schedule,
            busy = detail.group.testing,
        )
        if (detail.canReturnToAutomatic) {
            DoonaButton(stringResource(R.string.policy_unpin), { onAction(OldGroupAction.ReturnToAutomatic) }, style = DoonaButtonStyle.Text)
        }
    }
}

/** The groups inside this one, as chips that open them; a member group is pinned from its tile, not from here. */
@Composable
private fun SubGroups(detail: GroupDetail, onOpenGroup: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
        DoonaText(stringResource(R.string.policy_groups_inside), style = DoonaTheme.type.footnote, color = DoonaTheme.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
            detail.subGroups.forEach { DoonaTag(it.name, onClick = { onOpenGroup(it.id) }) }
        }
    }
}

@Composable
private fun Members(detail: GroupDetail, onAction: (OldGroupAction) -> Unit) {
    val groupLabel = stringResource(R.string.ui_group)
    val failed = stringResource(R.string.policy_failed)
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item)) {
        detail.members.forEach { item ->
            val member = item.member
            val node = item.node
            DoonaNodeTile(
                name = member.name,
                latencyMs = node?.latencyMs?.takeUnless { node.failed },
                current = member.id == detail.group.selectedId,
                groupLabel = groupLabel.takeIf { member.isGroup },
                onClick = { onAction(OldGroupAction.Select(member.id)) },
                source = failed.takeIf { node?.failed == true },
            )
        }
    }
}
