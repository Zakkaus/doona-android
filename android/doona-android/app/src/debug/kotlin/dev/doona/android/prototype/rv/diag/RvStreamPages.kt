package dev.doona.android.prototype.rv.diag

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBarChart
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvFilterChip
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGlyph
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvStatsSize
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.prototype.rv.samples.sampleText

/**
 * 日誌檢視 (doona web's 日誌, `GET /logs`): the caption says the level honk records from and the stream's state; the
 * chips pick the lowest level shown and a target prefix. 日誌時間分布 draws a row of bars per level over time, then the records
 * of each level and the busiest minute; as on the web, a level's row sets the lowest level shown, and the chip, the
 * rows' check and the list follow it. Each record is its level's glyph, its message (with its typed fields), and its
 * time, level and target under it; a gap in the stream is a row of its own. 暫停 holds the list,
 * 匯出 writes it, 清空 empties it, 記錄設定 opens the recorder's mode and level.
 */
@Composable
internal fun RvLogsSample(records: List<RvLogRecord> = RvDiagData.logs, initialMinimum: RvLevel = RvLevel.Info) {
    val format = rememberRvFormat()
    var minimum by remember { mutableStateOf(initialMinimum) }
    val levels = RvLevel.entries.filter { it <= minimum }
    val word = minimum.word.text()
    RvDiagPage(
        sampleText("日誌檢視", "Logs"),
        caption = sampleText("引擎記錄：資訊及以上 · 串流中", "Engine records info and above · Streaming"),
        control = { RvFilterChipRow(RvChipPick(sampleText("${word}及以上", "$word and above")), RvChipPick(sampleText("全部模組", "All targets"))) },
    ) {
        RvDiagSection(sampleText("日誌時間分布", "Log activity"))
        RvGutter {
            RvCard {
                LogLevelBars(records, minimum) { minimum = it }
                RvDiagData.logCounts.filter { it.first in levels }.forEach { (level, n) -> RvStatRow(level.word.text(), sampleText("$n 筆", if (n == 1) "1 record" else "$n records")) }
                RvStatRow(sampleText("記錄最多的時段", "Busiest"), RvDiagData.LOG_PEAK)
            }
        }
        RvDiagSection(sampleText("記錄", "Records"))
        RvGutter {
            RvCard {
                records.filter { it.gap || it.level in levels }.forEach { r ->
                    if (r.gap) {
                        RvRow({ RvIconDisc(R.drawable.rv_sym_wifi_off) }, sampleText("日誌遺失：中斷連線期間的記錄無法取回", "Logs lost: records from the disconnection cannot be recovered"), null)
                    } else {
                        RvRow(
                            { RvIconDisc(r.level.glyph) },
                            r.message,
                            listOf(format.clock(r.atEpochMs), r.level.word.text(), r.target).joinToString(SEPARATOR),
                            onClick = {},
                            titleKeepWords = false,
                            subtitleKeepWords = false,
                        )
                    }
                }
            }
            RvSettingsCard {
                RvSwitchRow(R.drawable.rv_sym_schedule, sampleText("暫停", "Pause"), false, onChange = {})
                RvSettingsNavRow(sampleText("匯出", "Export"), R.drawable.rv_sym_download, {})
                RvSettingsNavRow(sampleText("清空", "Clear"), R.drawable.rv_sym_delete, {})
                RvSettingsNavRow(sampleText("記錄設定", "Recording settings"), R.drawable.rv_sym_settings, {}, sampleText("自動", "Auto"))
            }
        }
    }
}

/**
 * 日誌時間分布 over [records]: a row per level, 錯誤 to 除錯, each 統計's hour bars ([RvBarChart]) at a row's height, a
 * bar per stretch of time in the level's hue, scaled to the level's own busiest so three errors read beside a thousand
 * info records; a stretch with no records is a stub on the track. The labels share one column as wide as the widest, after a
 * check's room, and the span's first and last times stand under the last row in the caption type. A row sets the
 * minimum to its level ([onMinimum]); the current minimum's row carries the check, and a screen reader hears each row's
 * busy stretches with their counts.
 */
@Composable
private fun LogLevelBars(records: List<RvLogRecord>, minimum: RvLevel, onMinimum: (RvLevel) -> Unit) {
    val format = rememberRvFormat()
    val heat = rvLogHeat(records, RvLevel.entries)
    val hues = mapOf(RvLevel.Error to Rv.colors.error, RvLevel.Warn to Rv.colors.fair, RvLevel.Info to Rv.colors.positive, RvLevel.Debug to Rv.colors.textSecondary)
    val zh = sampleText("zh", "") == "zh"
    val measurer = rememberTextMeasurer()
    val style = Rv.type.tileCaption
    val density = LocalDensity.current
    val widest = with(density) { heat.counts.maxOf { measurer.measure(it.first.word.text(), style).size.width }.toDp() }
    Column(Modifier.padding(top = RvSpace.tight)) {
        heat.counts.forEach { (level, counts) ->
            val word = level.word.text()
            val busy = counts.indices.filter { counts[it] > 0 }.joinToString(if (zh) "，" else "; ") { i ->
                val span = format.clock(heat.starts[i]) + SPAN + format.clock(heat.starts[i] + heat.width)
                val n = counts[i]
                if (zh) {
                    "$span $n 筆"
                } else if (n == 1) {
                    "$span, 1 record"
                } else {
                    "$span, $n records"
                }
            }
            val current = level == minimum
            val pick = if (current) sampleText("$word：目前的最低等級", "$word: the current minimum") else sampleText("顯示${word}及以上", "Show $word and above")
            val heard = (if (zh) "$word：" else "$word: ") + busy
            Row(
                Modifier.rvRowSelect(current, onClick = { onMinimum(level) }).semantics { contentDescription = pick }
                    .padding(horizontal = RvSpace.rowPadding, vertical = RvSpace.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current) RvGlyph(R.drawable.rv_sym_check, RvChartSize.segmentCheck, Rv.colors.text) else Spacer(Modifier.width(RvChartSize.segmentCheck))
                Spacer(Modifier.width(RvChartSize.segmentGlyphGap))
                RvText(word, style, Modifier.widthIn(min = widest), maxLines = Int.MAX_VALUE)
                Spacer(Modifier.width(RvSpace.trailingGap))
                RvBarChart(counts.map { n -> n.toLong().takeIf { it > 0 } }, hues.getValue(level), Modifier.weight(1f).height(RvStatsSize.rowBars).semantics { contentDescription = heard }, slots = heat.starts.size.coerceAtLeast(1))
            }
        }
        if (heat.starts.isNotEmpty()) {
            val labelRoom = RvChartSize.segmentCheck + RvChartSize.segmentGlyphGap + widest + RvSpace.trailingGap
            Row(Modifier.padding(start = RvSpace.rowPadding + labelRoom, end = RvSpace.rowPadding, bottom = RvSpace.tight)) {
                RvText(format.clock(heat.starts.first()), style, Modifier.weight(1f), color = Rv.colors.textSecondary)
                RvText(format.clock(heat.starts.last() + heat.width), style, color = Rv.colors.textSecondary, align = TextAlign.End)
            }
        }
    }
}

private const val SPAN = "–"

/**
 * 事件 (doona web's 事件, `GET /events`): the caption says how many events the page keeps, the stream's state and that
 * it can resume; the chip picks the kinds (by default all but 運作狀態更新). Each event is its summary over its kind,
 * with its time; one that names a flow, the configuration or the recorder opens it. 匯出 JSON writes what is listed.
 */
@Composable
internal fun RvEventsSample(events: List<RvEvent> = RvDiagData.events) {
    val format = rememberRvFormat()
    RvDiagPage(
        sampleText("事件", "Events"),
        caption = sampleText(
            "最近 ${RvDiagData.EVENT_LIMIT} 筆，新事件在前 · 已連線 · 可從上次位置續傳",
            "Latest ${RvDiagData.EVENT_LIMIT}, newest first · Connected · Resumable",
        ),
        control = { RvFilterChip(sampleText("排除運作狀態更新", "Without runtime updates"), {}) },
    ) {
        RvGutter {
            RvCard {
                events.forEach { e ->
                    RvRow(
                        null,
                        e.summary.text(),
                        e.kind.text() + SEPARATOR + format.clock(e.atEpochMs),
                        onClick = e.link?.let { {} },
                        titleKeepWords = false,
                        subtitleKeepWords = false,
                        trailing = e.link?.let { { RvChevron() } },
                    )
                }
            }
            RvCard { RvDiagNavRow(R.drawable.rv_sym_download, sampleText("匯出 JSON", "Export JSON")) }
        }
    }
}
