package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportActions
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.feature.report.RvReportScreen
import dev.doona.android.feature.settings.RV_DEBUG_HOURS
import dev.doona.android.feature.settings.RvAboutRoutes
import dev.doona.android.feature.settings.RvAboutScreen
import dev.doona.android.feature.settings.RvIntervalScreen
import dev.doona.android.feature.settings.RvLogLevel
import dev.doona.android.feature.settings.RvLogLevelScreen
import dev.doona.android.feature.settings.RvUpdate
import dev.doona.android.prototype.rv.LICENSES
import dev.doona.android.prototype.rv.REPORT
import dev.doona.android.prototype.rv.data.RvData
import kotlinx.coroutines.delay

/**
 * The 設定 sub-page samples in the preview, reached by route (the `page` or `stack` extra of the sample's activity) or
 * from 設定's rows once they are wired. 語言 is one: 設定's row pushes it, and the preview app draws it over 設定's
 * state, so this file only names its route. 回報問題 is the preview's `report` page ([RvReportSample]); 設定's update
 * check is [rvUpdateSample].
 */
internal object RvSettingsPreviewRoutes {
    const val ABOUT = "about"
    const val LANGUAGE = "language"
    const val INTERVAL = "interval"
    const val LOG_LEVEL = "log-level"
    val all = setOf(ABOUT, LANGUAGE, INTERVAL, LOG_LEVEL)
}

/** The sample at [route], each choosing as the real page would; [push] opens a page over it and [pop] goes back. */
@Composable
internal fun RvSettingsPreview(route: String, push: (String) -> Unit, pop: () -> Unit) {
    when (route.substringBefore(':')) {
        RvSettingsPreviewRoutes.ABOUT -> RvAboutScreen(rvSampleVersions, onBack = pop, routes = rvAboutRoutes(report = { push(REPORT) }, licenses = { push(LICENSES) }))

        RvSettingsPreviewRoutes.INTERVAL -> {
            var hours by rememberSaveable { mutableIntStateOf(RvData.UPDATE_HOURS) }
            RvIntervalScreen(hours, { hours = it }, onBack = pop, onCustom = NOT_WIRED)
        }

        else -> LogLevelSample(pop)
    }
}

/** 設定's update check in the sample: where it stands, and the check, which asks for [RV_CHECK_MS] and ends as [outcome] says. */
internal class RvUpdateSample(val update: RvUpdate, val check: () -> Unit)

/**
 * 設定's 檢查更新 (the hero's action and 關於's row): it asks for [RV_CHECK_MS], then ends with a newer release when
 * [outcome] is `new`, with no answer when it is `failed`, else with nothing newer (`settings:new`, `settings:failed`).
 */
@Composable
internal fun rvUpdateSample(outcome: String): RvUpdateSample {
    var update by remember { mutableStateOf<RvUpdate>(RvUpdate.Idle) }
    LaunchedEffect(update) {
        if (update != RvUpdate.Checking) return@LaunchedEffect
        delay(RV_CHECK_MS)
        update = when (outcome) {
            NEW -> RvUpdate.Available(RV_SAMPLE_RELEASE)
            FAILED -> RvUpdate.Failed
            else -> RvUpdate.Latest
        }
    }
    return RvUpdateSample(update) { update = RvUpdate.Checking }
}

/**
 * 日誌等級: 除錯, once chosen, returns to 資訊 by itself after [RV_DEBUG_HOURS], as the core does; choosing it again
 * starts the hour again.
 */
@Composable
private fun LogLevelSample(pop: () -> Unit) {
    var level by rememberSaveable { mutableStateOf(RvLogLevel.Info) }
    // Each choice of 除錯 starts the hour again, so the effect is keyed on the choice, not only on the level.
    var chosen by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(level, chosen) {
        if (level != RvLogLevel.Debug) return@LaunchedEffect
        delay(RV_DEBUG_HOURS * HOUR_MS)
        level = RvLogLevel.Info
    }
    val select = { next: RvLogLevel ->
        if (next == RvLogLevel.Debug) chosen++
        level = next
    }
    RvLogLevelScreen(level, select, onBack = pop)
}

/**
 * 回報問題: the report is written for [RV_BUILD_MS] when the page opens, again on 重試 and on each turn of a
 * switch, failed or not, as the real page writes it with the redaction it shows; with [failed] the first build fails.
 * A write hides what the switches said when it began and says so in its [RvReportBuild.Ready]: a switch turned as it
 * ends leaves it stale, and it is written again.
 */
@Composable
internal fun RvReportSample(failed: Boolean, pop: () -> Unit) {
    var redaction by remember { mutableStateOf(RvRedaction()) }
    var build by remember { mutableStateOf<RvReportBuild>(if (failed) RvReportBuild.Failed else RvReportBuild.Building) }
    val current = build
    val wanted = redaction
    val stale = current is RvReportBuild.Ready && current.redaction != wanted
    LaunchedEffect(wanted, current == RvReportBuild.Building || stale) {
        if (current != RvReportBuild.Building && !stale) return@LaunchedEffect
        delay(RV_BUILD_MS)
        build = RvReportBuild.Ready(wanted)
    }
    val actions = rvReportActions(retry = { build = RvReportBuild.Building })
    val turn = { next: RvRedaction ->
        redaction = next
        build = RvReportBuild.Building
    }
    RvReportScreen(build, rvSampleReport, redaction, turn, onBack = pop, actions = actions)
}

/** How long the sample's update check asks before it answers. */
internal const val RV_CHECK_MS = 1_200L

/** How long the sample's report takes to write. */
internal const val RV_BUILD_MS = 1_500L

private const val HOUR_MS = 3_600_000L
private const val NEW = "new"
private const val FAILED = "failed"
