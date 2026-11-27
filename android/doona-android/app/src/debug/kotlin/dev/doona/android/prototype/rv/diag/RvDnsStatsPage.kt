package dev.doona.android.prototype.rv.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencySpread
import dev.doona.android.designsystem.rv.RvProgress
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.prototype.rv.samples.sampleText
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * DNS 統計 (doona web's DNS › 統計, `features/dns/Analysis.tsx`), over the loaded page of `GET /dns/log` and the cache's
 * `usage` from `GET /dns/cache`. The caption counts what was loaded, missed the cache and went upstream. Then the web's
 * cards in the library's parts: its fact strip as key-stats rows (中位數, P95, 快取命中率, 失敗率); 上游查詢的延遲, the
 * web's beeswarm as the library's latency dots ([RvLatencySpread]) with the median line, a row per upstream with its
 * count and median, and 開啟 DNS 組態; 回應結果, the waffle's shares as key-stats rows; 查詢排行 by
 * device or by domain, a row each with its count that opens DNS 記錄 filtered to it, the rest counted under it; and
 * 快取狀態, the entries against the capacity as the progress bar, what the cache keeps, and 檢視快取.
 */
@Composable
internal fun RvDnsStatsSample(byDomain: Boolean = true) {
    val s = RvDnsStats
    val upstream = s.upstreams.sumOf { it.second.size }
    val all = s.upstreams.flatMap { it.second }.sorted()
    val unit = MS.trim()
    RvDiagPage(
        sampleText("DNS 統計", "DNS statistics"),
        caption = sampleText(
            "已載入 ${s.LOADED} 筆 · 未命中快取 ${s.LOADED - s.CACHED} 筆 · 上游查詢 ${upstream + s.TIMED_OUT} 筆",
            "${s.LOADED} loaded · ${s.LOADED - s.CACHED} uncached · ${upstream + s.TIMED_OUT} sent upstream",
        ),
    ) {
        RvGutter {
            RvCard {
                RvStatRow(sampleText("中位數", "Median"), ms(median(all)))
                RvStatRow("P95", ms(all[ceil(all.size * P95).toInt() - 1]))
                RvStatRow(sampleText("快取命中率", "Cache hit rate"), percent(s.CACHED, s.LOADED))
                RvStatRow(sampleText("失敗率", "Failure rate"), percent(s.FAILED, s.LOADED))
            }
        }
        RvDiagSection(sampleText("$upstream 筆上游查詢的延遲", "Upstream latency for $upstream lookups"))
        RvGutter {
            RvCard {
                Column(Modifier.padding(start = RvSpace.rowPadding, top = RvSpace.rowPadding, end = RvSpace.rowPadding)) { RvLatencySpread(all, null, "", median(all), unit) }
                s.upstreams.forEach { (name, values) ->
                    RvStatRow(name, sampleText("${values.size} 筆，中位數：${ms(median(values))}", "${values.size} lookups, median: ${ms(median(values))}"))
                }
                RvSeeAll(sampleText("開啟 DNS 組態", "Open DNS configuration"), {})
            }
        }
        RvFootnote(sampleText("逾時的 ${s.TIMED_OUT} 筆失敗查詢沒有延遲，不在圖中", "The ${s.TIMED_OUT} failed lookups that timed out have no latency and are not drawn"))
        Outcomes(s)
        Ranking(s, byDomain)
        Cache(s)
    }
}

/** 回應結果: the waffle's shares in figures, a key-stats row per outcome with its count and share. */
@Composable
private fun Outcomes(s: RvDnsStats) {
    val outcomes = listOf(
        sampleText("快取命中", "From the cache") to s.CACHED,
        sampleText("上游回答", "Answered upstream") to s.ANSWERED,
        sampleText("查無此名", "No such name") to s.NXDOMAIN,
        sampleText("失敗", "Failed") to s.FAILED,
    )
    RvDiagSection(sampleText("回應結果", "Outcomes"))
    RvGutter {
        RvCard {
            outcomes.forEach { (label, n) -> RvStatRow(label, "$n" + sampleText("（${percent(n, s.LOADED)}）", " (${percent(n, s.LOADED)})")) }
        }
    }
}

/** 查詢排行: devices or domains, the most asked first, each with its count; the rest in one line under the card. */
@Composable
private fun Ranking(s: RvDnsStats, byDomain: Boolean) {
    val ranking = if (byDomain) s.domains else s.devices
    val shown = ranking.sumOf { it.second }
    RvDiagSection(sampleText("查詢排行", "Top queries"))
    RvGutter {
        RvCard {
            RvSegmented(listOf(sampleText("裝置", "Devices"), sampleText("網域", "Domains")), if (byDomain) 1 else 0, {}, Modifier.padding(start = RvSpace.rowPadding, top = RvSpace.rowPadding, end = RvSpace.rowPadding))
            ranking.forEach { (key, n) ->
                val name = key ?: sampleText("解析器自身", "The resolver itself")
                RvRow(null, name, null, onClick = key?.let { {} }, titleKeepWords = false, trailing = { RvEndValue(count(n)) })
            }
        }
    }
    if (shown < s.LOADED) RvFootnote(sampleText("其餘 ${s.LOADED - shown} 筆", "${s.LOADED - shown} more lookups"))
}

/** 快取狀態: the entries against the capacity, as figures and as the bar, what the cache keeps, and 檢視快取. */
@Composable
private fun Cache(s: RvDnsStats) {
    RvDiagSection(sampleText("快取狀態", "Cache"))
    RvGutter {
        RvCard {
            RvStatRow(sampleText("條目", "Entries"), rvCount(RvDiagData.CACHE_ENTRIES.toLong()))
            RvStatRow(sampleText("容量", "Capacity"), rvCount(s.CAPACITY.toLong()))
            Column(Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding)) {
                RvProgress(RvDiagData.CACHE_ENTRIES.toFloat() / s.CAPACITY)
                RvText(
                    sampleText("快取範圍：正向回答、負向回答；僅存於記憶體，重新啟動後清空", "Caches positive answers, negative answers; held in memory only, cleared on restart"),
                    Rv.type.rowSub,
                    Modifier.padding(top = RvSpace.barGap),
                    color = Rv.colors.textSecondary,
                    maxLines = Int.MAX_VALUE,
                )
            }
            RvSeeAll(sampleText("檢視快取", "View cache"), {})
        }
    }
}

@Composable
private fun count(n: Int): String = sampleText("$n 筆", if (n == 1) "1 lookup" else "$n lookups")

private fun ms(n: Int?): String = n?.let { rvCount(it.toLong()) + MS } ?: "—"

private fun percent(n: Int, of: Int): String = "${(n * 100f / of).roundToInt()}%"

private fun median(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

private const val MS = " ms"
private const val P95 = 0.95

/**
 * The loaded page of the DNS log the statistics read: 100 records, by outcome; the latency of each lookup an upstream
 * answered (or denied the name), by upstream; the most asked domains and devices; and the cache's capacity
 * (`max_cache_size`). Its names are the DNS samples' own (googledns, cloudflare, alidns; the phone's 172.19.0.1).
 */
internal object RvDnsStats {
    const val LOADED = 100
    const val CACHED = 36
    const val ANSWERED = 58
    const val NXDOMAIN = 2
    const val FAILED = 4
    const val TIMED_OUT = 4
    const val CAPACITY = 4_096
    val upstreams = listOf(
        "googledns" to listOf(31, 38, 29, 44, 36, 41, 33, 52, 35, 39, 30, 47, 37, 34, 58, 40, 32, 43, 36, 49, 38, 35, 61, 42, 33, 37, 45, 39, 128),
        "cloudflare" to listOf(52, 47, 61, 55, 49, 58, 66, 51, 72, 54, 48, 63, 57, 50, 59, 164),
        "alidns" to listOf(14, 11, 17, 12, 21, 13, 15, 19, 12, 16, 14, 23, 13, 18, 15),
    )
    val domains = listOf<Pair<String?, Int>>(
        "www.youtube.com." to 14,
        "i.ytimg.com." to 9,
        "chatgpt.com." to 7,
        "imap.gmail.com." to 6,
        "time.android.com." to 5,
        "szshort.weixin.qq.com." to 4,
        "connectivitycheck.gstatic.com." to 4,
    )
    val devices = listOf<Pair<String?, Int>>("172.19.0.1" to 97, null to 3)
}
