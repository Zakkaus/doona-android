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

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun itShowsNothingBeforeItsFirstRead() {
        assertFalse(model.ui.value.loaded)
        assertTrue(honk.calls.isEmpty())
    }

    @Test
    fun loadReadsHonksConnectionsNewestFirst() {
        model.load()

        val ui = model.ui.value
        assertTrue(ui.loaded)
        assertEquals(NOW, ui.nowEpochMs)
        assertEquals(listOf("Chrome", "ChatGPT", "WeChat", "YouTube", "Telegram", "Netflix", "Gmail"), ui.connections.map { it.title })
    }

    @Test
    fun closeClosesTheConnectionAndReadsAgain() {
        honk.on("DELETE", "/api/v1/connections/tcp-sample-01", 204, "")
        model.load()
        honk.on("GET", LIST_PATH, 200, HonkFixtures.body("connections/connections-example"))

        model.close("tcp-sample-01")

        assertEquals(1, honk.calls.count { it.method == "DELETE" && it.path == "/api/v1/connections/tcp-sample-01" })
        assertEquals(listOf("udp-01HZX4K8W6", "tcp-01HZX4K8W5"), model.ui.value.connections.map { it.id })
    }

    @Test
    fun oneAlreadyGoneIsReadAgainAllTheSame() {
        honk.on("DELETE", "/api/v1/connections/gone", 404, HonkFixtures.body("connections/close-gone-example"))

        model.close("gone")

        assertEquals(7, model.ui.value.connections.size)
    }

    @Test
    fun aFailedReadKeepsWhatWasShown() {
        model.load()
        honk.on("GET", LIST_PATH, 503, HonkFixtures.body("errors/temporarily-unavailable-example"))

        model.load()

        assertEquals(7, model.ui.value.connections.size)
    }

    private companion object {
        const val LIST_PATH = "/api/v1/connections?limit=1000&detail=full"
        const val NOW = 1_795_343_040_000L
    }
}
