package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

/** [NativeConnections] against the examples of honk's `/connections` contract. */
class NativeConnectionsTest {
    private val honk = HonkFake()
    private val connections = NativeConnections(honk)

    @Test
    fun listJoinsTcpAndUdpNewestFirst() {
        honk.get(LIST_PATH, HonkExamples.fixture("connections/connections-example.json"))

        val (udp, tcp) = connections.list().rows

        assertEquals(
            HonkConnection(
                id = "udp-01HZX4K8W6",
                udp = true,
                host = "8.8.8.8",
                port = 53,
                hasDomain = false,
                process = null,
                outbound = "direct",
                chain = emptyList(),
                ruleId = null,
                ruleExpression = null,
                startedAtEpochMs = Instant.parse("2026-08-15T09:59:55Z").toEpochMilli(),
                upBytes = 0,
                downBytes = 0,
            ),
            udp,
        )
        assertEquals("example.com", tcp.host)
        assertEquals(443, tcp.port)
        assertEquals("curl", tcp.process)
        assertEquals(listOf("group-proxy", "node-hk-01"), tcp.chain)
        assertEquals(20_480L to 1_048_576L, tcp.upBytes to tcp.downBytes)
    }

    @Test
    fun anIpv6DestinationLosesItsBrackets() {
        val body = HonkExamples.fixture("connections/connections-example.json").replace("\"8.8.8.8:53\"", "\"[2001:db8::1]:53\"")
        honk.get(LIST_PATH, body)

        assertEquals("2001:db8::1" to 53, connections.list().rows.first().let { it.host to it.port })
    }

    @Test
    fun closeDeletesTheIdAndMapsAGoneOneToNotFound() {
        honk.on("DELETE", "/api/v1/connections/tcp-01HZX4K8W5", 204, "")
        honk.on("DELETE", "/api/v1/connections/gone", 404, HonkExamples.fixture("connections/close-gone-example.json"))
        honk.on("DELETE", "/api/v1/connections/kernel", 409, "")

        connections.close("tcp-01HZX4K8W5")
        assertEquals(ErrorCode.NotFound, assertThrows(EngineException::class.java) { connections.close("gone") }.code)
        assertEquals(ErrorCode.InvalidArgument, assertThrows(EngineException::class.java) { connections.close("kernel") }.code)
    }

    @Test
    fun closeAllPermitsTheUnfilteredCloseAndReturnsTheCount() {
        honk.on("DELETE", "/api/v1/connections?all=true", 200, HonkExamples.fixture("connections/close-all-example.json"))

        assertEquals(2, connections.closeAll())
    }

    private companion object {
        const val LIST_PATH = "/api/v1/connections?limit=1000&detail=full"
    }
}
