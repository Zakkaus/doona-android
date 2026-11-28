package dev.doona.android.feature.connections

import dev.doona.android.R
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.honk.HonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [ConnectionsViewModel]: 連線 from honk's `/connections` with its groups and nodes, and closing one. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {
    private val honk = HonkFixtures.rvSample().apply { on("GET", LIST_PATH, 200, HonkFixtures.body("connections/connections-sample")) }
    private val model = ConnectionsViewModel(honk, CoreNodePlaces, { NOW }, io = Dispatchers.Unconfined)
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun itShowsNothingBeforeItsFirstRead() {
        assertFalse(model.ui.value.loaded)
        assertTrue(honk.calls.isEmpty())
    }

    @Test
    fun loadReadsHonksConnectionsNewestFirst() {
        model.load(NOW).cancel()

        val ui = model.ui.value
        assertTrue(ui.loaded)
        assertEquals(NOW, ui.nowEpochMs)
        assertEquals(listOf("Chrome", "ChatGPT", "WeChat", "YouTube", "Telegram", "Netflix", "Gmail"), ui.connections.map { it.title })
    }

    @Test
    fun theSummaryRetainsTheTotalBeyondTheLoadedPage() {
        val sample = Json.parseToJsonElement(HonkFixtures.body("connections/connections-sample")).jsonObject
        val tcp = sample.getValue("tcp").jsonArray.first().jsonObject
        val page = sample + ("tcp" to JsonArray(List(999) { JsonObject(tcp + ("id" to JsonPrimitive("tcp-$it"))) }))
        for ((tcpCount, expected) in listOf(1000L to 1001L, 999L to null)) {
            val response = JsonObject(page + ("truncated" to JsonPrimitive(expected != null)) + ("total_tcp" to JsonPrimitive(tcpCount)))
            honk.on("GET", LIST_PATH, 200, response.toString())
            model.load(NOW).cancel()
            assertEquals(1000, model.ui.value.connections.size)
            assertEquals(expected, model.ui.value.truncatedTotal)
        }
    }

    @Test
    fun rotatingWhileClosingDoesNotResendAndKeepsTheRefusal() = runTest {
        val vm = ConnectionsViewModel(honk, CoreNodePlaces, { NOW }, StandardTestDispatcher(testScheduler))
        val id = "tcp-sample-01"
        honk.on("DELETE", "/api/v1/connections/$id", 409, """{"error":{"code":"state_conflict","message":"Connection is not owned by a closable userspace transport"},"request_id":"r1"}""")
        val first = vm.load(NOW, id)
        testScheduler.runCurrent()
        vm.close(NOW, id)
        first.cancel()
        val restored = vm.load(NOW, id)
        vm.close(NOW, id)
        testScheduler.runCurrent()
        assertEquals(R.string.rv_conn_not_closable, vm.closeNotices[id])
        vm.close(NOW, id)
        testScheduler.runCurrent()
        assertEquals(1, honk.calls.count { it.method == "DELETE" })
        restored.cancel()
    }

    @Test
    fun anUnconfirmedCloseCanBeRetriedUntilItSucceeds() {
        val id = "tcp-sample-01"
        model.load(NOW).cancel()
        honk.on("DELETE", "/api/v1/connections/$id", 503, HonkFixtures.body("errors/temporarily-unavailable"))
        model.close(NOW, id)
        assertEquals(R.string.rv_conn_close_unknown, model.closeNotices[id])
        honk.on("DELETE", "/api/v1/connections/$id", 204, "")
        model.close(NOW, id)
        assertTrue(id in model.closeNotices && model.closeNotices[id] == null)
        assertTrue(honk.calls.drop(honk.calls.indexOfLast { it.method == "DELETE" }).any { it.path == LIST_PATH })
    }

    @Test
    fun aFailedReadAfterReconnectClearsThePreviousSession() {
        model.load(NOW).cancel()
        honk.on("GET", LIST_PATH, 503, HonkFixtures.body("errors/temporarily-unavailable-example"))
        model.load(NOW + 1).cancel()
        assertFalse(model.ui.value.loaded)
        assertTrue(model.ui.value.connections.isEmpty())
    }

    @Test
    fun aFailedReadKeepsWhatWasShown() {
        val reading = model.load(NOW, "tcp-sample-01")
        honk.on("GET", LIST_PATH, 200, HonkFixtures.body("connections/connections-example"))
        val catalogCalls = honk.calls.count { it.path != LIST_PATH }
        main.scheduler.advanceTimeBy(MS_PER_SECOND + 1)
        val ids = model.ui.value.connections.map { it.id }
        assertEquals(listOf("udp-01HZX4K8W6", "tcp-01HZX4K8W5"), ids)
        assertEquals(catalogCalls, honk.calls.count { it.path != LIST_PATH })

        honk.on("GET", LIST_PATH, 503, HonkFixtures.body("errors/temporarily-unavailable-example"))
        main.scheduler.advanceTimeBy(MS_PER_SECOND + 1)
        reading.cancel()
        assertTrue(model.ui.value.error != null)
        assertEquals(ids, model.ui.value.connections.map { it.id })
    }

    private companion object {
        const val LIST_PATH = "/api/v1/connections?limit=1000&detail=full"
        const val NOW = 1_795_343_040_000L
    }
}
