package dev.doona.android.vpn

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Mode
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Parses every payload in `contracts/doona-core/`, the examples both sides of the JNI contract test against. */
class CoreDtoTest {
    private val fixtures: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { it.resolve("contracts/doona-core") }
        .first { it.isDirectory }

    private fun fixture(name: String) = fixtures.resolve(name).readText()

    @Test
    fun everyFixtureHasAParser() {
        val checked = setOf(
            "init.json", "start-config.json", "snapshot-running.json", "snapshot-died.json", "snapshot-stopped-catalog.json",
            "call-ok-empty.json", "call-ok-subscription.json", "call-error.json", "call-select-args.json",
        )
        val present = fixtures.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.toSet()
        assertEquals(checked, present)
    }

    @Test
    fun init() {
        assertEquals(CORE_SCHEMA, CoreJson.schema(fixture("init.json")))
    }

    @Test
    fun startConfigEncodesAsTheFixture() {
        val encoded = CoreJson.encode(StartConfigDto.serializer(), StartConfigDto.of(listOf("192.168.8.1")))
        assertEquals(Json.parseToJsonElement(fixture("start-config.json")), Json.parseToJsonElement(encoded))
    }

    @Test
    fun snapshots() {
        val running = CoreJson.snapshot(fixture("snapshot-running.json"))
        assertTrue(running.alive)
        assertEquals(52428800L, running.traffic.downTotal)
        assertNull(running.catalog)

        val died = CoreJson.snapshot(fixture("snapshot-died.json"))
        assertFalse(died.alive)
        assertEquals("internal", died.error)
    }

    @Test
    fun catalogMapsToEngineTypes() {
        val catalog = checkNotNull(CoreJson.snapshot(fixture("snapshot-stopped-catalog.json")).catalog)
        assertEquals(Mode.Global, catalog.mode)
        val groups = catalog.groups.map(GroupDto::toGroup)
        assertEquals(listOf("g-Proxy", "g-Auto", "g-HK"), groups.map { it.id })
        val auto = groups[1]
        assertEquals(GroupKind.UrlTest, auto.kind)
        assertTrue(auto.testing)
        // Two distinct endpoints may share a display name; their ids differ.
        assertEquals(listOf("hk-01", "hk-01"), auto.members.map { it.name })
        assertEquals(2, auto.members.map { it.id }.toSet().size)
        assertTrue(groups[0].members.first().isGroup)

        val subscription = catalog.subscriptions.single().toSubscription()
        assertEquals("s1", subscription.id)
        assertEquals(107374182400L, subscription.totalBytes)
        assertNull(subscription.error)
    }

    @Test
    fun callReplies() {
        assertEquals(Unit, CoreJson.result(fixture("call-ok-empty.json"), Unit.serializer()))
        val subscription = CoreJson.result(fixture("call-ok-subscription.json"), SubscriptionDto.serializer())
        assertEquals(2, subscription.nodes)
        try {
            CoreJson.result(fixture("call-error.json"), Unit.serializer())
            fail("an error reply must throw")
        } catch (e: EngineException) {
            assertEquals(ErrorCode.FetchFailed, e.code)
        }
    }

    @Test
    fun selectArgsEncodeAsTheFixture() {
        val encoded = CoreJson.encode(SelectArgs.serializer(), SelectArgs("g-Proxy", "n-0c41be77"))
        assertEquals(Json.parseToJsonElement(fixture("call-select-args.json")), Json.parseToJsonElement(encoded))
        val auto = CoreJson.encode(SelectArgs.serializer(), SelectArgs("g-Proxy", null))
        assertEquals("""{"group":"g-Proxy","memberId":null}""", auto)
    }

    @Test
    fun unknownErrorCodeIsInternal() {
        assertEquals(ErrorCode.NoNodes, errorCodeOf("no_nodes"))
        assertEquals(ErrorCode.Internal, errorCodeOf("from_a_newer_core"))
    }
}
