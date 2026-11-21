package dev.doona.android.model

// Sample data in the shape of doona's mock backend (mock/fixtures/inventory.ts, mock/fixtures/configuration.ts): the
// inline nodes hk-01, hk-02, sg-01, jp-01, us-01, the "harbor" subscription's regional nodes, the template's region
// groups and the proxy, office and backup groups.

enum class Region(val code: String) { HK("hk"), JP("jp"), TW("tw"), SG("sg"), US("us"), KR("kr") }

enum class Mode { Rule, Direct, Global }

enum class Tone { Ok, Warn, Err, Neutral }

enum class PolicyKind(val manual: Boolean) { Selector(true), Fixed(true), UrlTest(false), Fallback(false) }

/** Latency colours (`src/ui/Tile.tsx:79`): under 100 ms ok, under 300 ms warn, otherwise err. */
fun latencyTone(ms: Int?): Tone = when {
    ms == null -> Tone.Neutral
    ms < 100 -> Tone.Ok
    ms < 300 -> Tone.Warn
    else -> Tone.Err
}

data class Member(
    val name: String,
    val region: Region? = null,
    val source: String? = null,
    val latencyMs: Int?,
    val isGroup: Boolean = false,
)

data class PolicyGroup(
    val name: String,
    val kind: PolicyKind,
    val healthy: Int,
    val untested: Int = 0,
    val tcp: Member,
    /** Set when UDP goes through another member than TCP. */
    val udp: Member? = null,
)

data class Activity(
    val profile: String,
    val connected: Boolean,
    val mode: Mode,
    val globalOutbound: String,
    val downloadRate: String,
    val download: List<Float>,
    val uploadRate: String,
    val upload: List<Float>,
    val connections: Int,
    val connectionHistory: List<Float>,
    val latencyNode: Member,
    val latencyHistory: List<Float>,
    val cpu: String,
    val cpuHistory: List<Float>,
)

data class GroupDetail(
    val group: PolicyGroup,
    val includes: List<String>,
    val memberCount: Int,
    /** Members sorted by latency, as doona's default. */
    val members: List<Member>,
)

object Demo {
    private val hk01 = Member("hk-01", Region.HK, latencyMs = 84)
    private val hk02 = Member("hk-02", Region.HK, latencyMs = 91)
    private val jp = Member("jp", latencyMs = 32, isGroup = true)

    // Two slow waves around a level: a steady minute of samples for the sparklines.
    private fun series(level: Float, swing: Float, seed: Int) = List(30) { i ->
        val t = (i + seed).toFloat()
        level + swing * (0.6f * kotlin.math.sin(t / 4.5f) + 0.4f * kotlin.math.sin(t / 1.7f + seed))
    }

    val activity = Activity(
        profile = "家中 honk",
        connected = true,
        mode = Mode.Rule,
        globalOutbound = "proxy",
        downloadRate = "3.9 MB/s",
        download = series(3.6f, 0.5f, 1),
        uploadRate = "330 KB/s",
        upload = series(300f, 60f, 5),
        connections = 53,
        connectionHistory = series(50f, 4f, 9),
        latencyNode = hk01,
        latencyHistory = series(84f, 8f, 3),
        cpu = "3.3%",
        cpuHistory = series(3.2f, 0.8f, 7),
    )

    val groups = listOf(
        PolicyGroup("proxy", PolicyKind.Selector, healthy = 130, untested = 2, tcp = jp, udp = hk02),
        PolicyGroup("office", PolicyKind.Fixed, healthy = 5, tcp = hk01),
        PolicyGroup("auto", PolicyKind.UrlTest, healthy = 128, tcp = Member("日本 01 2x", Region.JP, "harbor", 32)),
        PolicyGroup("hk", PolicyKind.UrlTest, healthy = 14, tcp = Member("香港 07 IPLC", Region.HK, "harbor", 61)),
        PolicyGroup("jp", PolicyKind.UrlTest, healthy = 13, tcp = Member("日本 01 2x", Region.JP, "harbor", 32)),
        PolicyGroup("us", PolicyKind.UrlTest, healthy = 12, untested = 1, tcp = Member("美國 02 BGP", Region.US, "harbor", 172)),
        PolicyGroup("tw", PolicyKind.UrlTest, healthy = 13, tcp = Member("台灣 04 家寬", Region.TW, "harbor", 44)),
        PolicyGroup("backup", PolicyKind.Fallback, healthy = 126, tcp = Member("sg-01", Region.SG, latencyMs = 63)),
    )

    /** Group counts as on doona's kind filter: 17 groups, 8 picked by hand, 9 automatic. */
    const val GROUPS_ALL = 17
    const val GROUPS_MANUAL = 8
    const val GROUPS_AUTO = 9

    val proxy = GroupDetail(
        group = groups.first(),
        includes = listOf("auto", "hk", "jp", "us", "tw", "sg", "kr"),
        memberCount = 132,
        members = listOf(
            Member("auto", latencyMs = 32, isGroup = true),
            jp,
            Member("日本 01 2x", Region.JP, "harbor", 32),
            Member("日本 05 BGP", Region.JP, "harbor", 35),
            Member("日本 03 IPLC", Region.JP, "harbor", 41),
            Member("台灣 04 家寬", Region.TW, "harbor", 44),
            Member("日本 06 IPLC", Region.JP, "harbor", 47),
            Member("香港 07 IPLC", Region.HK, "harbor", 61),
            Member("sg-01", Region.SG, latencyMs = 63),
            Member("韓國 02 解鎖", Region.KR, "harbor", 66),
            hk01,
            hk02,
        ),
    )
}
