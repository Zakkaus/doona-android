package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.nodes.RvNodeDetail
import dev.doona.android.feature.nodes.RvNodePicker
import dev.doona.android.feature.nodes.RvNodePickerPage
import dev.doona.android.feature.nodes.RvNodesPage
import dev.doona.android.feature.nodes.RvNodesPageState
import dev.doona.android.prototype.rv.data.RvData

/** The page as [RvData] has it. */
internal fun sampleNodesPage() = RvNodesPageState(RvData.nodes, setOf(RvData.CURRENT_ID))

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
) = RvNodePicker(nodes, subscriptions, currentId, RvData.nowEpochMs, onPick, onDetail, onClose, section, stretch, searchPage, title, banner, caption, onAddNode = {})

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
) = RvNodePickerPage(nodes, subscriptions, currentId, sort, onSort, onPick, onBack, onTest, onSearch, RvData.nowEpochMs, onAdd, onAddNode = {})

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodeDetailPreview() {
    val dark = isSystemInDarkTheme()
    val page = sampleNodesPage()
    RvTheme(rvSystemScheme(dark), dark) {
        RvNodeDetail(page.nodes.first { it.id == RV_NODES_DETAIL }, current = false, RvData.testTimes, onBack = {}, onUse = {}, onTest = {})
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodePickerPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodePickerSample(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}) }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodePickerPagePreview() {
    val dark = isSystemInDarkTheme()
    var current by remember { mutableStateOf(RvData.CURRENT_ID) }
    var sort by remember { mutableStateOf(RvNodeSort.Latency) }
    RvTheme(rvSystemScheme(dark), dark) {
        RvNodePickerPageSample(RvData.nodes, RvData.subscriptions, current, sort, { sort = it }, { current = it }, onBack = {}, onTest = {}, onSearch = {})
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodesPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodesPage(sampleNodesPage(), onBack = {}, onOpen = {}, onAllNodes = {}, onAddNode = {}) }
}
