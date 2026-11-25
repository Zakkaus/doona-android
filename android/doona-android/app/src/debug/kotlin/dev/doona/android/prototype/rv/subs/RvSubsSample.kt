package dev.doona.android.prototype.rv.subs

import androidx.compose.runtime.Composable
import dev.doona.android.feature.model.RvSubscription
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
) = RvSubDetail(sub, state, onChange, onBack, onUpdate, onUserAgent, RvData.UPDATE_HOURS)

/** 新增訂閱 on the sample: what came in counted over [RvData]'s nodes. The page takes them as a parameter. */
@Composable
internal fun RvSubAddSample(
    state: RvAddState,
    onChange: (RvAddState) -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
    onLanConfirm: () -> Unit = {},
) = RvSubAdd(state, onChange, onPaste, onImport, onBack, onLanConfirm, RvData.nodes)
