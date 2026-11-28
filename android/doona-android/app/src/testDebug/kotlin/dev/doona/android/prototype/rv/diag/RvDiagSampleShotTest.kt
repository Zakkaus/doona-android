package dev.doona.android.prototype.rv.diag

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * The samples of the diagnostic pages (q-diag-pages), each a whole page: `proto-rv-diag-<screen>-<light|dark|en|fs200>`
 * for 流量記錄 (`flows`), a flow (`flow`), 流量路徑 (`flows-map`), DNS 查詢 (`dns-query`), DNS 快取 (`dns-cache`),
 * DNS 記錄 (`dns-log`), a DNS record (`dns-record`), DNS 統計 (`dns-stats`), 日誌檢視 (`logs`), 事件 (`events`) and
 * 總覽 (`overview`). Each
 * window is as tall as its page, so the golden holds all of it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvDiagSampleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = "+h1809dp")
    fun flowsLight() = compose.rvShot("diag-flows", dark = false, before = { assertWhole() }) { RvFlowsSample() }

    @Test
    @Config(qualifiers = "+h1809dp")
    fun flowsDark() = compose.rvShot("diag-flows", dark = true, before = { assertWhole() }) { RvFlowsSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1807dp-notnight-xhdpi")
    fun flowsEn() = compose.rvShot("diag-flows", dark = false, english = true, before = { assertWhole() }) { RvFlowsSample() }

    @Test
    @Config(qualifiers = "+h2640dp")
    fun flowsFs200() = compose.rvShot("diag-flows", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvFlowsSample() }

    @Test
    @Config(qualifiers = "+h2369dp")
    fun flowLight() = compose.rvShot("diag-flow", dark = false, before = { assertWhole() }) { RvFlowDetailSample() }

    @Test
    @Config(qualifiers = "+h2369dp")
    fun flowDark() = compose.rvShot("diag-flow", dark = true, before = { assertWhole() }) { RvFlowDetailSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h2430dp-notnight-xhdpi")
    fun flowEn() = compose.rvShot("diag-flow", dark = false, english = true, before = { assertWhole() }) { RvFlowDetailSample() }

    @Test
    @Config(qualifiers = "+h3251dp")
    fun flowFs200() = compose.rvShot("diag-flow", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvFlowDetailSample() }

    @Test
    @Config(qualifiers = "+h1701dp")
    fun flowsMapLight() = compose.rvShot("diag-flows-map", dark = false, before = { assertWhole() }) { RvFlowMapSample() }

    @Test
    @Config(qualifiers = "+h1701dp")
    fun flowsMapDark() = compose.rvShot("diag-flows-map", dark = true, before = { assertWhole() }) { RvFlowMapSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1745dp-notnight-xhdpi")
    fun flowsMapEn() = compose.rvShot("diag-flows-map", dark = false, english = true, before = { assertWhole() }) { RvFlowMapSample() }

    @Test
    @Config(qualifiers = "+h2521dp")
    fun flowsMapFs200() = compose.rvShot("diag-flows-map", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvFlowMapSample() }

    @Test
    @Config(qualifiers = "+h2250dp")
    fun dnsQueryLight() = compose.rvShot("diag-dns-query", dark = false, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "+h2250dp")
    fun dnsQueryDark() = compose.rvShot("diag-dns-query", dark = true, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "en-w412dp-h2250dp-notnight-xhdpi")
    fun dnsQueryEn() = compose.rvShot("diag-dns-query", dark = false, english = true, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "+h2950dp")
    fun dnsQueryFs200() = compose.rvShot("diag-dns-query", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "+h1055dp")
    fun dnsCacheLight() = compose.rvShot("diag-dns-cache", dark = false, before = { assertWhole() }) { RvDnsCacheSample() }

    @Test
    @Config(qualifiers = "+h1055dp")
    fun dnsCacheDark() = compose.rvShot("diag-dns-cache", dark = true, before = { assertWhole() }) { RvDnsCacheSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1049dp-notnight-xhdpi")
    fun dnsCacheEn() = compose.rvShot("diag-dns-cache", dark = false, english = true, before = { assertWhole() }) { RvDnsCacheSample() }

    @Test
    @Config(qualifiers = "+h1978dp")
    fun dnsCacheFs200() = compose.rvShot("diag-dns-cache", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsCacheSample() }

    @Test
    @Config(qualifiers = "+h1099dp")
    fun dnsLogLight() = compose.rvShot("diag-dns-log", dark = false, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "+h1099dp")
    fun dnsLogDark() = compose.rvShot("diag-dns-log", dark = true, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1113dp-notnight-xhdpi")
    fun dnsLogEn() = compose.rvShot("diag-dns-log", dark = false, english = true, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "+h2012dp")
    fun dnsLogFs200() = compose.rvShot("diag-dns-log", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "+h1163dp")
    fun dnsRecordLight() = compose.rvShot("diag-dns-record", dark = false, before = {
        onNode(hasText("路由來源") and hasText("DNS 路由規則")).assertExists()
        onNode(hasText("路由規則") and hasText("—")).assertExists()
        assertWhole()
    }) { RvDnsRecordSample() }

    @Test
    @Config(qualifiers = "+h1163dp")
    fun dnsRecordDark() = compose.rvShot("diag-dns-record", dark = true, before = { assertWhole() }) { RvDnsRecordSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1158dp-notnight-xhdpi")
    fun dnsRecordEn() = compose.rvShot("diag-dns-record", dark = false, english = true, before = { assertWhole() }) { RvDnsRecordSample() }

    @Test
    @Config(qualifiers = "+h1545dp")
    fun dnsRecordFs200() = compose.rvShot("diag-dns-record", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsRecordSample() }

    @Test
    @Config(qualifiers = "+h2223dp")
    fun dnsStatsLight() = compose.rvShot("diag-dns-stats", dark = false, before = { assertWhole() }) { RvDnsStatsSample() }

    @Test
    @Config(qualifiers = "+h2223dp")
    fun dnsStatsDark() = compose.rvShot("diag-dns-stats", dark = true, before = { assertWhole() }) { RvDnsStatsSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h2256dp-notnight-xhdpi")
    fun dnsStatsEn() = compose.rvShot("diag-dns-stats", dark = false, english = true, before = { assertWhole() }) { RvDnsStatsSample() }

    @Test
    @Config(qualifiers = "+h2938dp")
    fun dnsStatsFs200() = compose.rvShot("diag-dns-stats", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsStatsSample() }

    @Test
    @Config(qualifiers = "+h1852dp")
    fun logsLight() = compose.rvShot("diag-logs", dark = false, before = { assertWhole() }) { RvLogsSample() }

    @Test
    fun busiestMinuteUsesLocalTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+08:00")) // Utc restores the previous zone after the test.
        compose.rvContent(dark = false) { RvLogsSample() }
        compose.onNodeWithText("18:21–18:22", useUnmergedTree = true).assertExists()
    }

    @Test
    fun logRowsRenderTypedFields() {
        val debug = RvLogRecord(RV_NOW, RvLevel.Debug, "honk_core::control::runtime", "Publishing accepted subscription body", buildJsonObject { put("nodes", 48) })
        compose.rvContent(dark = false) { RvLogsSample(RvDiagData.logs + debug) }
        compose.onNodeWithText("Publishing accepted subscription body nodes=48", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Standalone DNS listener started native_withheld_fields=true tcp=false udp=true", useUnmergedTree = true).assertExists()
    }

    @Test
    fun dnsStatisticsUseTheLoadedRecords() {
        compose.rvContent(dark = false) { RvDnsStatsSample() }
        compose.onNodeWithText("已載入 8 筆 / 未命中快取 6 筆 / 上游查詢 6 筆", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("P95") and hasText("5,000 ms")).assertExists()
    }

    @Test
    fun singleQueryTypeUsesTheReturnedType() {
        compose.rvContent(dark = false) { RvDnsQuerySample(RvDiagData.query.takeLast(1)) }
        compose.onNodeWithText("AAAA", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("A / AAAA", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun timeoutDoesNotReplaceAnotherQueryTypesAnswer() {
        compose.rvContent(dark = false) { RvDnsQuerySample() }
        compose.onNodeWithText("A / AAAA", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("142.250.196.206", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("www.youtube.com. AAAA", useUnmergedTree = true, ignoreCase = true).assertExists()
        compose.onAllNodesWithText("TIMEOUT", useUnmergedTree = true).onFirst().assertExists()
        compose.onAllNodes(hasText("路由來源") and hasText("DNS 路由規則")).assertCountEquals(2)
        compose.onAllNodes(hasText("路由規則") and hasText("—")).assertCountEquals(2)
        compose.onAllNodesWithText("新增規則", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("檢視快取", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun observedFlowListDoesNotInventARoute() {
        val flow = RvFlow("id", "example.org", 443, "Chrome", "com.android.chrome", false, null, null, RvFlowState.Observed, RV_NOW)
        compose.rvContent(dark = false) { RvFlowsSample(listOf(flow)) }
        compose.onNodeWithText("已觀測", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("—", useUnmergedTree = true).assertExists()
    }

    @Test
    fun observedFlowHasNoInventedNodeAndKeepsIndependentStepTimes() {
        val steps = listOf(0L to 48_306L, 0L to null, null to 0L).map { (at, elapsed) -> RvFlowStep(RvWord("Stage"), at, elapsed, emptyList()) }
        val flow = RvFlow("id", "example.org", 443, "Chrome", "com.android.chrome", false, null, null, RvFlowState.Observed, RV_NOW, steps = steps)
        compose.rvContent(dark = false) { RvFlowDetailSample(flow) }
        compose.onNodeWithText("已觀測", useUnmergedTree = true).assertExists()
        compose.onNode(hasText("節點") and hasText("—")).assertExists()
        compose.onNodeWithText("00:00:00 / 48,306 μs", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("00:00:00 / —", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("— / 0 μs", useUnmergedTree = true).assertExists()
    }

    @Test
    @Config(qualifiers = "+h1852dp")
    fun logsDark() = compose.rvShot("diag-logs", dark = true, before = { assertWhole() }) { RvLogsSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1848dp-notnight-xhdpi")
    fun logsEn() = compose.rvShot("diag-logs", dark = false, english = true, before = { assertWhole() }) { RvLogsSample() }

    @Test
    @Config(qualifiers = "+h2909dp")
    fun logsFs200() = compose.rvShot("diag-logs", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvLogsSample() }

    @Test
    @Config(qualifiers = "+h915dp")
    fun eventsLight() = compose.rvShot("diag-events", dark = false, before = { assertWhole() }) { RvEventsSample() }

    @Test
    @Config(qualifiers = "+h915dp")
    fun eventsDark() = compose.rvShot("diag-events", dark = true, before = { assertWhole() }) { RvEventsSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun eventsEn() = compose.rvShot("diag-events", dark = false, english = true, before = { assertWhole() }) { RvEventsSample() }

    @Test
    @Config(qualifiers = "+h1211dp")
    fun eventsFs200() = compose.rvShot("diag-events", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvEventsSample() }

    @Test
    @Config(qualifiers = "+h3026dp")
    fun overviewLight() = compose.rvShot("diag-overview", dark = false, before = { assertWhole() }) { RvOverviewSample() }

    @Test
    @Config(qualifiers = "+h3026dp")
    fun overviewDark() = compose.rvShot("diag-overview", dark = true, before = { assertWhole() }) { RvOverviewSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h3041dp-notnight-xhdpi")
    fun overviewEn() = compose.rvShot("diag-overview", dark = false, english = true, before = { assertWhole() }) { RvOverviewSample() }

    @Test
    @Config(qualifiers = "+h3345dp")
    fun overviewFs200() = compose.rvShot("diag-overview", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvOverviewSample() }
}
