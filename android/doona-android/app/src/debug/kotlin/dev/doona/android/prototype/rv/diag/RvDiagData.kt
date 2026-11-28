package dev.doona.android.prototype.rv.diag

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.samples.sampleText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A word of the samples in zh-TW, English and zh-CN; a value honk prints as it is (an address, a code) is the same in all three. */
@Immutable
internal class RvWord(val zh: String, val en: String = zh, private val zhCn: String = zh) {
    @Composable
    fun text(): String = sampleText(zh, zhCn, en)
}

/** One fact of a card: its label and its value. */
@Immutable
internal class RvFact(val label: RvWord, val value: RvWord)

/** A connection state as `GET /flows` reports it (`observed` to `unknown`), in doona web's words. */
internal enum class RvFlowState(val word: RvWord, val trouble: Boolean = false) {
    Observed(RvWord("已觀測", "Observed", "已观测")),
    Active(RvWord("進行中", "Active")),
    Closed(RvWord("已關閉", "Closed")),
    Blocked(RvWord("已封鎖", "Blocked")),
    Failed(RvWord("失敗", "Failed"), trouble = true),
}

/** Where a flow went: a group and the node it picked, or honk's `direct` or `block`. */
@Immutable
internal class RvFlowOut(val group: RvWord, val node: String? = null)

/** A recorded stage of a flow's trace (`FlowStep`): its stage, when it was observed, its elapsed time and its fields. */
@Immutable
internal class RvFlowStep(val stage: RvWord, val atEpochMs: Long?, val elapsedUs: Long?, val facts: List<RvFact>)

/**
 * One record of `GET /flows` with the parts of `GET /flows/{id}` the samples show: the routing input (domain or
 * destination, the client, the app's uid as the package it maps to), the rule that decided in the generation that
 * routed it ([recomputed] when honk had to evaluate it again), the outbound and its chain, the state, the start, and the
 * trace's status, revision, missing reasons and steps.
 */
@Immutable
internal class RvFlow(
    val id: String,
    val target: String,
    val port: Int,
    val app: String,
    val packageName: String,
    val udp: Boolean,
    val rule: String?,
    val out: RvFlowOut?,
    val state: RvFlowState,
    val startedAtEpochMs: Long,
    val recomputed: Boolean = false,
    val complete: Boolean = true,
    val revision: Int = 1,
    val missing: List<RvWord> = emptyList(),
    val connectionId: String? = null,
    val steps: List<RvFlowStep> = emptyList(),
)

/** A tile of the flow map (`flow.map`): a rule (or a device), an outbound, or a node, with the flows through it. */
@Immutable
internal class RvMapTile(
    val title: RvWord,
    val line: RvWord?,
    val flows: Int,
    val place: RvPlace? = null,
    val code: Boolean = false,
    @DrawableRes val glyph: Int? = null,
)

/** The flow map by rule: the rules on the left, the outbounds they send to, the nodes the groups picked. */
@Immutable
internal class RvFlowMap(val rules: List<RvMapTile>, val devices: List<RvMapTile>, val outbounds: List<RvMapTile>, val nodes: List<RvMapTile>)

/** The six populations `GET /flows` reports coverage for, with honk's answer on a phone: userspace partial, kernel none. */
@Immutable
internal class RvCoverage(val scope: RvWord, val value: RvWord)

/** One answer record: its owner when it is not the name asked, its type, TTL and data. */
@Immutable
internal class RvDnsAnswer(val owner: String?, val type: String, val ttl: Int, val data: String)

/** One result of `POST /dns/query`: a type's status, the cache, the upstream, the route and the answers. */
@Immutable
internal class RvDnsResult(
    val domain: String,
    val type: String,
    val status: String,
    val cached: Boolean,
    val upstream: String?,
    val routeSource: RvWord,
    val routeRule: String?,
    val elapsedMs: Int,
    val answers: List<RvDnsAnswer>,
)

/** One entry of `GET /dns/cache`: an exact incarnation of a name and type, its status, expiry and stale end. */
@Immutable
internal class RvDnsCacheEntry(val domain: String, val type: String, val status: String, val expiresAtEpochMs: Long, val staleUntilEpochMs: Long?)

/** One record of `GET /dns/log`: a completed client query, with what answered it and how. */
@Immutable
internal class RvDnsLogRecord(
    val name: String,
    val type: String,
    val src: String?,
    val status: String,
    val cached: Boolean,
    val upstream: String?,
    val routeSource: RvWord,
    val routeRule: String?,
    val elapsedMs: Int,
    val atEpochMs: Long,
    val answers: List<RvDnsAnswer>,
)

/** A log level as `GET /logs` names it, its words, and the glyph its rows lead with. */
internal enum class RvLevel(val word: RvWord, @DrawableRes val glyph: Int) {
    Error(RvWord("錯誤", "Error"), R.drawable.rv_sym_error),
    Warn(RvWord("警告", "Warning"), R.drawable.rv_nodes_ic_warning),
    Info(RvWord("資訊", "Info"), R.drawable.rv_sym_info),
    Debug(RvWord("除錯", "Debug"), R.drawable.rv_settings_ic_description),
    Trace(RvWord("追蹤", "Trace", "跟踪"), R.drawable.rv_settings_ic_description),
}

/** One record of the native log stream: its time, level, target, message and typed fields; or the gap marker. */
@Immutable
internal class RvLogRecord(val atEpochMs: Long, val level: RvLevel, val target: String?, val message: String, val fields: JsonObject? = null, val gap: Boolean = false)

/** One native event (`GET /events`): its kind, its summary and when honk observed it. */
@Immutable
internal class RvEvent(val kind: RvWord, val summary: RvWord, val atEpochMs: Long, val link: RvWord? = null)

/** The sample data of the diagnostic pages, in the shapes honk's native API returns (pr/483 `doc/en/reference/api.md`). */
internal object RvDiagData {
    private val streaming = RvWord("串流", "Streaming")
    private val ai = RvWord("AI")
    private val main = RvWord("主節點", "Main node")
    private val direct = RvWord("直連", "Direct")
    private val block = RvWord("拒絕", "Reject")
    private const val HK = "Hong Kong 02"
    private const val TOKYO = "Tokyo 01"
    private const val SINGAPORE = "Singapore 03"
    private const val YOUTUBE = "com.google.android.youtube"
    private const val SECOND = 1_000L
    const val MINUTE = 60_000L

    /** The trace of the first flow: input, route, dial mode, outbound and connection, a few microseconds apart. */
    private val youtubeSteps = run {
        val at = RV_NOW - 8 * SECOND
        listOf(
            RvFlowStep(
                RvWord("輸入", "Input"),
                at,
                0,
                listOf(
                    RvFact(RvWord("裝置", "Device"), RvWord("172.19.0.1:41822")),
                    RvFact(RvWord("目標位址", "Target address"), RvWord("142.250.196.206:443")),
                    RvFact(RvWord("網域", "Domain"), RvWord("www.youtube.com")),
                    RvFact(RvWord("網域來源", "Domain source"), RvWord("TLS SNI")),
                    RvFact(RvWord("協定", "Protocol"), RvWord("TCP")),
                    RvFact(RvWord("UID"), RvWord("10187")),
                ),
            ),
            RvFlowStep(
                RvWord("路由", "Route"),
                at,
                41,
                listOf(
                    RvFact(RvWord("鏈", "Chain"), RvWord("routing")),
                    RvFact(RvWord("平面", "Plane"), RvWord("使用者空間", "Userspace")),
                    RvFact(RvWord("規則", "Rule"), RvWord("domain(geosite:youtube)")),
                    RvFact(RvWord("出站", "Outbound"), streaming),
                    RvFact(RvWord("must"), RvWord("否", "No")),
                ),
            ),
            RvFlowStep(
                RvWord("撥號模式", "Dial mode"),
                at,
                58,
                listOf(
                    RvFact(RvWord("撥號目標", "Dial target"), RvWord("domain → www.youtube.com:443")),
                    RvFact(RvWord("驗證", "Verification"), RvWord("不需要", "Not required")),
                ),
            ),
            RvFlowStep(
                RvWord("出站", "Outbound"),
                at,
                212,
                listOf(
                    RvFact(RvWord("出站", "Outbound"), RvWord("串流 → $HK", "Streaming → $HK")),
                    RvFact(RvWord("選擇路徑", "Selection path"), RvWord("streaming → $HK")),
                    RvFact(RvWord("末端節點 ID", "Leaf node ID"), RvWord("hk-02")),
                    RvFact(RvWord("目標", "Target"), RvWord("www.youtube.com:443")),
                    RvFact(RvWord("狀態", "State"), RvWord("成功", "Succeeded")),
                ),
            ),
            RvFlowStep(
                RvWord("連線", "Connection"),
                at + 48,
                48_306,
                listOf(
                    RvFact(RvWord("狀態", "State"), RvWord("進行中", "Active")),
                    RvFact(RvWord("里程碑", "Milestone"), RvWord("目標已確認", "Target confirmed")),
                ),
            ),
        )
    }

    /** Eight flows of the last minutes, newest first: proxied, direct, blocked, failed and one with a partial trace. */
    val flows: List<RvFlow> = listOf(
        RvFlow(
            "f-7c21", "www.youtube.com", 443, "YouTube", YOUTUBE, udp = false, "domain(geosite:youtube)", RvFlowOut(streaming, HK), RvFlowState.Active,
            RV_NOW - 8 * SECOND, revision = 6, connectionId = "c-2214", steps = youtubeSteps,
        ),
        RvFlow("f-7c1e", "chatgpt.com", 443, "ChatGPT", "com.openai.chatgpt", udp = false, "domain(suffix:chatgpt.com)", RvFlowOut(ai, TOKYO), RvFlowState.Active, RV_NOW - 140 * SECOND),
        RvFlow(
            "f-7c18", "rr3---sn-a5mekn6d.googlevideo.com", 443, "YouTube", YOUTUBE, udp = true, "domain(geosite:youtube)", RvFlowOut(streaming, HK),
            RvFlowState.Active, RV_NOW - 3 * MINUTE,
        ),
        RvFlow("f-7c11", "ad.doubleclick.net", 443, "Chrome", "com.android.chrome", udp = false, "domain(geosite:category-ads-all)", RvFlowOut(block), RvFlowState.Blocked, RV_NOW - 4 * MINUTE),
        RvFlow(
            "f-7c0b", "91.108.56.130", 443, "Telegram", "org.telegram.messenger", udp = false, "dip(91.108.56.0/22)", RvFlowOut(main, SINGAPORE), RvFlowState.Active,
            RV_NOW - 5 * MINUTE, recomputed = true, complete = false, missing = listOf(RvWord("中途開始記錄", "Recording started late")),
        ),
        RvFlow("f-7c02", "imap.gmail.com", 993, "Gmail", "com.google.android.gm", udp = false, "fallback", RvFlowOut(main, SINGAPORE), RvFlowState.Failed, RV_NOW - 6 * MINUTE),
        RvFlow("f-7bf4", "szshort.weixin.qq.com", 443, "WeChat", "com.tencent.mm", udp = false, "dip(geoip:cn)", RvFlowOut(direct), RvFlowState.Closed, RV_NOW - 7 * MINUTE),
        RvFlow("f-7be9", "time.android.com", 123, "Android", "android", udp = true, "l4proto(udp) && dport(123)", RvFlowOut(direct), RvFlowState.Closed, RV_NOW - 9 * MINUTE),
    )

    /** The map of [flows]: seven rules, five outbounds, three nodes. */
    val map = RvFlowMap(
        rules = listOf(
            RvMapTile(RvWord("domain(geosite:youtube)"), RvWord("→ 串流", "→ Streaming"), 2, code = true),
            RvMapTile(RvWord("dip(91.108.56.0/22)"), RvWord("→ 主節點", "→ Main node"), 1, code = true),
            RvMapTile(RvWord("fallback"), RvWord("→ 主節點", "→ Main node"), 1, code = true),
            RvMapTile(RvWord("domain(suffix:chatgpt.com)"), RvWord("→ AI"), 1, code = true),
            RvMapTile(RvWord("dip(geoip:cn)"), RvWord("→ 直連", "→ Direct"), 1, code = true),
            RvMapTile(RvWord("l4proto(udp) && dport(123)"), RvWord("→ 直連", "→ Direct"), 1, code = true),
            RvMapTile(RvWord("domain(geosite:category-ads-all)"), RvWord("→ 拒絕", "→ Reject"), 1, code = true),
        ),
        devices = listOf(RvMapTile(RvWord("172.19.0.1"), RvWord("→ 串流、主節點、AI、直連、拒絕", "→ Streaming, Main node, AI, Direct, Reject"), 8)),
        outbounds = listOf(
            RvMapTile(streaming, RvWord("手動選擇 → $HK", "Manual → $HK"), 2),
            RvMapTile(main, RvWord("手動選擇 → $SINGAPORE", "Manual → $SINGAPORE"), 2),
            RvMapTile(direct, null, 2, glyph = R.drawable.rv_sym_arrow_right_alt),
            RvMapTile(ai, RvWord("手動選擇 → $TOKYO", "Manual → $TOKYO"), 1),
            RvMapTile(block, null, 1, glyph = R.drawable.rv_sym_block),
        ),
        nodes = listOf(
            RvMapTile(RvWord(HK), RvWord("← 串流 / 48 ms", "← Streaming / 48 ms"), 2, RvPlace.HK),
            RvMapTile(RvWord(SINGAPORE), RvWord("← 主節點 / 182 ms", "← Main node / 182 ms"), 2, RvPlace.SG),
            RvMapTile(RvWord(TOKYO), RvWord("← AI / 62 ms"), 1, RvPlace.JP),
        ),
    )

    /** honk on a phone: userspace TCP, UDP and intercepted DNS are partial, the kernel's populations none. */
    val coverage = listOf(
        RvCoverage(RvWord("使用者空間 TCP", "Userspace TCP"), RvWord("部分", "Partial")),
        RvCoverage(RvWord("使用者空間 UDP", "Userspace UDP"), RvWord("部分", "Partial")),
        RvCoverage(RvWord("DNS 攔截", "DNS interception"), RvWord("部分", "Partial")),
        RvCoverage(RvWord("核心直連", "Kernel direct"), RvWord("無", "None")),
        RvCoverage(RvWord("核心封鎖", "Kernel block"), RvWord("無", "None")),
        RvCoverage(RvWord("核心旁路", "Kernel bypass"), RvWord("無", "None")),
    )

    private val byRules = RvWord("DNS 路由規則", "DNS routing rules")
    private val byDefault = RvWord("預設", "Default")

    /** `www.youtube.com` A through Google's DoH upstream, a miss: the CNAME and the two addresses under it. */
    private val queryA = RvDnsResult(
        "www.youtube.com.",
        "A",
        "NOERROR",
        cached = false,
        "googledns",
        byRules,
        null,
        38,
        listOf(
            RvDnsAnswer(null, "CNAME", 86_400, "youtube-ui.l.google.com."),
            RvDnsAnswer("youtube-ui.l.google.com.", "A", 300, "142.250.196.206"),
            RvDnsAnswer("youtube-ui.l.google.com.", "A", 300, "142.250.196.238"),
        ),
    )
    val query = listOf(queryA, RvDnsResult(queryA.domain, "AAAA", "TIMEOUT", false, null, byRules, null, 9_962, emptyList()))

    /** Total usage is independent of the unexpired entries returned on this page. */
    const val CACHE_ENTRIES = 1_284
    const val CACHE_CAPACITY = 4_096
    val cache = listOf(
        RvDnsCacheEntry("www.youtube.com.", "A", "NOERROR", RV_NOW + 5 * MINUTE, RV_NOW + 10 * MINUTE),
        RvDnsCacheEntry("www.youtube.com.", "AAAA", "NOERROR", RV_NOW + 5 * MINUTE, RV_NOW + 10 * MINUTE),
        RvDnsCacheEntry("chatgpt.com.", "HTTPS", "NOERROR", RV_NOW + 2 * MINUTE, null),
        RvDnsCacheEntry("szshort.weixin.qq.com.", "A", "NOERROR", RV_NOW + 9 * MINUTE, RV_NOW + 14 * MINUTE),
        RvDnsCacheEntry("ipv6.msftconnecttest.com.", "AAAA", "NODATA", RV_NOW + 3 * MINUTE, null),
        RvDnsCacheEntry("telemetry.invalid.", "A", "NXDOMAIN", RV_NOW + 1 * MINUTE, null),
        RvDnsCacheEntry("time.android.com.", "A", "NOERROR", RV_NOW + 12 * MINUTE, RV_NOW + 17 * MINUTE),
    )

    const val LOG_RETAINED = 512
    val dnsLog = listOf(
        RvDnsLogRecord("www.youtube.com.", "A", "172.19.0.1:38211", "NOERROR", false, "googledns", byRules, null, 38, RV_NOW - 9 * SECOND, queryA.answers),
        RvDnsLogRecord(
            "www.youtube.com.", "AAAA", "172.19.0.1:38212", "NOERROR", true, null, byRules, null, 0, RV_NOW - 9 * SECOND,
            listOf(RvDnsAnswer(null, "CNAME", 86_400, "youtube-ui.l.google.com."), RvDnsAnswer("youtube-ui.l.google.com.", "AAAA", 300, "2404:6800:4012:5::200e")),
        ),
        RvDnsLogRecord("chatgpt.com.", "HTTPS", "172.19.0.1:40116", "NOERROR", false, "cloudflare", byDefault, null, 52, RV_NOW - 140 * SECOND, listOf(RvDnsAnswer(null, "HTTPS", 300, "1 . alpn=h3,h2"))),
        RvDnsLogRecord("telemetry.invalid.", "A", "172.19.0.1:40502", "NXDOMAIN", false, "alidns", byDefault, null, 21, RV_NOW - 3 * MINUTE, emptyList()),
        RvDnsLogRecord("imap.gmail.com.", "A", "172.19.0.1:36004", "SERVFAIL", false, "googledns", byRules, null, 5_000, RV_NOW - 6 * MINUTE, emptyList()),
        RvDnsLogRecord("szshort.weixin.qq.com.", "A", "172.19.0.1:35870", "NOERROR", false, "alidns", byRules, null, 14, RV_NOW - 7 * MINUTE, listOf(RvDnsAnswer(null, "A", 600, "183.3.226.35"))),
        RvDnsLogRecord("time.android.com.", "A", "172.19.0.1:33178", "NOERROR", true, null, byDefault, null, 0, RV_NOW - 9 * MINUTE, listOf(RvDnsAnswer(null, "A", 1_800, "216.239.35.4"))),
        RvDnsLogRecord("dns.google.", "A", null, "NOERROR", false, "alidns", byDefault, null, 12, RV_NOW - 10 * MINUTE, listOf(RvDnsAnswer(null, "A", 300, "8.8.8.8"))),
    )

    /** The log: info and above as honk records it, with one gap; most messages are withheld as honk withholds them. */
    private const val WITHHELD = "[message withheld: not audited for native disclosure]"
    val logs = listOf(
        RvLogRecord(RV_NOW - 47 * SECOND, RvLevel.Warn, "honk_core::outbound::health", WITHHELD),
        RvLogRecord(RV_NOW - 2 * MINUTE, RvLevel.Error, "honk_core::outbound::dial", WITHHELD),
        RvLogRecord(RV_NOW - 2 * MINUTE, RvLevel.Info, null, "", gap = true),
        RvLogRecord(
            RV_NOW - 3 * MINUTE,
            RvLevel.Info,
            "honk_core::control::runtime",
            "Standalone DNS listener started",
            buildJsonObject {
                put("native_withheld_fields", true)
                put("tcp", false)
                put("udp", true)
            },
        ),
        RvLogRecord(RV_NOW - 3 * MINUTE, RvLevel.Info, "honk_core", "native API listener ready", buildJsonObject { put("native_withheld_fields", true) }),
        RvLogRecord(RV_NOW - 3 * MINUTE, RvLevel.Warn, "honk_core::dns::upstream", WITHHELD),
    )

    /** Events, newest first, runtime updates left out as the page's default kind leaves them out. */
    val events = listOf(
        RvEvent(RvWord("流程更新", "Flow updated"), RvWord("f-7c21，修訂：6", "f-7c21, revision 6"), RV_NOW - 8 * SECOND, RvWord("檢視流程記錄", "View flow record")),
        RvEvent(RvWord("流程更新", "Flow updated"), RvWord("f-7c1e，修訂：3", "f-7c1e, revision 3"), RV_NOW - 21 * SECOND, RvWord("檢視流程記錄", "View flow record")),
        RvEvent(
            RvWord("流程記錄遺失", "Flow records lost"),
            RvWord("原因：記錄已達保留上限，本次記錄期間累計捨棄：12", "Reason: retention limit reached, 12 dropped this recording"),
            RV_NOW - 2 * MINUTE,
            RvWord("記錄設定", "Recording settings"),
        ),
        RvEvent(RvWord("操作更新", "Operation updated"), RvWord("op-19，狀態：succeeded", "op-19, status: succeeded"), RV_NOW - 3 * MINUTE),
        RvEvent(RvWord("組態生效", "Configuration active"), RvWord("41 至 42", "41 to 42"), RV_NOW - 3 * MINUTE, RvWord("檢視組態", "View configuration")),
        RvEvent(RvWord("串流就緒", "Stream ready"), RvWord("cursor e1:4f2a"), RV_NOW - 4 * MINUTE),
    )
    const val EVENT_LIMIT = 50
}
