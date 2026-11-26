package dev.doona.android.feature.settings

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvDnsUpstream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** DNS reads the core's servers from its effective config and saves through `setDnsUpstreams`, which also checks 自訂. */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val DnsViewModel.state: DnsUiState get() = checkNotNull(uiState.value) { "the core has not answered" }

    private suspend fun FakeEngine.servers() = effectiveConfig().getOrThrow().dnsUpstreams

    @Test
    fun `the page shows the core's servers, and a preset the core then asks`() = runTest {
        val engine = FakeEngine()
        val vm = DnsViewModel(engine)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), vm.state.servers)
        assertEquals(RvDnsUpstream.Cloudflare, rvDnsUpstream(vm.state.servers))

        vm.select(RvDnsUpstream.Quad9)
        assertEquals(listOf("9.9.9.9:53"), engine.servers())
        assertEquals(RvDnsUpstream.Quad9, rvDnsUpstream(vm.state.servers))
    }

    @Test
    fun `自訂 opens on the servers as typed and saves what the core accepts`() = runTest {
        val engine = FakeEngine()
        val vm = DnsViewModel(engine)
        vm.openCustom()
        assertTrue(vm.state.customOpen)
        assertEquals("1.1.1.1, 8.8.8.8", vm.state.customText)

        vm.editCustom("9.9.9.9,  223.5.5.5 ")
        vm.saveCustom()
        assertFalse(vm.state.customOpen)
        assertEquals(listOf("9.9.9.9:53", "223.5.5.5:53"), engine.servers())
        assertEquals(engine.servers(), vm.state.servers)
    }

    @Test
    fun `a server the core refuses keeps the sheet open with why, and typing clears it`() = runTest {
        val engine = FakeEngine()
        val vm = DnsViewModel(engine)
        vm.openCustom()
        vm.editCustom("dns.example")
        vm.saveCustom()
        assertTrue(vm.state.customOpen)
        assertEquals(ErrorCode.InvalidArgument, vm.state.customError)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), engine.servers())

        vm.editCustom("1.1.1.1 1.0.0.1 8.8.8.8 8.8.4.4 9.9.9.9")
        assertNull(vm.state.customError)
        vm.saveCustom()
        assertEquals("five servers are more than the core keeps", ErrorCode.InvalidArgument, vm.state.customError)

        vm.dismissCustom()
        assertFalse(vm.state.customOpen)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), vm.state.servers)
    }
}
