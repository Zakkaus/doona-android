package dev.doona.android.engine.fake

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.Member
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Subscription

/** Endpoints and probe rounds from the RV prototype; zero in a probe row means it timed out. */
internal object RvSampleNodes {
    private val samples = listOf(
        node("hk1", "Hong Kong 01", Proto.Trojan, 36, 33, 33, 39, 20, 84, 39, 33),
        node("hk2", "Hong Kong 02 IPLC", Proto.Vless, 31, 29, 47, 34, 34, 34, 35, 32),
        node("hk3", "Hong Kong 03", Proto.Ss, 40, 47, 57, 43, 52, 42, 42, 46),
        node("hk4", "Hong Kong 04", Proto.Hy2, 55, 71, 37, 52, 0, 61, 57, 49),
        node("hk5", "Hong Kong 05 IEPL", Proto.TrojanWs, 34, 44, 37, 70, 38, 36, 41, 36),
        node("hk6", "Hong Kong 06", Proto.Vmess, 137, 80, 57, 68, 63, 58, 71, 73),
        node("tw1", "Taipei 01", Proto.Trojan, 22, 23, 26, 22, 23, 0, 28, 27),
        node("tw2", "Taipei 02", Proto.Hy2, 32, 30, 21, 14, 20, 23, 29, 27),
        node("tw3", "Taichung 03", Proto.Ss, 39, 29, 36, 47, 35, 27, 35, 33),
        node("tw4", "Taipei 04", Proto.Vless, 27, 28, 26, 26, 30, 28, 26, 26),
        node("tw5", "Kaohsiung 05", Proto.Tuic, 36, 36, 38, 39, 41, 70, 46, 50),
        node("jp1", "Tokyo 01", Proto.Vless, 53, 68, 60, 59, 53, 52, 63, 61),
        node("jp2", "Tokyo 02", Proto.Trojan, 70, 58, 72, 61, 55, 55, 67, 49),
        node("jp3", "Osaka 03", Proto.Hy2, 67, 77, 83, 110, 88, 71, 83, 61),
        node("jp4", "Tokyo 04 IPLC", Proto.Ss, 68, 69, 66, 64, 54, 64, 67, 59),
        node("jp5", "Osaka 05", Proto.Vmess, 82, 78, 0, 0, 72, 85, 0, 78),
        node("jp6", "Tokyo 06", Proto.Tuic, 78, 56, 63, 62, 57, 56, 59, 51),
        node("kr1", "Seoul 01", Proto.Trojan, 80, 107, 80, 67, 73, 75, 71, 79),
        node("kr2", "Seoul 02", Proto.Vless, 90, 84, 91, 81, 124, 67, 87, 88),
        node("kr3", "Chuncheon 03", Proto.Ss, 89, 91, 94, 96, 100, 100, 0, 93),
        node("sg1", "Singapore 01", Proto.Trojan, 85, 83, 93, 79, 86, 86, 83, 77),
        node("sg2", "Singapore 02", Proto.Hy2, 130, 150, 193, 93, 117, 111, 116, 86),
        node("sg3", "Singapore 03", Proto.Vless, 92, 88, 97, 104, 131, 158, 172, 186),
        node("sg4", "Singapore 04", Proto.Ss, 95, 103, 157, 93, 103, 92, 98, 108),
        node("sg5", "Singapore 05", Proto.TrojanWs, 108, 172, 110, 109, 103, 98, 111, 103),
        node("us1", "Los Angeles 01", Proto.Vless, 165, 154, 162, 122, 151, 144, 165, 142),
        node("us2", "San Jose 02", Proto.Trojan, 169, 154, 165, 159, 165, 165, 199, 162),
        node("us3", "Seattle 03", Proto.Hy2, 186, 160, 186, 150, 281, 248, 184, 174),
        node("us4", "Los Angeles 04", Proto.Ss, 149, 154, 173, 166, 167, 159, 159, 166),
        node("us5", "New York 05", Proto.Vmess, 212, 284, 240, 206, 217, 207, 223, 0),
        node("us6", "Dallas 06", Proto.Tuic, 217, 229, 200, 199, 199, 202, 198, 193),
        node("ca1", "Vancouver 01", Proto.Trojan, 171, 177, 180, 176, 211, 177, 174, 173),
        node("ca2", "Toronto 02", Proto.Vless, 246, 276, 339, 248, 240, 252, 221, 226),
        node("gb1", "London 01", Proto.Trojan, 238, 242, 229, 0, 242, 234, 251, 233),
        node("gb2", "London 02", Proto.Hy2, 247, 251, 250, 253, 277, 270, 239, 259),
        node("gb3", "Manchester 03", Proto.Ss, 255, 277, 248, 261, 256, 258, 256, 254),
        node("gb4", "London 04", Proto.Vless, 237, 278, 242, 238, 273, 241, 243, 291),
        node("de1", "Frankfurt 01", Proto.Vless, 221, 224, 223, 213, 222, 225, 217, 243),
        node("de2", "Frankfurt 02", Proto.Trojan, 230, 213, 216, 230, 227, 226, 235, 267),
        node("de3", "Berlin 03", Proto.Tuic, 240, 267, 227, 238, 256, 211, 228, 261),
        node("nl1", "Amsterdam 01", Proto.Trojan, 246, 230, 223, 244, 242, 296, 229, 223),
        node("nl2", "Amsterdam 02", Proto.Ss, 240, 247, 249, 279, 239, 246, 252, 247),
        node("nl3", "Rotterdam 03", Proto.Vmess, 258, 259, 276, 267, 281, 278, 261, 271),
        node("fr1", "Paris 01", Proto.Vless, 221, 239, 241, 232, 234, 242, 275, 237),
        node("fr2", "Marseille 02", Proto.Hy2, 222, 201, 221, 223, 215, 228, 225, 232),
        node("au1", "Sydney 01", Proto.Trojan, 134, 129, 118, 102, 193, 123, 115, 123),
        node("au2", "Melbourne 02", Proto.Vless, 144, 191, 137, 139, 131, 143, 143, 135),
        node("au3", "Sydney 03", Proto.Ss, 143, 125, 127, 128, 110, 136, 169, 120),
    )
    val details = samples.map(Sample::info)
    val subscriptions = listOf(
        Subscription("s1", "Harbor Cloud", "https://sub.harbor-cloud.example/sub", 32, UPDATED, usedBytes = 61_700_000_000, totalBytes = 200_000_000_000, expiresAtEpochMs = 1_805_112_000_000),
        Subscription("s2", "Lantern Net", "https://lantern-net.example/sub", 16, UPDATED, usedBytes = 36_400_000_000, totalBytes = 100_000_000_000, expiresAtEpochMs = 1_798_718_400_000),
    )
    val groups = buildGroups()

    private fun buildGroups(): List<Group> {
        val members = samples.mapIndexed { index, sample ->
            val latency = sample.info.tests.last().ms
            Member(
                sample.info.id,
                sample.name,
                latency,
                failed = latency == null,
                subscriptionId = if (index % 3 == 2) "s2" else "s1",
                // As the core reads it from the name: each sample host starts with its ISO code.
                region = sample.host.take(2).uppercase(),
            )
        }
        val byRegion = samples.indices.groupBy({ samples[it].host.take(2).uppercase() }, { members[it] })
        fun regions(vararg codes: String) = codes.flatMap(byRegion::getValue)
        return listOf(
            Group(FakeRuleMode.MAIN_GROUP_ID, "Proxy", GroupKind.Selector, members, id("hk2")),
            automatic("Auto", members),
            Group("g-Streaming", "Streaming", GroupKind.Selector, regions("HK", "TW", "JP", "SG", "US"), id("tw4")),
            Group("g-AI", "AI", GroupKind.Selector, regions("TW", "JP", "SG", "US"), id("jp2")),
        ) + listOf("HK", "TW", "JP", "US").map { automatic(it, byRegion.getValue(it)) }
    }

    private fun automatic(name: String, members: List<Member>) =
        Group("g-$name", name, GroupKind.UrlTest, members, members.minBy { it.latencyMs ?: Int.MAX_VALUE }.id)

    private fun node(host: String, name: String, proto: Proto, vararg history: Int): Sample {
        val tests = history.mapIndexed { i, ms -> LatencyTest(LAST_TEST - (history.lastIndex - i) * HALF_HOUR, ms.takeUnless { it == 0 }) }
        return Sample(host, name, NodeInfo(id(host), proto.protocol, "$host.example.net", proto.port, proto.transport, proto.tls, tests))
    }

    private fun id(host: String) = "n-${host.hashCode().toString(16).padStart(8, '0')}00000000"

    private class Sample(val host: String, val name: String, val info: NodeInfo)

    private enum class Proto(val protocol: String, val transport: String, val port: Int, val tls: Boolean = true) {
        Trojan("trojan", "tcp", 443),
        TrojanWs("trojan", "ws", 443),
        Vless("vless", "grpc", 443),
        Vmess("vmess", "ws", 8443),
        Ss("ss", "tcp", 8388, false),
        Hy2("hysteria2", "quic", 443),
        Tuic("tuic", "quic", 8443),
    }

    private const val UPDATED = 1_795_327_200_000L
    private const val LAST_TEST = 1_795_341_600_000L
    private const val HALF_HOUR = 1_800_000L
}
