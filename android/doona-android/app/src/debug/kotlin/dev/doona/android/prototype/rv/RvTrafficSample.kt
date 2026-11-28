package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvSession
import dev.doona.android.feature.traffic.RV_TRAFFIC_LIVE
import dev.doona.android.feature.traffic.RV_TRAFFIC_POINTS
import dev.doona.android.feature.traffic.RV_TRAFFIC_RANGE_SECONDS
import dev.doona.android.feature.traffic.RvTrafficChart
import dev.doona.android.feature.traffic.RvTrafficScreen
import dev.doona.android.feature.traffic.RvTrafficSession
import dev.doona.android.feature.traffic.RvTrafficState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvSessionSeconds

/**
 * 流量詳情 from the sample, holding the range the page reports: [activity]'s rates, its minute as the live 1 分, and
 * [session]'s seconds ([RvSessionSeconds]) as the 5 分, 15 分 and 1 小時 windows ending at the session's now.
 */
@Composable
internal fun RvTrafficSample(activity: RvActivitySampleState, session: RvSession, onBack: () -> Unit, initialScrub: Int? = null) {
    var range by remember { mutableIntStateOf(RV_TRAFFIC_LIVE) }
    val seconds = remember(session) { RvSessionSeconds(session) }
    RvTrafficScreen(rvSampleTraffic(activity, session, seconds, range), onRange = { range = it }, onBack = onBack, initialScrub = initialScrub)
}

/** The page's state for [range] (an index of [RV_TRAFFIC_RANGE_SECONDS]), from [activity], [session] and its [seconds]. */
internal fun rvSampleTraffic(activity: RvActivitySampleState, session: RvSession, seconds: RvSessionSeconds, range: Int): RvTrafficState {
    val live = range == RV_TRAFFIC_LIVE
    val chart = RvTrafficChart(
        range = range,
        down = if (live) activity.downHistory else seconds.chart(range, upload = false),
        up = if (live) activity.upHistory else seconds.chart(range, upload = true),
        endEpochMs = session.nowEpochMs,
        stepMs = RV_TRAFFIC_RANGE_SECONDS[range] / RV_TRAFFIC_POINTS * MS_PER_SECOND,
    )
    if (activity.status !is Status.Running) return RvTrafficState(0, 0, sampled = false, chart = chart, session = null)
    val figures = RvTrafficSession(
        peakDown = session.peakDown.toDouble(),
        peakUp = session.peakUp.toDouble(),
        averageDown = session.averageDown,
        averageUp = session.averageUp,
        downTotal = session.downTotal,
        upTotal = session.upTotal,
        seconds = session.seconds,
    )
    return RvTrafficState(activity.downRate, activity.upRate, sampled = true, chart = chart, session = figures)
}

private const val MS_PER_SECOND = 1000L

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvTrafficScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvTrafficSample(rvSampleActivity(), RvData.session, onBack = {}) }
}
