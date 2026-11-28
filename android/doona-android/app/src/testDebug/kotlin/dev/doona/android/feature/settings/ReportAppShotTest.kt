package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.report.ReportFacts
import dev.doona.android.feature.report.ReportPage
import dev.doona.android.feature.report.ReportViewModel
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.feature.report.RvReportScreen
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeApi
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.settings.rvReportActions
import dev.doona.android.prototype.rv.settings.rvSampleReport
import dev.doona.android.report.AppInfo
import dev.doona.android.report.CrashFiles
import dev.doona.android.report.CrashOffer
import dev.doona.android.report.ExportTokens
import dev.doona.android.report.RedactionOptions
import dev.doona.android.report.ReportExport
import dev.doona.android.report.ReportExporter
import dev.doona.android.report.ReportFailure
import dev.doona.android.report.ReportShare
import dev.doona.android.report.ReportZip
import dev.doona.android.report.testFlowEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 回報問題 as the app builds it (`app-rv-report*`): the RV sample engine and honk, through [ReportViewModel], into the page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class ReportAppShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = READY)
    fun reportLight() = compose.app("report", dark = false, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY)
    fun reportDark() = compose.app("report", dark = true, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_FS115)
    fun reportFs115() = compose.app("report", fontScale = RV_FS115, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_FS200)
    fun reportFs200() = compose.app("report", fontScale = RV_FS200, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = BUILDING)
    fun reportBuildingLight() = compose.app("report-building", dark = false, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = BUILDING)
    fun reportBuildingDark() = compose.app("report-building", dark = true, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = BUILDING_FS115)
    fun reportBuildingFs115() = compose.app("report-building", fontScale = RV_FS115, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = BUILDING_FS200)
    fun reportBuildingFs200() = compose.app("report-building", fontScale = RV_FS200, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = FAILED)
    fun reportFailedLight() = compose.app("report-failed", dark = false, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = FAILED)
    fun reportFailedDark() = compose.app("report-failed", dark = true, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = FAILED_FS115)
    fun reportFailedFs115() = compose.app("report-failed", fontScale = RV_FS115, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = FAILED_FS200)
    fun reportFailedFs200() = compose.app("report-failed", fontScale = RV_FS200, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = LINK_FAILED)
    fun reportLinkFailedLight() = compose.app("report-link-failed", dark = false, whole = true, content = reportPage(Built.Ready, tmp, linkFailed = true))

    @Test
    @Config(qualifiers = LINK_FAILED)
    fun reportLinkFailedDark() = compose.app("report-link-failed", dark = true, whole = true, content = reportPage(Built.Ready, tmp, linkFailed = true))

    @Test
    @Config(qualifiers = LINK_FAILED_FS200)
    fun reportLinkFailedFs200() = compose.app("report-link-failed", fontScale = RV_FS200, whole = true, content = reportPage(Built.Ready, tmp, linkFailed = true))
}

/** The English 回報問題, light: the app's golden, and the sample page as the panels' approved column. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class ReportAppEnShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    @Config(qualifiers = READY_EN)
    fun report() = compose.app("report-en", dark = false, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_EN)
    fun reportSample() = compose.sample("report-en") {
        RvReportScreen(RvReportBuild.Ready(RvRedaction()), rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {}, actions = rvReportActions())
    }
}

/**
 * 回報問題 on its ViewModel, with the report [built] as it stands, the ViewModel made before the page is composed;
 * [linkFailed]: 在 GitHub 回報 could not open its link.
 */
private fun reportPage(built: Built, tmp: TemporaryFolder, linkFailed: Boolean = false): @Composable () -> Unit {
    val vm = ReportViewModel(reportEngine(), reportHonk(), ShotReports(built, tmp), SampleFacts, Dispatchers.Unconfined)
    if (linkFailed) vm.linkFailed()
    return { ReportPage(vm, onBack = {}) }
}

/** The RV sample engine with the sample report's two crash files. */
private fun reportEngine(): Engine = rvSampleEngine().apply {
    crashes = listOf(CrashFile("1795300000000.txt", 2_048), CrashFile("1795300100000.txt", 1_024))
}

/** honk on the RV sample, its node list cut to the sample report's node count; its 2 subscriptions and 14 rules as they are. */
private fun reportHonk(): NativeApi = HonkFixtures.rvSample().apply {
    val list = Json.parseToJsonElement(HonkFixtures.body("nodes/rv-sample")).jsonObject
    val nodes = list.getValue("nodes").jsonArray.take(checkNotNull(rvSampleReport.nodes))
    check(nodes.size == rvSampleReport.nodes)
    on("GET", "/api/v1/nodes", 200, JsonObject(list + ("nodes" to JsonArray(nodes))).toString())
}

/** The sample report's app, phone and event count ([rvSampleReport]). */
private object SampleFacts : ReportFacts {
    override val app = rvSampleReport.app
    override val device = rvSampleReport.device

    override suspend fun events() = checkNotNull(rvSampleReport.events)
}

/** Where the report stands when the page is drawn. */
private enum class Built { Ready, Building, Failed }

/**
 * A [ReportExporter] whose export is [built]: a written report (its share over an export in [tmp] that is never sent),
 * one that never finishes, or one that could not be written.
 */
private class ShotReports(private val built: Built, private val tmp: TemporaryFolder) : ReportExporter {
    override val crashOffered = MutableStateFlow(false)

    override fun crashShown() = Unit

    override fun dismissCrash() = Unit

    override suspend fun export(options: RedactionOptions, failure: ReportFailure?): ReportShare? = when (built) {
        Built.Building -> awaitCancellation()
        Built.Failed -> null
        Built.Ready -> ReportShare(android.content.Intent(), ReportZip(tmp.root.resolve("report.zip"), "t", null, ISSUE, checkNotNull(rvSampleReport.events), checkNotNull(rvSampleReport.crashes)), shotExport(tmp))
    }
}

private fun shotExport(tmp: TemporaryFolder): ReportExport {
    val engine = rvSampleEngine()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val files = CrashFiles(tmp.root.resolve("crash"))
    val app = AppInfo("0.1.0", 1, "debug", listOf("arm64-v8a"), "15", 35, "Google", "Pixel 8")
    return ReportExport(
        engine,
        FakeHonk(),
        files,
        CrashOffer(files, tmp.root.resolve("seen")),
        { tmp.root.resolve("reports") },
        ExportTokens { tmp.root.resolve("tokens.json") },
        { app },
        scope,
        Dispatchers.Unconfined,
        LogLevelController({ Result.success(Unit) }, scope) { 0L },
        testFlowEvents(Dispatchers.Unconfined),
        { emptyList() },
    ) { 0L }
}

private const val ISSUE = "https://github.com/Zakkaus/doona-android/issues/new"

/** The English report is longer: its window holds the whole page. */
private const val READY_EN = "+h1300dp"
private const val READY = "+h1177dp"
private const val READY_FS115 = "+h1213dp"
private const val READY_FS200 = "+h1820dp"
private const val BUILDING = "+h1259dp"
private const val BUILDING_FS115 = "+h1295dp"
private const val BUILDING_FS200 = "+h1959dp"
private const val FAILED = "+h1335dp"
private const val FAILED_FS115 = "+h1376dp"
private const val FAILED_FS200 = "+h2053dp"
private const val LINK_FAILED = "+h1357dp"
private const val LINK_FAILED_FS200 = "+h2062dp"
