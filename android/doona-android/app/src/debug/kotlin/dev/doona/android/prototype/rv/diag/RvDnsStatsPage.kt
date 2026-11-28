package dev.doona.android.prototype.rv.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencyScale
import dev.doona.android.designsystem.rv.RvLatencySpread
import dev.doona.android.designsystem.rv.RvLegendItem
import dev.doona.android.designsystem.rv.RvMeter
import dev.doona.android.designsystem.rv.RvMeterTrack
import dev.doona.android.designsystem.rv.RvProgress
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvStatsSize
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvUsageSize
import dev.doona.android.feature.stats.RvStatsFigure
import dev.doona.android.feature.stats.cardBody
import dev.doona.android.prototype.rv.samples.sampleText
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * DNS 統計 (doona web's DNS › 統計, `features/dns/Analysis.tsx`), over the loaded page of `GET /dns/log` and the cache's
 * `usage` from `GET /dns/cache`. The caption counts what was loaded, missed the cache and went upstream. Then the web's
 * cards in the library's parts: its fact strip as key-stats rows (中位數, P95, 快取命中率, 失敗率 of the uncached);
 * 上游查詢的延遲, the web's beeswarm as the library's latency dots ([RvLatencySpread]) on the web's log axis, each lookup
 * that reached an upstream in its outcome's colour with the timed-out failures at their five seconds, the outcomes' key,
 * a row per upstream with its count and median, and 開啟 DNS 組態; 回應結果, 統計's 分流比例 card
 * body over the four outcomes ([RvMeter]); 查詢排行 by device or by domain, a row each with its count that opens DNS 記錄 filtered to it
 * and the web's bar under it ([RvMeterTrack], against the most asked), the rest counted under the card; and 快取狀態, the entries against the
 * capacity as the progress bar, what the cache keeps, and 檢視快取.
 */
@Composable
internal fun RvDnsStatsSample(byDomain: Boolean = true) {
    val s = RvDnsStats
    val samples = s.upstreams.flatMap { it.second }.sortedBy { it.ms }
    val all = samples.map { it.ms }
    val upstream = samples.size
    val unit = MS.trim()
    RvDiagPage(
        sampleText("DNS 統計", "DNS statistics"),
        caption = sampleText(
            "已載入 ${s.LOADED} 筆，未命中快取 ${s.LOADED - s.CACHED} 筆，上游查詢 $upstream 筆",
            "已加载 ${s.LOADED} 条，未命中缓存 ${s.LOADED - s.CACHED} 条，上游查询 $upstream 条",
            "${s.LOADED} loaded, ${s.LOADED - s.CACHED} uncached, $upstream sent upstream",
        ),
    ) {
        RvGutter {
            RvCard {
                RvStatRow(sampleText("中位數", "Median"), ms(median(all)))
                RvStatRow("P95", ms(all[ceil(all.size * P95).toInt() - 1]))
                RvStatRow(sampleText("快取命中率", "Cache hit rate"), percent(s.CACHED, s.LOADED))
                RvStatRow(sampleText("失敗率", "Failure rate"), percent(s.FAILED, s.LOADED - s.CACHED))
            }
        }
        RvDiagSection(sampleText("$upstream 筆上游查詢的延遲", "Upstream latency for $upstream lookups"))
        RvGutter {
            RvCard {
                val ink = outcomeInk()
                Column(Modifier.padding(start = RvSpace.rowPadding, top = RvSpace.rowPadding, end = RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
                    RvLatencySpread(all, null, "", median(all), unit, dotColors = samples.map { ink.getValue(it.outcome) }, scale = RvLatencyScale.Log)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(RvUsageSize.legendItemGap)) {
                        listOf(RvDnsOutcome.Answered, RvDnsOutcome.NoSuchName, RvDnsOutcome.Failed).forEach { RvLegendItem(ink.getValue(it), outcomeLabel(it)) }
                    }
                }
                s.upstreams.forEach { (name, values) ->
                    val m = median(values.map { it.ms })
                    RvStatRow(name, sampleText("${values.size} 筆，中位數：${ms(m)}", "${values.size} lookups, median: ${ms(m)}"))
                }
                RvSeeAll(sampleText("開啟 DNS 組態", "Open DNS configuration")) {}
            }
        }
        Outcomes(s)
        Ranking(s, byDomain)
        Cache(s)
    }
}

/**
 * 回應結果, in 統計's 分流比例 grammar: its title in the card ([RvCardHeader]), the share served from the cache as the figure, and a meter each for the four
 * outcomes with its count, filled to its share of what was loaded, in the outcomes' colours.
 */
@Composable
private fun Outcomes(s: RvDnsStats) {
    val ink = outcomeInk()
    val outcomes = listOf(RvDnsOutcome.Cached to s.CACHED, RvDnsOutcome.Answered to s.ANSWERED, RvDnsOutcome.NoSuchName to s.NXDOMAIN, RvDnsOutcome.Failed to s.FAILED)
    RvGutter {
        RvCard {
            RvCardHeader(sampleText("回應結果", "Outcomes"))
            Row(cardBody, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RvStatsFigure(RvFigure(percent(s.CACHED, s.LOADED), ""))
                    RvText(sampleText("命中快取", "From the cache"), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                val texts = outcomes.map { (o, n) -> rvCount(n.toLong()) to meterLabel(o) }
                val measurer = rememberTextMeasurer()
                val style = Rv.type.rowSub
                val widest = with(LocalDensity.current) { texts.maxOf { (n, label) -> measurer.measure("$n $label", style).size.width }.toDp() }
                BoxWithConstraints(Modifier.weight(1f)) {
                    // The labels take at most this share of the column, so the tracks keep the rest; longer ones wrap.
                    val labelWidth = minOf(maxOf(widest, RvStatsSize.ratingLabel), maxWidth * LABEL_SHARE)
                    Column {
                        outcomes.forEachIndexed { i, (o, n) ->
                            RvMeter(n.toFloat() / s.LOADED, texts[i].first, texts[i].second, ink.getValue(o), known = true, labelWidth = labelWidth)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What each outcome is drawn in: a cache hit and an answer the library's two series, first and second as 分流比例's
 * meters are, no such name the fair hue and a failure the error's.
 */
@Composable
private fun outcomeInk(): Map<RvDnsOutcome, Color> = mapOf(
    RvDnsOutcome.Cached to Rv.colors.down.line,
    RvDnsOutcome.Answered to Rv.colors.up.line,
    RvDnsOutcome.NoSuchName to Rv.colors.fair,
    RvDnsOutcome.Failed to Rv.colors.error,
)

/** An outcome's short name for its meter, as short as 分流比例's 代理 and 直連. */
@Composable
private fun meterLabel(o: RvDnsOutcome): String = when (o) {
    RvDnsOutcome.Cached -> sampleText("快取", "Cached")
    RvDnsOutcome.Answered -> sampleText("上游", "Upstream")
    RvDnsOutcome.NoSuchName -> sampleText("無此名", "NXDOMAIN")
    RvDnsOutcome.Failed -> sampleText("失敗", "Failed")
}

@Composable
private fun outcomeLabel(o: RvDnsOutcome): String = when (o) {
    RvDnsOutcome.Cached -> sampleText("快取命中", "From the cache")
    RvDnsOutcome.Answered -> sampleText("上游回答", "Answered upstream")
    RvDnsOutcome.NoSuchName -> sampleText("查無此名", "No such name")
    RvDnsOutcome.Failed -> sampleText("失敗", "Failed")
}

/**
 * 查詢排行: devices or domains, the most asked first, each with its count and its bar against the first, in the web's two
 * series' colours (devices the first, domains the second); the rest in one line under the card.
 */
@Composable
private fun Ranking(s: RvDnsStats, byDomain: Boolean) {
    val ranking = if (byDomain) s.domains else s.devices
    val shown = ranking.sumOf { it.second }
    val top = ranking.first().second.toFloat()
    val ink = if (byDomain) Rv.colors.up.line else Rv.colors.down.line
    RvDiagSection(sampleText("查詢排行", "Top queries"))
    RvGutter {
        RvCard {
            RvSegmented(listOf(sampleText("裝置", "Devices"), sampleText("網域", "Domains")), if (byDomain) 1 else 0, {}, Modifier.padding(start = RvSpace.rowPadding, top = RvSpace.rowPadding, end = RvSpace.rowPadding))
            ranking.forEach { (key, n) ->
                val name = key ?: sampleText("解析器自身", "The resolver itself")
                RvRow(null, name, null, onClick = key?.let { {} }, titleKeepWords = false, trailing = { RvEndValue(count(n)) })
                RvMeterTrack(n / top, ink, Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding))
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
            RvSeeAll(sampleText("檢視快取", "View cache")) {}
        }
    }
}

@Composable
private fun count(n: Int): String = sampleText("$n 筆", if (n == 1) "1 lookup" else "$n lookups")

private fun ms(n: Int?): String = n?.let { rvCount(it.toLong()) + MS } ?: "—"

private fun percent(n: Int, of: Int): String = "${(n * 100f / of).roundToInt()}%"

private fun median(values: List<Int>): Int? = values.sorted().takeIf { it.isNotEmpty() }?.let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }

private const val MS = " ms"
private const val LABEL_SHARE = 0.7f
private const val P95 = 0.95

/**
 * The loaded page of the DNS log the statistics read: 100 records, by outcome; each uncached lookup that reached an
 * upstream, by upstream, with its `elapsed_ms` and outcome (the four failures timed out at honk's five-second query
 * timeout); the most asked domains and devices; and the cache's capacity (`max_cache_size`). Its names are the DNS
 * samples' own (googledns, cloudflare, alidns; the phone's 172.19.0.1).
 */
internal object RvDnsStats {
    const val LOADED = 100
    const val CACHED = 36
    const val ANSWERED = 58
    const val NXDOMAIN = 2
    const val FAILED = 4
    const val CAPACITY = 4_096
    val upstreams = listOf(
        "googledns" to samples(listOf(31, 38, 29, 44, 36, 41, 33, 52, 35, 39, 30, 47, 37, 34, 58, 40, 32, 43, 36, 49, 38, 35, 61, 42, 33, 37, 45, 39, 128), noName = setOf(58), failed = listOf(5_002)),
        "cloudflare" to samples(listOf(52, 47, 61, 55, 49, 58, 66, 51, 72, 54, 48, 63, 57, 50, 59, 164), failed = listOf(5_001, 5_003)),
        "alidns" to samples(listOf(14, 11, 17, 12, 21, 13, 15, 19, 12, 16, 14, 23, 13, 18, 15), noName = setOf(21), failed = listOf(5_000)),
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

/** How a lookup ended, as the web's DNS statistics split them: a cache hit, an answer, no such name, or no answer. */
internal enum class RvDnsOutcome { Cached, Answered, NoSuchName, Failed }

/** A lookup that reached an upstream: how long it took and how it ended. */
internal class RvDnsSample(val ms: Int, val outcome: RvDnsOutcome)

/** An upstream's answered lookups ([noName] among them denied the name), then its timed-out failures. */
private fun samples(answered: List<Int>, noName: Set<Int> = emptySet(), failed: List<Int> = emptyList()): List<RvDnsSample> =
    answered.map { RvDnsSample(it, if (it in noName) RvDnsOutcome.NoSuchName else RvDnsOutcome.Answered) } + failed.map { RvDnsSample(it, RvDnsOutcome.Failed) }
