package dev.doona.android.prototype.rv.samples

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.policies.RvApplyChoice
import dev.doona.android.feature.policies.RvApplyTo
import dev.doona.android.feature.policies.RvGroupActions
import dev.doona.android.feature.policies.RvGroupContent
import dev.doona.android.feature.policies.RvGroupFilter
import dev.doona.android.feature.policies.RvGroupPage
import dev.doona.android.prototype.rv.data.RvData

/**
 * The s1125d sample of the selector group's page: [RvGroupPage] on [sample] with the page's taps kept here, as the owner
 * approved it. [apart] is how the 分開選擇 switch starts; with [score] the group is a Score group. [query], [source] (a
 * subscription's id) and [order] are what the search field and the two chips hold to begin with.
 */
@Composable
internal fun RvGroupFullPage(
    sample: RvGroupFullSample.Sample,
    apart: Boolean,
    modifier: Modifier = Modifier,
    score: Boolean = false,
    query: String = "",
    source: String? = null,
    order: RvNodeSort = RvNodeSort.Latency,
) {
    var choice by remember { mutableStateOf(RvApplyChoice(sample.tcpId, if (apart) sample.udpId else sample.tcpId, apart)) }
    var apply by remember { mutableStateOf(RvApplyTo.Tcp) }
    RvGroupPage(
        RvGroupContent(sample.group, sample.subscriptions, choice, apply, score, RvData.nowEpochMs),
        RvGroupActions(
            tap = { if (!score) choice = choice.tapped(it, apply) },
            apart = { choice = choice.apart(it) },
            apply = { apply = it },
        ),
        modifier,
        filter = RvGroupFilter(query, source, order),
    )
}
