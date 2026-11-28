package dev.doona.android.feature.connections

import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.honk.HonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
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
    fun closeClosesTheConnectionAndReadsAgain() {
        honk.on("DELETE", "/api/v1/connections/tcp-sample-01", 204, "")
        val reading = model.load(NOW, "tcp-sample-01")
        honk.on("GET", LIST_PATH, 200, HonkFixtures.body("connections/connections-example"))

        var closed = false
        model.close("tcp-sample-01") { closed = true }
        assertTrue(closed)
        main.scheduler.advanceTimeBy(MS_PER_SECOND + 1)
        reading.cancel()

        assertEquals(1, honk.calls.count { it.method == "DELETE" && it.path == "/api/v1/connections/tcp-sample-01" })
        assertEquals(listOf("udp-01HZX4K8W6", "tcp-01HZX4K8W5"), model.ui.value.connections.map { it.id })
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
