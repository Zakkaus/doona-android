package dev.doona.android.prototype.rv.diag

import androidx.compose.runtime.Composable
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvFilterChip
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.prototype.rv.samples.sampleText

/**
 * 日誌檢視 (doona web's 日誌, `GET /logs`): the caption says the level honk records from and the stream's state; the
 * chips pick the lowest level shown and a target prefix. 日誌時間分布 gives the web's level map in figures: the records
 * of each level and the busiest minute. Each record is its level's glyph, its message (with its typed fields), and its
 * time, level and target under it; a gap in the stream is a row of its own. 暫停 holds the list,
 * 匯出 writes it, 清空 empties it, 記錄設定 opens the recorder's mode and level.
 */
@Composable
internal fun RvLogsSample(records: List<RvLogRecord> = RvDiagData.logs) {
    val format = rememberRvFormat()
    val logged = records.filterNot { it.gap }
    val counts = logged.groupingBy { it.level }.eachCount()
    val peak = logged.groupingBy { it.atEpochMs / RvDiagData.MINUTE }.eachCount().entries
        .maxWithOrNull(compareBy<Map.Entry<Long, Int>> { it.value }.thenByDescending { it.key })?.key
    RvDiagPage(
        sampleText("日誌檢視", "Logs"),
        caption = sampleText("引擎記錄：資訊及以上 / 串流中", "Engine records info and above / Streaming"),
        control = { RvFilterChipRow(RvChipPick(sampleText("資訊及以上", "Info and above")), RvChipPick(sampleText("全部模組", "All targets"))) },
    ) {
        RvDiagSection(sampleText("日誌時間分布", "Log activity"))
        RvGutter {
            RvCard {
                RvLevel.entries.filter { it != RvLevel.Debug || it in counts }.forEach { level ->
                    val n = counts[level] ?: 0
                    RvStatRow(level.word.text(), sampleText("$n 筆", if (n == 1) "1 record" else "$n records"))
                }
                RvStatRow(sampleText("記錄最多的時段", "Busiest"), peak?.let { "${format.time(it * RvDiagData.MINUTE)}–${format.time((it + 1) * RvDiagData.MINUTE)}" } ?: "—")
            }
        }
        RvDiagSection(sampleText("記錄", "Records"))
        RvGutter {
            RvCard {
                records.forEach { r ->
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
            "最近 ${RvDiagData.EVENT_LIMIT} 筆，新事件在前 / 已連線 / 可從上次位置續傳",
            "Latest ${RvDiagData.EVENT_LIMIT}, newest first / Connected / Resumable",
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
