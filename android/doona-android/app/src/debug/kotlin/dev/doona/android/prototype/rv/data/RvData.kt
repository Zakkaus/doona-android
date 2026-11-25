package dev.doona.android.prototype.rv.data

import dev.doona.android.R
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRuleOutbound
import dev.doona.android.feature.model.RvSession
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.fastest

/**
 * The one invented dataset every RV page reads, at 2026-11-22 10:24 UTC (the harness's status bar reads 10:24). An
 * earlier connection ran until 01:24 (a photo backup uploaded at 01:00) and another from 07:05 to 08:31; the current one
 * started at 08:42, so it has run 1 小時 42 分. Today's hours are those connections' bytes, so the session is part of
 * today, and its last minute is part of its last hour. The node in use is a Hong Kong node of Harbor Cloud, through the
 * auto group 自動; it routes by rules, so that node is the main node and two rule groups use nodes of their own
 * ([ruleOutbounds]). Every name and figure is invented.
 */
internal object RvData {
    val nowEpochMs = RV_NOW

    /** Where now falls in the day, in hours from midnight UTC: 10.4. */
    val nowHour: Float = (RV_NOW % MS_PER_DAY).toFloat() / MS_PER_HOUR

    val nodes: List<RvNode> = rvNodes()
    val testTimes: List<Long> = rvTestTimes()
    val current: RvNode = nodes.first { it.id == CURRENT_ID }

    /** Nodes in places the 48 leave out ([rvWorldNodes]): for the map's pans and the node list's parts of the world. */
    val worldNodes: List<RvNode> = rvWorldNodes(nodes)

    fun node(id: String): RvNode? = nodes.firstOrNull { it.id == id } ?: worldNodes.firstOrNull { it.id == id }

    val subscriptions = listOf(
        RvSubscription(RV_HARBOR_ID, RV_HARBOR, nodes.count { it.subscription == RV_HARBOR }, UPDATED, 61_700_000_000, 200_000_000_000, 1_805_112_000_000, "sub.harbor-cloud.example", RvSubFormat.Clash),
        RvSubscription(RV_LANTERN_ID, RV_LANTERN, nodes.count { it.subscription == RV_LANTERN }, UPDATED, 36_400_000_000, 100_000_000_000, 1_798_718_400_000, "lantern-net.example", RvSubFormat.Links),
    )

    val session = RvSession(SESSION_START, RV_NOW, SESSION_DOWN, SESSION_UP, PEAK_DOWN, PEAK_UP, RvMinute.down, RvMinute.up)

    val today = RvDay(EARLIER_DOWN.zip(SESSION_DOWN) { a, b -> a + b }, EARLIER_UP.zip(SESSION_UP) { a, b -> a + b }, CONNECTIONS)

    /** The same day before its first byte: the 統計 page's quiet sample. */
    val quietDay = RvDay(today.down.map { 0L }, today.up.map { 0L }, null)

    /** Both directions yesterday by 10:24. */
    const val YESTERDAY_BY_NOW = 2_862_000_000L

    val split = RvSplit(PROXY_BYTES, today.total - PROXY_BYTES)

    /**
     * The rule groups that sent traffic today other than to the main node, most bytes first: 串流 through Taipei 04, the
     * domestic and LAN rule with every byte that went direct (分流比例's direct share), and AI through Tokyo 02. The main
     * node carried the rest of the proxied bytes.
     */
    val ruleOutbounds: List<RvRuleOutbound> = listOf(
        RvRuleOutbound(R.string.rv_rule_streaming, "tw-04", STREAMING_BYTES),
        RvRuleOutbound(R.string.rv_rule_domestic, null, split.directBytes),
        RvRuleOutbound(R.string.rv_rule_ai, "jp-02", AI_BYTES),
    ).sortedByDescending { it.bytes }

    /** The rules in the profile; 活動 lists the few that sent traffic today. */
    const val RULES = 14

    /** The auto group the global outbound uses, by the name the subscription's template gives it. */
    const val AUTO_GROUP = "自動"

    /** Settings: how often subscriptions refresh (the last refresh was at 06:00); 依 App's apps are RvPerAppData's. */
    const val UPDATE_HOURS = 12

    const val CURRENT_ID = "hk-02"
}

internal const val RV_HARBOR = "Harbor Cloud"
internal const val RV_LANTERN = "Lantern Net"

/** The sample subscriptions' ids, which the nodes name their subscription by. */
internal const val RV_HARBOR_ID = "s1"
internal const val RV_LANTERN_ID = "s2"

/** 2026-11-22 10:24:00 UTC. */
internal const val RV_NOW = 1_795_343_040_000L

/** 08:42:00, 6,120 s before now. */
private const val SESSION_START = 1_795_336_920_000L

/** The subscriptions were refreshed at 06:00. */
private const val UPDATED = 1_795_327_200_000L

/** Bytes an hour from 00:00 of the earlier connections: late browsing and the 01:00 backup, then the morning one. */
private val EARLIER_DOWN = listOf<Long>(142_318_604, 38_207_551, 0, 0, 0, 0, 0, 96_441_027, 61_093_318, 0, 0)
private val EARLIER_UP = listOf<Long>(9_418_230, 86_302_117, 0, 0, 0, 0, 0, 7_150_938, 4_211_574, 0, 0)

/** The current connection's bytes an hour: 18 minutes of hour 8, all of hour 9 (a video call uploads), 24 of hour 10. */
private val SESSION_DOWN = listOf<Long>(0, 0, 0, 0, 0, 0, 0, 0, 212_408_163, 979_552_804, 648_039_033)
private val SESSION_UP = listOf<Long>(0, 0, 0, 0, 0, 0, 0, 0, 13_025_391, 70_886_402, 42_088_207)

/** Its fastest second each way, earlier in the session than the last minute. */
private const val PEAK_DOWN = 7_836_412f
private const val PEAK_UP = 1_314_208f

/** Connections opened each hour (a v0.2 figure); none while disconnected. */
private val CONNECTIONS = listOf(96, 31, 0, 0, 0, 0, 0, 118, 142, 203, 84)

private const val PROXY_BYTES = 1_641_870_000L

/** Of the proxied bytes, what the streaming and AI rules sent to their own nodes. */
private const val STREAMING_BYTES = 1_196_420_000L
private const val AI_BYTES = 182_350_000L

private const val MS_PER_HOUR = 3_600_000f
private const val MS_PER_DAY = 86_400_000L
