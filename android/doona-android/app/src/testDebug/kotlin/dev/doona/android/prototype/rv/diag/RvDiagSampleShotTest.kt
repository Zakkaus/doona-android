package dev.doona.android.prototype.rv.diag

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
    @Config(qualifiers = "+h3173dp")
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
    @Config(qualifiers = "+h1486dp")
    fun dnsQueryLight() = compose.rvShot("diag-dns-query", dark = false, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "+h1486dp")
    fun dnsQueryDark() = compose.rvShot("diag-dns-query", dark = true, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1480dp-notnight-xhdpi")
    fun dnsQueryEn() = compose.rvShot("diag-dns-query", dark = false, english = true, before = { assertWhole() }) { RvDnsQuerySample() }

    @Test
    @Config(qualifiers = "+h1910dp")
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
    @Config(qualifiers = "+h1924dp")
    fun dnsCacheFs200() = compose.rvShot("diag-dns-cache", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsCacheSample() }

    @Test
    @Config(qualifiers = "+h1017dp")
    fun dnsLogLight() = compose.rvShot("diag-dns-log", dark = false, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "+h1017dp")
    fun dnsLogDark() = compose.rvShot("diag-dns-log", dark = true, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1031dp-notnight-xhdpi")
    fun dnsLogEn() = compose.rvShot("diag-dns-log", dark = false, english = true, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "+h1905dp")
    fun dnsLogFs200() = compose.rvShot("diag-dns-log", dark = false, fontScale = RV_FS200, before = { assertWhole() }) { RvDnsLogSample() }

    @Test
    @Config(qualifiers = "+h1163dp")
    fun dnsRecordLight() = compose.rvShot("diag-dns-record", dark = false, before = { assertWhole() }) { RvDnsRecordSample() }

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
    @Config(qualifiers = "+h1852dp")
    fun logsDark() = compose.rvShot("diag-logs", dark = true, before = { assertWhole() }) { RvLogsSample() }

    @Test
    @Config(qualifiers = "en-w412dp-h1848dp-notnight-xhdpi")
    fun logsEn() = compose.rvShot("diag-logs", dark = false, english = true, before = { assertWhole() }) { RvLogsSample() }

    @Test
    @Config(qualifiers = "+h2855dp")
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
