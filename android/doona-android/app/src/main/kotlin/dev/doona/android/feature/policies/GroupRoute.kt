package dev.doona.android.feature.policies

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvTesting
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonHeader
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.engine.Engine
import dev.doona.android.feature.messageRes

/**
 * A group's page, pushed over 策略: builds the ViewModel and draws [RvGroupPage] from its state while the screen is
 * started. A group removed while its page is open, such as by a subscription update, leaves the page; before the engine
 * has loaded, as after process death, the group is only not read yet and the page is a skeleton under its name; a
 * first read that fails shows its banner with 重試 under the name instead.
 * [onNodeDetail] opens a node's detail (a wiring point: the 節點 lane's page). [onOpenGroup] is what DoonaApp still
 * passes until p4-handoff-doonaapp.patch drops it: a member group is a row a tap chooses, and its own page opens from
 * 策略's groups card, so this page opens none.
 */
@Composable
fun GroupRoute(
    engine: Engine,
    groupId: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenGroup: (String) -> Unit = {},
    onNodeDetail: (String) -> Unit = {},
) {
    val viewModel: GroupViewModel = viewModel(factory = GroupViewModel.factory(engine, groupId))
    GroupRoute(viewModel, groupId, onBack, onNodeDetail)
}

/** A group's page on a given [viewModel]: the page as [GroupRoute] draws it, for a caller (a test) that makes its own. */
@Composable
internal fun GroupRoute(viewModel: GroupViewModel, groupId: String, onBack: () -> Unit, onNodeDetail: (String) -> Unit = {}) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val group = ui.group
    val choice = ui.choice
    val failure = ui.failure
    when {
        group != null && choice != null -> CompositionLocalProvider(LocalRvTesting provides ui.testing) {
            RvGroupPage(
                RvGroupContent(group, ui.subscriptions, choice, ui.apply, ui.isScore, ui.nowEpochMs),
                RvGroupActions(onBack, viewModel::onTap, onNodeDetail, viewModel::onApart, viewModel::onApply, viewModel::onTest),
                banner = failure?.let { groupBanner(it, viewModel::retry) },
            )
        }

        failure?.action == GroupAction.Read -> GroupUnread(groupId.removePrefix(GROUP_ID_PREFIX), onBack, groupBanner(failure, viewModel::retry))

        ui.loading -> GroupSkeleton(groupId.removePrefix(GROUP_ID_PREFIX), onBack)

        else -> LaunchedEffect(Unit) { onBack() }
    }
}

/** The page before the group is read: its name (a group's id is `g-` and its name), 使用中, and rows of nodes. */
@Composable
private fun GroupSkeleton(title: String, onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets)
        RvGutter {
            RvSkeletonCard {
                RvSkeletonHeader(stringResource(R.string.rv_nodes_in_use))
                RvSkeletonRow(0, RvSkeletonText.Mixed, RvSkeletonEnd.Latency)
            }
            RvSkeletonCard { repeat(SKELETON_ROWS) { RvSkeletonRow(it + 1, RvSkeletonText.Mixed, RvSkeletonEnd.Latency) } }
        }
    }
}

/** The page when its first read failed: the name and [banner], which reads again. */
@Composable
private fun GroupUnread(title: String, onBack: () -> Unit, banner: RvNotice) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvPageHeader(title, titleBlock, insets)
        RvGutter(banner = banner) {}
    }
}

/** The banner of a failed [failure]: what failed over the engine's reason, and 重試 ([onRetry]). */
@Composable
private fun groupBanner(failure: GroupFailure, onRetry: () -> Unit) = RvNotice(
    RvNoticeKind.Error,
    stringResource(failure.action.title),
    stringResource(failure.code.messageRes()),
    stringResource(R.string.rv_retry),
    onRetry,
)

/** What a failed action's banner is titled, as 策略's and the picker's failures are (pv-d M8). */
@get:StringRes
private val GroupAction.title: Int
    get() = when (this) {
        GroupAction.Select, GroupAction.Apart -> R.string.rv_failed_switch_node
        GroupAction.Test -> R.string.rv_failed_test_latency
        GroupAction.Read -> R.string.rv_group_read_failed
    }

private const val GROUP_ID_PREFIX = "g-"
private const val SKELETON_ROWS = 6
