package dev.doona.android.prototype.rv.diag

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvFieldStack
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchPill
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSettingsCard
import dev.doona.android.designsystem.rv.RvSettingsNavRow
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvSwitchRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.prototype.rv.samples.sampleText
import java.text.NumberFormat
import java.util.Locale

/**
 * DNS 查詢 (doona web's DNS › 查詢, `POST /dns/query`): the name to ask, then the request's settings as settings rows
 * (類型, 上游 with 自動 leaving the pick to `dns.routing`, 略過快取), and 查詢 at the bottom. A [result] follows under
 * its name and type: whether the cache answered, the status, the upstream, the route's source and rule, and the time;
 * then 回答記錄, each answer's data over its owner (when it is not the name asked, as down a CNAME chain), type and TTL.
 * An answer opens the quick rule for its address; 新增規則 starts one from the name; 檢視快取 opens DNS 快取 on it.
 */
@Composable
internal fun RvDnsQuerySample(result: RvDnsResult? = RvDiagData.query) {
    RvDiagPage(sampleText("DNS 查詢", "DNS query"), bottomAction = { RvPrimaryButton(sampleText("查詢", "Query"), {}) }) {
        RvGutter {
            RvFieldStack { RvTextField("www.youtube.com", {}, label = sampleText("網域", "Domain")) }
            RvSettingsCard {
                RvSettingsNavRow(sampleText("類型", "Type"), R.drawable.rv_sym_dns, {}, "A")
                RvSettingsNavRow(sampleText("上游", "Upstream"), R.drawable.rv_sym_link, {}, sampleText("自動", "Automatic"))
                RvSwitchRow(R.drawable.rv_sym_sync_alt, sampleText("略過快取", "Bypass cache"), false, onChange = {})
            }
        }
        result?.let { Result(it) }
    }
}

@Composable
private fun Result(result: RvDnsResult) {
    RvDiagSection(result.domain + " " + result.type)
    RvGutter {
        RvCard {
            RvStatRow(sampleText("快取", "Cache"), cacheText(result.cached))
            RvStatRow(sampleText("狀態", "State"), result.status)
            RvStatRow(sampleText("上游", "Upstream"), result.upstream ?: NONE, valueKeepWords = false)
            RvStatRow(sampleText("路由來源", "Route source"), result.routeSource.text())
            RvStatRow(sampleText("路由規則", "Route rule"), result.routeRule ?: NONE, valueKeepWords = false)
            RvStatRow(sampleText("耗時", "Elapsed"), ms(result.elapsedMs))
        }
    }
    Answers(result.answers, result.domain, result.status) {
        RvCard { RvAddRow(sampleText("新增規則", "Add rule"), {}) }
        RvCard { RvDiagNavRow(R.drawable.rv_sym_memory, sampleText("檢視快取", "View cache")) }
    }
}

/** 回答記錄: each answer as a row, or with none the library's empty caption with the [status] honk answered; then [after]. */
@Composable
private fun Answers(answers: List<RvDnsAnswer>, asked: String, status: String, after: @Composable ColumnScope.() -> Unit) {
    RvDiagSection(sampleText("回答記錄", "Answers"))
    RvGutter {
        if (answers.isEmpty()) {
            RvEmptyCaption(sampleText("沒有回答記錄", "No answers"), status)
        } else {
            RvCard {
                answers.forEach { a ->
                    val owner = a.owner?.takeUnless { it.equals(asked, ignoreCase = true) }
                    val line = listOfNotNull(owner, a.type, sampleText("TTL ${a.ttl} 秒", "TTL ${a.ttl} s")).joinToString(SEPARATOR)
                    RvRow(null, a.data, line, onClick = {}, titleKeepWords = false, subtitleKeepWords = false)
                }
            }
        }
        after()
    }
}

/**
 * DNS 快取 (doona web's DNS › 快取, `GET /dns/cache`): the bar's capsule matches names (the web's 比對內容, whose
 * matches 刪除符合項目 deletes); the caption counts the runtime cache and says it lives only in memory; the chips pick a
 * type and whether expired entries show. Each entry is its name, its type, when it expires and until when honk may
 * still serve it stale, with its status at the end; a tap offers 新增規則 and 刪除. 清除全部快取 ends the page.
 */
@Composable
internal fun RvDnsCacheSample(entries: List<RvDnsCacheEntry> = RvDiagData.cache) {
    val format = rememberRvFormat()
    val count = NumberFormat.getIntegerInstance(Locale.ROOT).format(RvDiagData.CACHE_ENTRIES)
    RvDiagPage(
        sampleText("DNS 快取", "DNS cache"),
        caption = sampleText("$count 筆快取項目 · 僅存於記憶體，重新啟動後清空", "$count entries · in memory only, cleared on restart"),
        control = { RvFilterChipRow(RvChipPick(sampleText("全部類型", "All types")), RvChipPick(sampleText("不含已到期", "Without expired"))) },
        field = { glass, modifier -> RvSearchPill({}, modifier, glass, hint = sampleText("比對網域", "Match names")) },
    ) {
        RvGutter {
            RvCard {
                entries.forEach { e ->
                    val expires = sampleText("到期 ${format.time(e.expiresAtEpochMs)}", "expires ${format.time(e.expiresAtEpochMs)}")
                    val stale = e.staleUntilEpochMs?.let { sampleText("逾期可用至 ${format.time(it)}", "stale until ${format.time(it)}") }
                    RvRow(
                        null,
                        e.domain,
                        listOfNotNull(e.type, expires, stale).joinToString(SEPARATOR),
                        onClick = {},
                        titleEnd = { RvEndValue(e.status) },
                        titleKeepWords = false,
                    )
                }
            }
            RvCard { RvRow({ RvIconDisc(R.drawable.rv_sym_delete) }, sampleText("清除全部快取", "Clear all cache"), null, onClick = {}) }
        }
    }
}

/**
 * DNS 記錄 (doona web's DNS › 解析記錄, `GET /dns/log`): the bar's capsule filters names; the caption counts what honk
 * retains and what is loaded; the chips pick a type and a device. Each record is the name asked over its type and its
 * result (the answers, or the failing status in the error hue), its time over where the answer came from (快取命中, or
 * the upstream and its time); a tap opens the record. 載入較舊記錄 loads the next page, 匯出 CSV writes what is loaded.
 */
@Composable
internal fun RvDnsLogSample(records: List<RvDnsLogRecord> = RvDiagData.dnsLog) {
    val format = rememberRvFormat()
    RvDiagPage(
        sampleText("DNS 記錄", "DNS log"),
        caption = sampleText("已保留 ${RvDiagData.LOG_RETAINED} 筆記錄 · 已載入 ${RvDiagData.LOG_LOADED} 筆", "${RvDiagData.LOG_RETAINED} retained · ${RvDiagData.LOG_LOADED} loaded"),
        control = { RvFilterChipRow(RvChipPick(sampleText("全部類型", "All types")), RvChipPick(sampleText("全部裝置", "All devices"))) },
        field = { glass, modifier -> RvSearchPill({}, modifier, glass, hint = sampleText("網域包含", "Domain contains")) },
    ) {
        RvGutter {
            RvCard {
                records.forEach { r ->
                    val failed = r.status != OK
                    // The row names what the question asked for; a CNAME on the way is on the record's page.
                    val result = if (failed) r.status else r.answers.filter { it.type == r.type }.joinToString(LIST) { it.data }
                    RvRow(
                        null,
                        r.name,
                        r.type + SEPARATOR + result,
                        onClick = {},
                        titleEnd = { RvEndValue(format.clock(r.atEpochMs)) },
                        subtitleEnd = { RvEndSub(if (r.cached) cacheText(true) else listOfNotNull(r.upstream, ms(r.elapsedMs)).joinToString(SEPARATOR)) },
                        titleKeepWords = false,
                        subtitleError = failed,
                    )
                }
                RvSeeAll(sampleText("載入較舊記錄", "Load older records")) {}
            }
            RvCard { RvDiagNavRow(R.drawable.rv_sym_download, sampleText("匯出 CSV", "Export CSV")) }
        }
    }
}

/**
 * A DNS record (doona web's 解析記錄 drawer): the name, its type and time; the facts (類型, 裝置, 狀態, 快取, 上游,
 * 路由來源, 路由規則, 耗時, 時間), 回答記錄, and 新增規則 from the record.
 */
@Composable
internal fun RvDnsRecordSample(record: RvDnsLogRecord = RvDiagData.dnsLog.first()) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val clock = rememberRvFormat().clock(record.atEpochMs)
    RvScrollPage(record.name, insets, onBack = {}) { titleBlock ->
        RvPageHeader(record.name, titleBlock, insets, caption = { RvCaption(record.type + SEPARATOR + clock) })
        RvGutter {
            RvCard {
                RvStatRow(sampleText("類型", "Type"), record.type)
                RvStatRow(sampleText("裝置", "Device"), record.src, valueKeepWords = false)
                RvStatRow(sampleText("狀態", "State"), record.status)
                RvStatRow(sampleText("快取", "Cache"), cacheText(record.cached))
                RvStatRow(sampleText("上游", "Upstream"), record.upstream ?: NONE, valueKeepWords = false)
                RvStatRow(sampleText("路由來源", "Route source"), record.routeSource.text())
                RvStatRow(sampleText("路由規則", "Route rule"), record.routeRule ?: NONE, valueKeepWords = false)
                RvStatRow(sampleText("耗時", "Elapsed"), ms(record.elapsedMs))
                RvStatRow(sampleText("時間", "Time"), clock)
            }
        }
        Answers(record.answers, record.name, record.status) { RvCard { RvAddRow(sampleText("新增規則", "Add rule"), {}) } }
    }
}

@Composable
private fun cacheText(cached: Boolean): String = if (cached) sampleText("快取命中", "Cache hit") else sampleText("快取未命中", "Cache miss")

private fun ms(n: Int): String = rvCount(n.toLong()) + " ms"

private const val OK = "NOERROR"
private const val NONE = "—"
private const val LIST = ", "
