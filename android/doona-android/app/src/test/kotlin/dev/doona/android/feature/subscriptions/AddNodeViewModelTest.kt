package dev.doona.android.feature.subscriptions

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.StaticNodeReason
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.engine.fake.rvSampleEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class AddNodeViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(engine: StaticNodesEngine = engine(), clipboard: ClipboardLinks = HeldClipboard()) = AddNodeViewModel(engine, clipboard).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun engine() = StaticNodesEngine(rvSampleEngine(), regionOf = { name -> REGIONS.entries.firstOrNull { name.startsWith(it.key) }?.value })

    private val AddNodeViewModel.sheet get() = uiState.value.sheet!!

    @Test
    fun opensEmptyWithTheClipboardRowWhenItMayHoldText() = runTest {
        val vm = model(clipboard = HeldClipboard(TOKYO))
        vm.open()
        assertEquals(RvAddNodeSheet(clipboard = true), vm.sheet)
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
    fun previewsOneLinkByTheNameTheCoreGives() = runTest {
        val engine = engine()
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        assertEquals(RvAddNodeCheck.One("東京 03"), vm.sheet.check)
        assertEquals(listOf(listOf(TOKYO)), engine.previews)
        assertTrue(engine.fake.groups.value.none { group -> group.members.any { it.subscriptionId == null && !it.isGroup } })
    }

    @Test
    fun aLineThatIsNoLinkIsRefusedWithoutAskingTheCore() = runTest {
        val engine = engine()
        val vm = model(engine)
        vm.open()
        vm.edit("203.0.113.24:443")
        assertEquals(RvAddNodeCheck.Refused(RvSkipped(notLink = listOf(1))), vm.sheet.check)
        assertTrue(engine.previews.isEmpty())
        assertFalse(vm.sheet.check.addable)
    }

    @Test
    fun previewsSeveralLinesWithTheCountAndTheLinesSkipped() = runTest {
        val engine = engine()
        val vm = model(engine)
        vm.open()
        vm.edit("$TOKYO\n\n$SINGAPORE\nftp://x.example/a\n203.0.113.24:443")
        assertEquals(RvAddNodeCheck.Many(2, RvSkipped(notLink = listOf(4, 5))), vm.sheet.check)
        assertEquals(listOf(listOf(TOKYO, SINGAPORE, "ftp://x.example/a")), engine.previews)
    }

    @Test
    fun aFailedPreviewSaysWhy() = runTest {
        val engine = engine().apply { failing = ErrorCode.Busy }
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        assertEquals(RvAddNodeCheck.Failed(ErrorCode.Busy), vm.sheet.check)
        assertFalse(vm.sheet.check.addable)
    }

    @Test
    fun addsListsTheNodesAndNamesTheGroupsThatTookThem() = runTest {
        val engine = engine()
        val vm = model(engine)
        vm.open()
        vm.edit("$TOKYO\n$SINGAPORE\nnot a link")
        vm.add()
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(listOf("東京 03", "新加坡 05"), state.nodes.map { it.name })
        assertEquals(listOf(RvPlace.JP, RvPlace.SG), state.nodes.map { it.place })
        assertEquals(listOf("VLESS", "Trojan"), state.nodes.map { it.protocol })
        assertTrue(state.nodes.all { it.subscription.isEmpty() })
        // The sample's template: Proxy (the main group), Auto, Streaming, AI, then HK, TW, JP and US; no Singapore group.
        val groups = listOf(RvGroupName.Main, RvGroupName.Auto, RvGroupName.Streaming, RvGroupName.Ai, RvGroupName.Place(RvPlace.JP))
        assertEquals(RvAddedNodes(2, groups, RvSkipped()), state.added)
    }

    @Test
    fun aPartialAddListsWhatItAddedAndTheLinesItSkipped() = runTest {
        val engine = engine().apply { refused += SINGAPORE }
        val vm = model(engine)
        vm.open()
        vm.edit("$TOKYO\n$SINGAPORE\nnot a link")
        assertEquals(RvAddNodeCheck.Many(2, RvSkipped(notLink = listOf(3))), vm.sheet.check)
        vm.add()
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(listOf("東京 03"), state.nodes.map { it.name })
        // Line 3 was skipped as the sheet said before 新增; only line 2, refused by the add, is news.
        assertEquals(RvSkipped(duplicate = listOf(2)), state.added?.skipped)
        assertEquals(1, state.added?.count)
    }

    @Test
    fun anAddThatTakesNothingKeepsTheSheetAndSaysWhy() = runTest {
        val engine = engine().apply { refused += TOKYO }
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        assertEquals(RvAddNodeCheck.Refused(RvSkipped(duplicate = listOf(1))), vm.sheet.check)
        assertFalse(vm.sheet.adding)
        assertNull(vm.uiState.value.added)
    }

    @Test
    fun aFailedAddKeepsTheSheetWithTheEnginesReason() = runTest {
        val engine = engine()
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        engine.failing = ErrorCode.Internal
        vm.add()
        assertEquals(RvAddNodeCheck.Failed(ErrorCode.Internal), vm.sheet.check)
        assertTrue(vm.uiState.value.nodes.isEmpty())
    }

    @Test
    fun aLinkAddedBeforeIsADuplicate() = runTest {
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
        val engine = engine()
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        val id = vm.uiState.value.nodes.single().id
        engine.deleteFailing = ErrorCode.Internal
        vm.confirmDelete(id)
        assertEquals(RvNodeDeleteFailed(id, "東京 03", ErrorCode.Internal), vm.uiState.value.deleteFailed)
        assertEquals(1, vm.uiState.value.nodes.size)
        engine.deleteFailing = null
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
        val vm = model()
        vm.open()
        vm.edit((listOf(TOKYO) + List(ADD_NODE_LINES - 1) { "not a link" } + SINGAPORE).joinToString("\n"))
        vm.add()
        // The sheet closes, so the banner is what says the line past the cap was never read.
        val state = vm.uiState.value
        assertNull(state.sheet)
        assertEquals(1, state.added?.count)
        assertEquals(RvSkipped(unread = 1), state.added?.skipped)
    }

    @Test
    fun anAddsLateResultLeavesASheetOpenedSince() = runTest {
        val engine = engine().apply { addGate = CompletableDeferred() }
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        assertTrue(vm.sheet.adding)
        vm.edit("typed while adding")
        assertEquals(TOKYO, vm.sheet.text)
        vm.close()
        vm.open()
        vm.edit(SINGAPORE)
        engine.addGate!!.complete(Unit)
        // The first add's nodes are in and its banner shows, but the new sheet stays as the user left it.
        val state = vm.uiState.value
        assertEquals(SINGAPORE, state.sheet?.text)
        assertFalse(state.sheet!!.adding)
        assertEquals(RvAddNodeCheck.One("新加坡 05"), state.sheet.check)
        assertEquals(1, state.added?.count)
    }

    @Test
    fun anAddsLateFailureLeavesASheetOpenedSince() = runTest {
        val engine = engine().apply { addGate = CompletableDeferred() }
        val vm = model(engine)
        vm.open()
        vm.edit(TOKYO)
        vm.add()
        vm.close()
        vm.open()
        engine.failing = ErrorCode.Internal
        engine.addGate!!.complete(Unit)
        assertEquals(RvAddNodeSheet(), vm.sheet)
        assertNull(vm.uiState.value.added)
    }

    @Test
    fun theCoresReasonsSortTheSkippedLines() {
        val lines = addNodeLines("a://1\nb://2\nc://3\nd://4\ne://5\nplain")
        val results = listOf(
            StaticNodeResult.Invalid(StaticNodeReason.Link),
            StaticNodeResult.Invalid(StaticNodeReason.Protocol),
            StaticNodeResult.Invalid(StaticNodeReason.Credentials),
            StaticNodeResult.Unsupported(StaticNodeReason.Protocol),
            StaticNodeResult.Duplicate("x"),
        )
        val (taken, skipped) = sortRows(lines, results)
        assertTrue(taken.isEmpty())
        // A link or protocol the core cannot read is no share link; any other reason, and an unsupported one, is other.
        assertEquals(RvSkipped(notLink = listOf(1, 2, 6), duplicate = listOf(5), other = listOf(3, 4)), skipped)
    }

    @Test
    fun aGroupTheUserWroteKeepsItsName() {
        fun group(id: String, name: String) = Group(id, name, GroupKind.Selector, emptyList(), Group.Selection(null, pinned = false))
        assertEquals(RvGroupName.Main, rvGroupName(group("main", "Proxy"), "main"))
        assertEquals(RvGroupName.Named("Work"), rvGroupName(group("g9", "Work"), "main"))
        assertEquals(RvGroupName.Auto, rvGroupName(group("g1", "Auto"), "main"))
    }

    private companion object {
        const val TOKYO = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@203.0.113.24:443?security=reality&type=tcp#%E6%9D%B1%E4%BA%AC%2003"
        const val SINGAPORE = "trojan://sample-pass@203.0.113.40:443?sni=sg.example.net#%E6%96%B0%E5%8A%A0%E5%9D%A1%2005"
        val REGIONS = mapOf("東京" to "JP", "新加坡" to "SG")
    }
}
