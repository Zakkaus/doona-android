package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.doona.android.prototype.rv.LICENSES
import dev.doona.android.prototype.rv.data.RvData
import kotlinx.coroutines.delay

/**
 * The 設定 sub-page samples in the preview, reached by route only (the `page` or `stack` extra of the sample's
 * activity): 設定's rows lead to them once the owner has picked 關於's layout and the rows are wired, so 設定 itself
 * does not change here. `about:new` and `about:failed` end 關於's check with a newer release or no answer (else nothing
 * newer); `report-github:failed` opens 回報問題 after a failed build.
 */
internal object RvSettingsPreviewRoutes {
    const val ABOUT = "about"
    const val LANGUAGE = "language"
    const val INTERVAL = "interval"
    const val LOG_LEVEL = "log-level"
    const val REPORT_GITHUB = "report-github"
    val all = setOf(ABOUT, LANGUAGE, INTERVAL, LOG_LEVEL, REPORT_GITHUB)
}

/** The sample at [route], each choosing as the real page would; [push] opens a page over it and [pop] goes back. */
@Composable
internal fun RvSettingsPreview(route: String, push: (String) -> Unit, pop: () -> Unit) {
    val state = route.substringAfter(':', "")
    when (route.substringBefore(':')) {
        RvSettingsPreviewRoutes.ABOUT -> AboutSample(state, push, pop)

        RvSettingsPreviewRoutes.LANGUAGE -> {
            var selected by rememberSaveable { mutableStateOf(RvLanguage.TraditionalChinese) }
            RvLanguageScreen(selected, RvLanguage.TraditionalChinese, { selected = it }, onBack = pop)
        }

        RvSettingsPreviewRoutes.INTERVAL -> {
            var hours by rememberSaveable { mutableIntStateOf(RvData.UPDATE_HOURS) }
            RvIntervalScreen(hours, { hours = it }, onBack = pop)
        }

        RvSettingsPreviewRoutes.LOG_LEVEL -> LogLevelSample(pop)

        else -> ReportSample(failed = state == FAILED, pop)
    }
}

/** 關於 doona: 檢查更新 asks for [RV_CHECK_MS], then ends as [state] says; 回報問題 and the licences open their pages. */
@Composable
private fun AboutSample(state: String, push: (String) -> Unit, pop: () -> Unit) {
    var update by remember { mutableStateOf<RvUpdate>(RvUpdate.Idle) }
    LaunchedEffect(update) {
        if (update != RvUpdate.Checking) return@LaunchedEffect
        delay(RV_CHECK_MS)
        update = when (state) {
            NEW -> RvUpdate.Available(RV_SAMPLE_RELEASE)
            FAILED -> RvUpdate.Failed
            else -> RvUpdate.Latest
        }
    }
    val routes = RvAboutRoutes(
        report = { push(RvSettingsPreviewRoutes.REPORT_GITHUB) },
        checkUpdate = { update = RvUpdate.Checking },
        licenses = { push(LICENSES) },
    )
    RvAboutScreen(rvSampleVersions, onBack = pop, routes = routes, update = update)
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
 * 回報問題 for GitHub: the report is written for [RV_BUILD_MS] when the page opens, again on 重試 and on each turn of a
 * switch, failed or not, as the real page writes it with the redaction it shows; with [failed] the first build fails.
 * A write hides what the switches said when it began and says so in its [RvReportBuild.Ready]: a switch turned as it
 * ends leaves it stale, and it is written again.
 */
@Composable
private fun ReportSample(failed: Boolean, pop: () -> Unit) {
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
    val actions = RvReportActions(retry = { build = RvReportBuild.Building })
    val turn = { next: RvRedaction ->
        redaction = next
        build = RvReportBuild.Building
    }
    RvReportGithubScreen(build, rvSampleReport, redaction, turn, onBack = pop, actions = actions)
}

/** How long the sample's update check asks before it answers. */
internal const val RV_CHECK_MS = 1_200L

/** How long the sample's report takes to write. */
internal const val RV_BUILD_MS = 1_500L

private const val HOUR_MS = 3_600_000L
private const val NEW = "new"
private const val FAILED = "failed"
