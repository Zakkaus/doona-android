package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.prototype.rv.data.RvData

/** The page as [RvData] has it. */
internal fun sampleNodesPage() = RvNodesPageState(RvData.nodes, RvData.CURRENT_ID, RvData.testTimes)

/** The node the detail page's golden shows: sg-03, whose last tests slow down steadily. */
internal const val RV_NODES_DETAIL = "sg-03"

/** The pull-down picker on the sample: the subscriptions' end dates as of [RvData]'s now. The page takes it as a parameter. */
@Composable
internal fun RvNodePickerSample(
    nodes: List<RvNode>,
    subscriptions: List<RvSubscription>,
    currentId: String,
    onPick: (String) -> Unit,
    onDetail: (String) -> Unit,
    onClose: () -> Unit,
    section: (Int) -> Modifier = { Modifier },
    stretch: () -> Float = { 0f },
    searchPage: Boolean = false,
    title: String = stringResource(R.string.rv_nodes_title),
    banner: RvNotice? = null,
    caption: String = stringResource(R.string.rv_main_node_hint),
) = RvNodePicker(nodes, subscriptions, currentId, RvData.nowEpochMs, onPick, onDetail, onClose, section, stretch, searchPage, title, banner, caption)

/** The main node's picker on the sample: the subscriptions' end dates as of [RvData]'s now. The page takes it as a parameter. */
@Composable
internal fun RvNodePickerPageSample(
    nodes: List<RvNode>,
    subscriptions: List<RvSubscription>,
    currentId: String,
    sort: RvNodeSort,
    onSort: (RvNodeSort) -> Unit,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onTest: () -> Unit,
    onSearch: () -> Unit,
    onAdd: () -> Unit = {},
) = RvNodePickerPage(nodes, subscriptions, currentId, sort, onSort, onPick, onBack, onTest, onSearch, RvData.nowEpochMs, onAdd)
