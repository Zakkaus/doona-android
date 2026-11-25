package dev.doona.android.engine.fake

import dev.doona.android.engine.DayTotal
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.OutboundBytes
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.Usage

/** The RV prototype's 10:24 UTC snapshot, including the exact minute plotted by its traffic chart. */
internal object RvSampleTraffic {
    const val START = 1_795_336_920_000L
    private const val NOW = 1_795_343_040_000L
    private const val YESTERDAY_BY_NOW = 2_862_000_000L
    private const val HOUR = 3_600
    private const val TODAY_AT = 24 * 60

    private val upMinute = listOf<Long>(
        6713, 7949, 8093, 8938, 8135, 8512, 7006, 142630, 53710, 7695,
        6803, 7438, 6400, 5097, 6058, 6367, 8118, 8406, 8183, 189017,
        154654, 198856, 169271, 143208, 154176, 201600, 134723, 183313, 171954, 136303,
        155771, 137226, 97253, 61059, 30672, 8446, 7971, 6513, 5923, 6253,
        5514, 7663, 133420, 49287, 9018, 9606, 8426, 9093, 9393, 397094,
        724639, 769078, 732297, 720069, 758079, 778716, 344459, 9350, 131416, 47617,
    )
    private val downMinute = listOf<Long>(
        60321, 42121, 56590, 40374, 51589, 60049, 69860, 4260180, 2407345, 2843577,
        1635753, 943628, 551220, 328691, 204952, 124569, 81159, 73374, 49462, 1468069,
        2680508, 3885718, 3965654, 3325904, 4042465, 4084166, 3425618, 3801706, 4074039, 3268289,
        3490110, 4064086, 2290133, 1425076, 567373, 53413, 51292, 37789, 42379, 51138,
        43429, 34830, 5627513, 3568086, 3939813, 2512361, 1604672, 1039958, 672382, 431357,
        279826, 187332, 151028, 126141, 96051, 76777, 64100, 59508, 3470713, 1981209,
    )
    private val upSession = listOf(13_025_391L, 70_886_402L, 42_088_207L)
    private val downSession = listOf(212_408_163L, 979_552_804L, 648_039_033L)

    val traffic = Traffic(
        upRate = upMinute.last(), downRate = downMinute.last(), upTotal = upSession.sum(), downTotal = downSession.sum(),
        upHistory = upMinute, downHistory = downMinute, activeFlows = 7, upPeak = 1_314_208, downPeak = 7_836_412,
    )
    val series = SampleSeries(NOW, hour(upSession, upMinute, traffic.upPeak), hour(downSession, downMinute, traffic.downPeak))
    val today = TodayTraffic(traffic.upTotal + 107_082_859, traffic.downTotal + 338_060_500, YESTERDAY_BY_NOW)
    val usage = usage()

    /** Before the plotted minute, spread each hour's bytes evenly apart from the session peak at 09:42. */
    private fun hour(session: List<Long>, minute: List<Long>, peak: Long): List<Long> {
        val morningRate = (session[1] - peak) / (HOUR - 1)
        val laterSeconds = TODAY_AT - minute.size
        val laterBytes = session[2] - minute.sum()
        return List(HOUR) { second ->
            val offset = second - (HOUR - TODAY_AT)
            when {
                second == 18 * 60 -> peak
                offset < 0 -> morningRate
                offset < laterSeconds -> laterBytes / laterSeconds + if (offset < laterBytes % laterSeconds) 1 else 0
                else -> minute[offset - laterSeconds]
            }
        }
    }

    private fun usage(): Usage {
        val day = DayUsage(
            date = "2026-11-22",
            up = day(listOf(9_418_230, 86_302_117, 0, 0, 0, 0, 0, 7_150_938, 17_236_965, upSession[1], upSession[2])),
            down = day(listOf(142_318_604, 38_207_551, 0, 0, 0, 0, 0, 96_441_027, 273_501_481, downSession[1], downSession[2])),
            flows = day(listOf(96, 31, 0, 0, 0, 0, 0, 118, 142, 203, 84)),
            outbounds = emptyList(),
        )
        val byRule = ruleBytes(day)
        val outbounds = listOf(
            OutboundBytes("direct", byRule[2].up + byRule[3].up, byRule[2].down + byRule[3].down),
            OutboundBytes("g-AI", byRule[1].up, byRule[1].down),
            OutboundBytes(FakeRuleMode.MAIN_GROUP_ID, byRule[4].up, byRule[4].down),
            OutboundBytes("g-Streaming", byRule[0].up, byRule[0].down),
        )
        val yesterday = DayUsage(
            "2026-11-21",
            day(List(9) { 0L } + 262_000_000L),
            day(List(9) { 0L } + 2_600_000_000L),
            day(emptyList()),
            listOf(OutboundBytes(FakeRuleMode.MAIN_GROUP_ID, 262_000_000, 2_600_000_000)),
        )
        return Usage(day.copy(outbounds = outbounds, byRule = byRule), yesterday, listOf(DayTotal(yesterday.date, 262_000_000, 2_600_000_000)))
    }

    private fun ruleBytes(day: DayUsage): List<RuleBytes> {
        val all = today.upTotal + today.downTotal
        val proxy = 1_641_870_000L
        val streaming = 1_196_420_000L
        val ai = 182_350_000L
        val lan = 41_206_118L
        val flows = day.flows.sum()
        val known = listOf("streaming" to streaming, "ai" to ai, "cn" to all - proxy - lan, "lan" to lan).map { (id, bytes) ->
            val up = bytes * today.upTotal / all
            RuleBytes("r-$id", up, bytes - up, bytes * flows / all)
        }
        val up = today.upTotal - known.sumOf(RuleBytes::up)
        return known + RuleBytes("r-match", up, proxy - streaming - ai - up, flows - known.sumOf(RuleBytes::flows))
    }

    private fun day(hours: List<Long>) = List(24) { hours.getOrElse(it) { 0L } }
}
