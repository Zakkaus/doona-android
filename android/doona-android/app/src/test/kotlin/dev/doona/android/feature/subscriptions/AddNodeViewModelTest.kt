package dev.doona.android.feature.subscriptions

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.honk.Node
import dev.doona.android.vpn.honk.Provider
import dev.doona.android.vpn.honk.ProviderKind
import dev.doona.android.vpn.honk.ProviderStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddNodeViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(honk: InlineHonk = honk(), clipboard: ClipboardLinks = HeldClipboard(), io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)) =
        AddNodeViewModel(honk, clipboard, io).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    private fun honk() = InlineHonk { name -> REGIONS.entries.firstOrNull { name.startsWith(it.key) }?.value }

    private val AddNodeViewModel.sheet get() = uiState.value.sheet!!

    @Test
    fun opensEmptyWithTheClipboardRowWhenItMayHoldText() = runTest {
        val vm = model(clipboard = HeldClipboard(TOKYO))
        vm.open()
        assertEquals(RvAddNodeSheet(clipboard = true), vm.sheet.copy(groups = emptyList()))
        vm.paste()
        assertEquals(TOKYO, vm.sheet.text)
        assertFalse(vm.sheet.clipboard)
        assertEquals(RvAddNodeCheck.One("東京 03"), vm.sheet.check)
    }

    @Test
    fun aClipWithNoLinkHidesTheRowAndLeavesTheField() = runTest {
        val vm = model(clipboard = HeldClipboard("hello"))
        vm.open()
        vm.paste()
        assertEquals("", vm.sheet.text)
        assertFalse(vm.sheet.clipboard)
    }

    @Test
    fun oneLinkIsNamedByWhatItCarriesWithoutAskingHonk() = runTest {
        val honk = honk()
        val vm = model(honk)
        vm.open()
        vm.edit(TOKYO)
        assertEquals(RvAddNodeCheck.One("東京 03"), vm.sheet.check)
        assertTrue(honk.links.isEmpty())
    }

    @Test
    fun aLineThatIsNoLinkIsRefused() = runTest {
        val vm = model()
        vm.open()
        vm.edit("203.0.113.24:443")
        assertEquals(RvAddNodeCheck.Refused(RvSkipped(notLink = listOf(1))), vm.sheet.check)
        assertFalse(vm.sheet.check.addable)
    }

    @Test
    fun severalLinesGiveTheCountAndTheLinesSkipped() = runTest {
        val vm = model()
        vm.open()
        vm.edit("$TOKYO\n\n$SINGAPORE\nftp://x.example/a\n203.0.113.24:443\n$TOKYO")
        // ftp:// may be a link honk reads; only honk can say. The second 東京 03 would take a name in use.
        assertEquals(RvAddNodeCheck.Many(3, RvSkipped(notLink = listOf(5), duplicate = listOf(6))), vm.sheet.check)
    }

    @Test
    fun addsListsTheNodesAndNamesTheGroupsThatTookThem() = runTest {
        val honk = honk()
        val vm = model(honk)
        vm.open()
        vm.edit("$TOKYO\n$SINGAPORE\nnot a link")
        vm.add()
        assertEquals(listOf(TOKYO, SINGAPORE), honk.links)
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(listOf("東京 03", "新加坡 05"), state.nodes.map { it.name })
        assertEquals(listOf(RvPlace.JP, RvPlace.SG), state.nodes.map { it.place })
        assertEquals(listOf("VLESS", "Trojan"), state.nodes.map { it.protocol })
        assertTrue(state.nodes.all { it.subscription.isEmpty() })
        // honk's groups by their names, in honk's order; the sample has no Singapore group.
        assertEquals(RvAddedNodes(2, listOf("Proxy", "Auto", "Streaming", "AI", "JP"), RvSkipped()), state.added)
        assertTrue(honk.writes.isEmpty())
    }

    @Test
    fun theSheetListsHonksGroupsAsTheFilesHoldThem() = runTest {
        val vm = model()
        vm.open()
        val picks = vm.sheet.groups
        assertEquals(listOf("Proxy", "Auto", "Streaming", "AI", "HK", "TW", "JP", "US"), picks.map { it.name })
        // Proxy and Auto have no filter, so they take every node; US is in a file honk cannot write.
        assertEquals(listOf("Proxy", "Auto"), picks.filter { it.every }.map { it.name })
        assertEquals(listOf("US"), picks.filterNot { it.writable }.map { it.name })
        vm.pick("Auto")
        vm.pick("US")
        assertTrue(vm.sheet.groups.none { it.chosen })
        vm.pick("HK")
        vm.pick("TW")
        vm.pick("TW")
        assertEquals(listOf("HK"), vm.sheet.groups.filter { it.chosen }.map { it.name })
    }

    @Test
    fun chosenGroupsTakeTheNodesTheyLackInOneWriteAFile() = runTest {
        val honk = honk()
        val vm = model(honk)
        vm.open()
        vm.edit("$TOKYO\n$SINGAPORE")
        listOf("HK", "TW", "AI", "JP").forEach(vm::pick)
        vm.add()
        advanceUntilIdle()
        // AI's filter took both nodes and JP's took 東京 03 already: only what each group lacks is written, and
        // source-main, which holds AI, not at all.
        assertEquals(listOf("source-regions"), honk.writes.map { it.first })
        val written = honk.writes.single().second
        assertEquals(2, written.lines().count { "filter: name('東京 03')" in it })
        assertEquals(3, written.lines().count { "filter: name('新加坡 05')" in it })
        assertEquals("        filter: name('新加坡 05')", written.lines()[written.lines().indexOf("    JP {") + 2])
        assertNull(vm.uiState.value.added?.failed)
        assertEquals(2, vm.uiState.value.added?.count)
    }

    @Test
    fun aRefusedGroupWriteSaysWhyOnTheBannerWithTheNodesIn() = runTest {
        val honk = honk().apply { writeStatus = 503 }
        val vm = model(honk)
        vm.open()
        vm.edit(TOKYO)
        vm.pick("HK")
        vm.add()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(ErrorCode.Busy, state.added?.failed)
        assertEquals(listOf("東京 03"), state.nodes.map { it.name })
    }

    @Test
    fun aPartialAddListsWhatItAddedAndTheLinesHonkRefused() = runTest {
        val honk = honk().apply { refused += SINGAPORE }
        val vm = model(honk)
        vm.open()
        vm.edit("$TOKYO\n$SINGAPORE\nnot a link")
        assertEquals(RvAddNodeCheck.Many(2, RvSkipped(notLink = listOf(3))), vm.sheet.check)
        vm.add()
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(listOf("東京 03"), state.nodes.map { it.name })
        // Line 3 was skipped as the sheet said before 新增; only line 2, refused by honk, is news.
        assertEquals(RvSkipped(other = listOf(2)), state.added?.skipped)
        assertEquals(1, state.added?.count)
    }

    @Test
    fun anAddThatTakesNothingKeepsTheSheetAndSaysWhy() = runTest {
        val honk = honk().apply { refused += TOKYO }
        val vm = model(honk)
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        assertEquals(RvAddNodeCheck.Refused(RvSkipped(other = listOf(1))), vm.sheet.check)
        assertFalse(vm.sheet.adding)
        assertNull(vm.uiState.value.added)
    }

    @Test
    fun aFailedAddKeepsTheSheetWithHonksReason() = runTest {
        val honk = honk()
        val vm = model(honk)
        vm.open()
        vm.edit(TOKYO)
        honk.addStatus = 500
        vm.add()
        assertEquals(RvAddNodeCheck.Failed(ErrorCode.Internal), vm.sheet.check)
        assertTrue(vm.uiState.value.nodes.isEmpty())
    }

    @Test
    fun aNameInUseIsADuplicate() = runTest {
        val vm = model()
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        vm.open()
        assertNull(vm.uiState.value.added)
        vm.edit(TOKYO)
        assertEquals(RvAddNodeCheck.Refused(RvSkipped(duplicate = listOf(1))), vm.sheet.check)
    }

    @Test
    fun deletesAfterTheQuestion() = runTest {
        val vm = model()
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        val id = vm.uiState.value.nodes.single().id
        vm.delete(id)
        assertEquals(id, vm.uiState.value.deletingId)
        vm.confirmDelete(id)
        assertNull(vm.uiState.value.deletingId)
        assertTrue(vm.uiState.value.nodes.isEmpty())
        assertNull(vm.uiState.value.deleteFailed)
    }

    @Test
    fun aRefusedDeleteKeepsTheNodeWithTheBannerAndRetries() = runTest {
        val honk = honk()
        val vm = model(honk)
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        val id = vm.uiState.value.nodes.single().id
        honk.deleteStatus = 500
        vm.confirmDelete(id)
        assertEquals(RvNodeDeleteFailed(id, "東京 03", ErrorCode.Internal), vm.uiState.value.deleteFailed)
        assertEquals(1, vm.uiState.value.nodes.size)
        honk.deleteStatus = null
        vm.confirmDelete(id)
        assertNull(vm.uiState.value.deleteFailed)
        assertTrue(vm.uiState.value.nodes.isEmpty())
    }

    @Test
    fun linesKeepTheirNumberAndAreTrimmed() {
        assertEquals(listOf(AddNodeLine(1, "a"), AddNodeLine(3, "ss://b")), addNodeLines(" a \n\n ss://b \n "))
        val text = (1..250).joinToString("\n\n") { "ss://h$it" }
        assertEquals(ADD_NODE_LINES, addNodeLines(text).size)
        assertEquals(50, addNodeUnread(text))
        assertEquals(0, addNodeUnread("ss://a\n\n\n"))
    }

    @Test
    fun aLineIsNamedByItsFragmentElseItsHostElseItself() {
        assertEquals("東京 03", AddNodeLine(1, TOKYO).name)
        assertEquals("203.0.113.40", AddNodeLine(1, "trojan://pass@203.0.113.40:443?sni=a.example").name)
        assertEquals("203.0.113.40", AddNodeLine(1, "trojan://pass@203.0.113.40:443#%20").name)
        assertEquals("eyJ2IjoiMiJ9", AddNodeLine(1, "vmess://eyJ2IjoiMiJ9").name)
        assertEquals("ss:/", AddNodeLine(1, "ss:/").name)
        assertEquals(64, AddNodeLine(1, "ss://a@h:1#" + "x".repeat(80)).name.length)
    }

    @Test
    fun linesPastTheCapAreCountedAsNotRead() = runTest {
        val vm = model()
        vm.open()
        vm.edit((List(ADD_NODE_LINES) { "not a link" } + TOKYO + SINGAPORE).joinToString("\n"))
        // Every line read is no link, and the two links past the cap are never sent.
        assertEquals(RvAddNodeCheck.Refused(RvSkipped(notLink = (1..ADD_NODE_LINES).toList(), unread = 2)), vm.sheet.check)
        vm.edit((listOf(TOKYO, SINGAPORE) + List(ADD_NODE_LINES) { "not a link" }).joinToString("\n"))
        assertEquals(RvAddNodeCheck.Many(2, RvSkipped(notLink = (3..ADD_NODE_LINES).toList(), unread = 2)), vm.sheet.check)
    }

    @Test
    fun anAddThatSucceedsStillCountsTheLinesPastTheCap() = runTest {
        val honk = honk()
        val vm = model(honk)
        vm.open()
        vm.edit((listOf(TOKYO) + List(ADD_NODE_LINES - 1) { "not a link" } + SINGAPORE).joinToString("\n"))
        vm.add()
        // The sheet closes, so the banner is what says the line past the cap was never read.
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(listOf(TOKYO), honk.links)
        assertEquals(1, state.added?.count)
        assertEquals(RvSkipped(unread = 1), state.added?.skipped)
    }

    @Test
    fun anAddsLateResultLeavesASheetOpenedSince() = runTest {
        val vm = model(io = StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        assertTrue(vm.sheet.adding)
        vm.edit("typed while adding")
        assertEquals(TOKYO, vm.sheet.text)
        vm.close()
        vm.open()
        vm.edit(SINGAPORE)
        runCurrent()
        // The first add's nodes are in and its banner shows, but the new sheet stays as the user left it.
        val state = vm.uiState.value
        assertEquals(SINGAPORE, state.sheet?.text)
        assertFalse(state.sheet!!.adding)
        assertEquals(RvAddNodeCheck.One("新加坡 05"), state.sheet.check)
        assertEquals(1, state.added?.count)
        assertEquals(listOf("東京 03"), state.nodes.map { it.name })
    }

    @Test
    fun anAddsLateFailureLeavesASheetOpenedSince() = runTest {
        val honk = honk()
        val vm = model(honk, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        vm.close()
        vm.open()
        honk.addStatus = 500
        runCurrent()
        assertEquals(RvAddNodeSheet(), vm.sheet.copy(groups = emptyList()))
        assertNull(vm.uiState.value.added)
    }

    @Test
    fun inlineNodesAreTheOnesOfNoProviderOrTheInlineOne() {
        fun node(id: String, provider: String?) = Node(id, id, "vless", subscriptionTag = null, providerId = provider, groupIds = emptyList(), health = emptyList())
        fun provider(id: String, kind: ProviderKind) = Provider(id, id, kind, null, 0, null, null, null, ProviderStatus.Ok, null)
        val nodes = listOf(node("a", null), node("b", "inline"), node("c", "s1"), node("d", "f1"))
        val providers = listOf(provider("inline", ProviderKind.Inline), provider("s1", ProviderKind.Subscription), provider("f1", ProviderKind.File))
        assertEquals(listOf("a", "b"), inlineNodes(nodes, providers).map { it.id })
    }

    private companion object {
        const val TOKYO = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.24:443?security=reality&type=tcp#%E6%9D%B1%E4%BA%AC%2003"
        const val SINGAPORE = "trojan://sample-pass@203.0.113.40:443?sni=sg.example.net#%E6%96%B0%E5%8A%A0%E5%9D%A1%2005"
        val REGIONS = mapOf("東京" to "JP", "新加坡" to "SG")
    }
}
