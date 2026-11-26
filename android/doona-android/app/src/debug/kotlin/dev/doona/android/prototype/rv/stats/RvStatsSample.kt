package dev.doona.android.prototype.rv.stats

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.rvScorePick
import dev.doona.android.feature.stats.RvStatsRange
import dev.doona.android.feature.stats.RvStatsScore
import dev.doona.android.feature.stats.RvStatsScoreGroup
import dev.doona.android.feature.stats.RvStatsScreen
import dev.doona.android.feature.stats.RvStatsState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.policy.RvScoreSample

/** The 統計 page as [RvData] has it at 10:24: today so far, both subscriptions, every node, the current one selected. */
internal fun rvStatsSample(score: RvStatsScore? = null) = RvStatsState(
    range = RvStatsRange.Today,
    today = RvData.today,
    yesterdaySoFar = RvData.YESTERDAY_BY_NOW,
    subscriptions = RvData.subscriptions,
    nodes = RvData.nodes,
    selectedId = RvData.CURRENT_ID,
    split = RvData.split,
    nowEpochMs = RvData.nowEpochMs,
    nowHour = RvData.nowHour,
    score = score,
    daily = true,
)

/** The same page before the first byte of the day, with no subscription quota and no test run yet. */
internal fun rvStatsQuiet() = RvStatsState(
    range = RvStatsRange.Today,
    today = RvData.quietDay,
    yesterdaySoFar = null,
    subscriptions = emptyList(),
    nodes = RvData.nodes.map { it.untested() },
    selectedId = RvData.CURRENT_ID,
    split = null,
    nowEpochMs = RvData.nowEpochMs,
    nowHour = RvData.nowHour,
    daily = true,
)

/**
 * The Score card's sample: the 香港 region of the group page's samples, TCP verified on its fastest node as there, UDP on
 * the second but still waiting for comparable traffic.
 */
internal fun rvStatsScore(): RvStatsScore {
    val chosen = RvScoreSample.chosen
    val udp = chosen.udp.copy(state = "provisional", waitReason = "comparableTraffic")
    // One group, so the card never shows its name; the sample's name is a string resource, and the id stands in.
    val group = RvStatsScoreGroup(RvScoreSample.group.id, RvScoreSample.group.id, RvScoreSample.group.members, rvScorePick(chosen.tcp), rvScorePick(udp))
    return RvStatsScore(listOf(group))
}

/** The page on a sample [state], its chips and tools not wired (the prototype's, the goldens' and the previews'). */
@Composable
internal fun RvStatsScreenSample(state: RvStatsState, onBack: () -> Unit = {}) =
    RvStatsScreen(state, onBack, onSubscription = {}, onRange = {}, NOT_WIRED, NOT_WIRED, NOT_WIRED)

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 2200)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 2200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvStatsScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvStatsScreenSample(rvStatsSample()) }
}

@Preview(name = "Quiet", locale = "zh-rTW", widthDp = 412, heightDp = 2200)
@Composable
private fun RvStatsQuietPreview() {
    RvTheme(rvSystemScheme(false), false) { RvStatsScreenSample(rvStatsQuiet()) }
}
