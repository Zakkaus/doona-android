package dev.doona.android.prototype.rv.subs

import androidx.compose.runtime.Composable
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.subscriptions.RvAddPhase
import dev.doona.android.feature.subscriptions.RvAddState
import dev.doona.android.feature.subscriptions.RvProtocolCount
import dev.doona.android.feature.subscriptions.RvSubAdd
import dev.doona.android.feature.subscriptions.RvSubDetail
import dev.doona.android.feature.subscriptions.RvSubDetailState
import dev.doona.android.feature.subscriptions.RvSubsState
import dev.doona.android.prototype.rv.data.RvData

/** 訂閱's state on the sample: [RvData]'s subscriptions unless the caller gives its own, their end dates as of its now. */
internal fun rvSubsStateSample(
    subscriptions: List<RvSubscription> = RvData.subscriptions,
    failedId: String? = null,
    deletingId: String? = null,
) = RvSubsState(subscriptions, RvData.nowEpochMs, failedId, deletingId)

/** A subscription's page on the sample: the interval [RvData] refreshes at. The page takes it as a parameter. */
@Composable
internal fun RvSubDetailSample(
    sub: RvSubscription,
    state: RvSubDetailState,
    onChange: (RvSubDetailState) -> Unit,
    onBack: () -> Unit,
    onUpdate: () -> Unit,
    onUserAgent: (String?) -> Unit = {},
) = RvSubDetail(sub, state, onChange, onBack, onUpdate, onUserAgent, RvData.UPDATE_HOURS, onAutoUpdate = NOT_WIRED)

/**
 * 新增訂閱 on the sample: what came in counted over [RvData]'s nodes, and the local network prompt [lan] in its sheet over
 * the page, as the app shows it over every page; its confirmation runs [onLanConfirm], its other button [onLanDismiss].
 */
@Composable
internal fun RvSubAddSample(
    state: RvAddState,
    onChange: (RvAddState) -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    lan: RvLanPrompt? = null,
    onLanConfirm: () -> Unit = {},
    onLanDismiss: () -> Unit = {},
) {
    val done = state.phase as? RvAddPhase.Done
    RvSubAdd(state, onChange, onPaste, onImport, onBack, done?.let { rvProtocolCounts(RvData.nodes, it.sub.name) }.orEmpty())
    RvLanSheet(lan, onLanConfirm, onLanDismiss)
}

/** The protocols of [subscription]'s nodes among [nodes], most nodes first. */
internal fun rvProtocolCounts(nodes: List<RvNode>, subscription: String): List<RvProtocolCount> =
    nodes.filter { it.subscription == subscription }.groupingBy { it.protocol }.eachCount().entries.sortedByDescending { it.value }
        .map { RvProtocolCount(it.key, it.value) }
