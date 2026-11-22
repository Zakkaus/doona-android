package dev.doona.android.prototype.rv

import kotlin.random.Random

/**
 * Today's traffic hour by hour, for the 今日用量 card: bytes each way in each hour from midnight, the last hour still
 * running and [nowHour] hours into the day, and what yesterday had used by the same time.
 */
internal class RvUsageDay(val down: List<Long>, val up: List<Long>, val nowHour: Float, val yesterdayByNow: Long) {
    val downTotal = down.sum()
    val upTotal = up.sum()
    val total = downTotal + upTotal
}

/**
 * An invented day, drawn from a fixed seed. Download is quiet overnight, browses through the morning, peaks with a long
 * video after lunch and is still going at 15:24. Upload has its own day: a photo backup at 02:00, a video call from
 * 09:00 to 11:00, and a few MB an hour otherwise. Each hour varies by up to 12 % around its profile.
 */
internal object RvUsageSample {
    fun today(): RvUsageDay {
        val random = Random(SEED)
        fun jitter(mb: Int) = (mb * MB * (1 + JITTER * (2 * random.nextFloat() - 1))).toLong()
        return RvUsageDay(DOWN_MB.map(::jitter), UP_MB.map(::jitter), NOW_HOUR, YESTERDAY_BY_NOW)
    }

    /** Megabytes an hour from 00:00; the last hour has run for 24 minutes. */
    private val DOWN_MB = listOf(28, 12, 9, 7, 6, 8, 35, 96, 160, 74, 62, 88, 170, 520, 220, 190)
    private val UP_MB = listOf(4, 3, 48, 3, 2, 3, 5, 9, 11, 30, 34, 7, 6, 9, 4, 3)

    private const val SEED = 20_261_123
    private const val MB = 1_000_000L
    private const val JITTER = 0.12f
    private const val NOW_HOUR = 15.4f
    private const val YESTERDAY_BY_NOW = 2_210_000_000L
}

/** A subscription in the 訂閱 card: its letter in a generic name, traffic used of its quota in GB, and its end date. */
internal class RvSubscriptionUse(val letter: String, val usedGb: Int, val totalGb: Int, val ends: String) {
    val leftGb = totalGb - usedGb
}

/** Two invented subscriptions with generic names. */
internal val RV_SAMPLE_SUBSCRIPTIONS = listOf(
    RvSubscriptionUse("A", usedGb = 32, totalGb = 100, ends = "12/31"),
    RvSubscriptionUse("B", usedGb = 112, totalGb = 200, ends = "03/15"),
)

/** A node in the 最快節點 card: its latency and how much it changed since the test before. */
internal class RvFastNode(val name: String, val protocol: String, val latencyMs: Int, val changeMs: Int)

/** The three fastest nodes of the node screen's sample group, with invented changes. */
internal val RV_SAMPLE_FASTEST = listOf(
    RvFastNode("hk-01", "Trojan", latencyMs = 42, changeMs = -6),
    RvFastNode("🇭🇰 HK 02 IPLC", "Shadowsocks", latencyMs = 57, changeMs = 9),
    RvFastNode("Taipei HiNet", "Trojan", latencyMs = 71, changeMs = -3),
)
