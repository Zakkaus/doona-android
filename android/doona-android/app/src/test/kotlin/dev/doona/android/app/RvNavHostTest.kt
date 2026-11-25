package dev.doona.android.app

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.LocalRvTabEntry
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvPullDown
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvPullState
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rememberRvPullState
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvSystemScheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The app's page host (plan §2.3, the P1a acceptance): tabs and pops, the stack and each page's state across process
 * death, state and ViewModels cleared only after a page's exit, predictive back cancelled and committed, the pull-down's
 * picker closing before any pop, tab switches not played as pushes, and back on a tab root going to 活動.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RvNavHostTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var stack: MutableList<NavKey>
    private lateinit var back: OnBackPressedDispatcher
    private var pull: RvPullState? = null
    private val cleared = mutableListOf<String>()
    private val tabEntries = mutableListOf<Pair<String, Boolean>>()
    private var serial = 0
    private var routesRules = true

    @Composable
    private fun Host(start: List<NavKey>) {
        RvTheme(rvSystemScheme(false), false) {
            val backStack = rememberNavBackStack(*start.toTypedArray())
            stack = backStack
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RvNavHost(backStack, Modifier.fillMaxSize()) {
                entry<ActivityKey> { Page("activity") }
                entry<PoliciesKey> { Page("policies") }
                entry<SettingsKey> { Page("settings") }
                entry<GroupKey> { key -> Page("group ${key.groupId}") }
                entry<NodeSearchKey> { Page("search") }
                entry<NodesKey> { Picker("nodes") }
                if (routesRules) entry<RulesKey> { Page("rules") }
            }
        }
    }

    /**
     * A page showing [name] and the serial number of its saved state (a fresh state takes the next number, a restored
     * one keeps its own), with a ViewModel that records when it is cleared. Each time it comes into composition it
     * records whether it came by a tab switch.
     */
    @Composable
    private fun Page(name: String) {
        val id = rememberSaveable { ++serial }
        rememberSaveable { "$SAVED$name;" }
        viewModel { Probe(name, cleared) }
        val entering = LocalRvTabEntry.current
        LaunchedEffect(Unit) { tabEntries += name to entering }
        BasicText("$name #$id")
    }

    /** A page with RV's pull-down open over it, as 活動 has it (D30). */
    @Composable
    private fun Picker(name: String) {
        val state = rememberRvPullState(open = true)
        pull = state
        RvPullDown(RvPullMotion.Sheet, state, activity = { BasicText(name) }, picker = { BasicText("picker") })
    }

    private var scroll: ScrollState? = null

    /** The tab roots and a group page, each drawing the floating nav for the tab the stack is on; only 策略 scrolls. */
    @Composable
    private fun BarHost(start: List<NavKey>) {
        RvTheme(rvSystemScheme(false), false) {
            val backStack = rememberNavBackStack(*start.toTypedArray())
            stack = backStack
            RvNavHost(backStack, Modifier.fillMaxSize()) {
                entry<ActivityKey> { BarPage("activity", null) }
                entry<PoliciesKey> { BarPage("policies", rememberScrollState().also { scroll = it }) }
                entry<SettingsKey> { BarPage("settings", null) }
                entry<GroupKey> { key -> BarPage("group ${key.groupId}", null) }
            }
        }
    }

    @Composable
    private fun BarPage(name: String, scroll: ScrollState?) {
        val glass = rememberRvGlass()
        Box(Modifier.fillMaxSize()) {
            val scrolls = if (scroll != null) Modifier.verticalScroll(scroll) else Modifier
            Column(Modifier.fillMaxSize().rvGlassSource(glass).then(scrolls)) {
                BasicText(name)
                Spacer(Modifier.height(TALL))
            }
            val nav = currentTab(stack).navId
            RvFloatingNav(nav, { stack.selectTab(it) }, glass, scroll, WindowInsets(0), Modifier.align(Alignment.BottomCenter), onReselect = { stack.reselectTab() })
        }
    }

    private fun tapSelectedTab() {
        compose.onNode(isSelected()).performClick()
        compose.waitForIdle()
    }

    private fun start(vararg keys: NavKey) {
        compose.setContent { Host(keys.toList()) }
        compose.waitForIdle()
    }

    private fun onUi(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    /**
     * The app's saved state as the activity would save it now, through a registry this test owns: a page's saved state
     * is in it while Navigation 3 keeps that state, and leaves it once the state is cleared.
     */
    private val registry = SaveableStateRegistry(null) { true }

    private fun savesStateOf(name: String): Boolean {
        var saved: Map<String, List<Any?>> = emptyMap()
        compose.runOnIdle { saved = registry.performSave() }
        return saved.holds("$SAVED$name;")
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    /** The serial number of the saved state [name]'s page shows. */
    private fun stateOf(name: String): String =
        compose.onNodeWithText("$name #", substring = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString().substringAfter('#')

    @Test
    fun tabsKeepActivityAtTheBottomAndBackOnATabRootGoesToActivity() {
        start(ActivityKey)
        onUi { stack.selectTab(Tab.Policies) }
        assertEquals(listOf(ActivityKey, PoliciesKey), stack.toList())
        assertTrue("策略 handles back", back.hasEnabledCallbacks())
        onUi { back.onBackPressed() }
        assertEquals(listOf<NavKey>(ActivityKey), stack.toList())
        assertFalse("活動 leaves back to the system", back.hasEnabledCallbacks())

        onUi { stack.selectTab(Tab.Settings) }
        onUi { stack.push(GroupKey("g")) }
        onUi { back.onBackPressed() }
        assertEquals("a page stacked on a tab pops first", listOf(ActivityKey, SettingsKey), stack.toList())
        onUi { back.onBackPressed() }
        assertEquals(listOf<NavKey>(ActivityKey), stack.toList())
    }

    @Test
    fun theBarsIdsSelectTabsAndConnectionsIsNotShipped() {
        val keys = mutableListOf<NavKey>(ActivityKey, PoliciesKey, GroupKey("g"))
        keys.selectTab(Tab.Settings.navId)
        assertEquals(listOf(ActivityKey, SettingsKey), keys)
        keys.selectTab(dev.doona.android.designsystem.rv.RV_NAV_CONNECTIONS)
        assertEquals("連線 waits for c-list (D31)", listOf(ActivityKey, SettingsKey), keys)
        assertEquals(Tab.entries.map { it.navId }, APP_NAV.map { it.id })
    }

    @Test
    fun theStackAndEachPagesStateSurviveProcessDeath() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host(listOf(ActivityKey)) }
        onUi { stack.selectTab(Tab.Policies) }
        val policies = stateOf("policies")
        onUi { stack.push(GroupKey("g")) }
        val group = stateOf("group g")

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(listOf(ActivityKey, PoliciesKey, GroupKey("g")), stack.toList())
        assertEquals("the page on top keeps its state", group, stateOf("group g"))
        onUi { back.onBackPressed() }
        assertEquals("so does the page under it", policies, stateOf("policies"))
    }

    @Test
    fun aRestoredPageTheAppHasNoEntryForFallsBackToItsTabsRoot() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Host(listOf(ActivityKey)) }
        onUi { stack.selectTab(Tab.Policies) }
        onUi { stack.push(GroupKey("g")) }
        onUi { stack.push(RulesKey) }
        assertEquals(1, shown("rules"))

        routesRules = false
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(listOf(ActivityKey, PoliciesKey), stack.toList())
        assertEquals(1, shown("policies"))
    }

    @Test
    fun aStackWithAKeyWithoutAPageFallsBackToItsTabsRoot() {
        val routed = setOf<NavKey>(ActivityKey, PoliciesKey, GroupKey("g"))
        val onActivity = mutableListOf<NavKey>(ActivityKey, GroupKey("g"), RulesKey)
        onActivity.keepRouted { it in routed }
        assertEquals(listOf<NavKey>(ActivityKey), onActivity)
        val onSettings = mutableListOf<NavKey>(ActivityKey, SettingsKey, GroupKey("g"))
        onSettings.keepRouted { it in routed }
        assertEquals("a tab root without a page falls back to 活動", listOf<NavKey>(ActivityKey), onSettings)
        val whole = mutableListOf<NavKey>(ActivityKey, PoliciesKey, GroupKey("g"))
        whole.keepRouted { it in routed }
        assertEquals("a stack of pages the app has is kept", listOf(ActivityKey, PoliciesKey, GroupKey("g")), whole)
    }

    @Test
    fun aPagesStateAndViewModelsClearOnlyAfterItsExit() {
        compose.setContent { CompositionLocalProvider(LocalSaveableStateRegistry provides registry) { Host(listOf(ActivityKey, PoliciesKey, GroupKey("g"))) } }
        compose.waitForIdle()
        val before = stateOf("group g")
        assertTrue(savesStateOf("group g"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stack.pop(GroupKey("g")) }
        // Well into the exit: past the frames before it starts, short of its end (PIPELINE_FRAMES + PUSH_FRAMES + 1).
        frames(PUSH_FRAMES)
        assertEquals("the leaving page still shows", 1, shown("group g"))
        assertTrue("its ViewModel lives while it leaves", cleared.isEmpty())
        assertTrue("and so does its saved state", savesStateOf("group g"))

        frames(SETTLE_FRAMES)
        assertEquals(0, shown("group g"))
        assertEquals("cleared once, after the exit", listOf("group g"), cleared)
        assertFalse("its saved state went with it", savesStateOf("group g"))

        compose.mainClock.autoAdvance = true
        onUi { stack.push(GroupKey("g")) }
        assertTrue("pushed again, the page starts afresh", stateOf("group g") != before)
        assertEquals("the policies page under it kept its ViewModel", listOf("group g"), cleared)
    }

    @Test
    fun aCancelledPredictiveBackPopsNothing() {
        start(ActivityKey, PoliciesKey, GroupKey("g"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            assertTrue("the host takes back", back.hasEnabledCallbacks())
            back.dispatchOnBackStarted(BackEventCompat(EDGE_X, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(EDGE_X * 2, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        frames(PIPELINE_FRAMES + 2)
        assertEquals("the page under shows while the finger is down", 1, shown("policies"))
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        frames(SETTLE_FRAMES)

        assertEquals(listOf(ActivityKey, PoliciesKey, GroupKey("g")), stack.toList())
        assertEquals("the page is back", 1, shown("group g"))
        assertEquals(0, shown("policies"))
        assertTrue(cleared.isEmpty())
    }

    @Test
    fun aCommittedPredictiveBackPopsOnce() {
        start(ActivityKey, PoliciesKey, GroupKey("a"), GroupKey("b"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(EDGE_X, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(EDGE_X * 2, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        frames(2)
        compose.runOnIdle { back.onBackPressed() }
        frames(SETTLE_FRAMES)

        assertEquals(listOf(ActivityKey, PoliciesKey, GroupKey("a")), stack.toList())
        assertEquals(1, shown("group a"))
        assertEquals(listOf("group b"), cleared)
    }

    @Test
    fun backPressedAgainWhileAPageLeavesPopsNothing() {
        start(ActivityKey, PoliciesKey, GroupKey("a"), GroupKey("b"), GroupKey("c"))
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            back.onBackPressed()
            back.onBackPressed()
        }
        assertEquals("two backs in one frame pop once", listOf(ActivityKey, PoliciesKey, GroupKey("a"), GroupKey("b")), stack.toList())

        frames(PIPELINE_FRAMES + 1)
        assertEquals("c still leaves", 1, shown("group c"))
        compose.runOnIdle { back.onBackPressed() }
        frames(SETTLE_FRAMES)
        assertEquals("a back during the exit pops nothing", listOf(ActivityKey, PoliciesKey, GroupKey("a"), GroupKey("b")), stack.toList())
        assertEquals(1, shown("group b"))

        compose.runOnIdle { back.onBackPressed() }
        frames(SETTLE_FRAMES)
        assertEquals("once the exit is over, back pops again", listOf(ActivityKey, PoliciesKey, GroupKey("a")), stack.toList())
        assertEquals(listOf("group c", "group b"), cleared)
    }

    @Test
    fun thePickerClosesBeforeAnyPop() {
        start(ActivityKey, NodesKey)
        assertTrue(pull!!.open)
        onUi { back.onBackPressed() }
        assertFalse("back closed the picker", pull!!.open)
        assertEquals("and popped nothing", listOf(ActivityKey, NodesKey), stack.toList())
        onUi { back.onBackPressed() }
        assertEquals(listOf<NavKey>(ActivityKey), stack.toList())
    }

    @Test
    fun thePickerOnActivityClosesBeforeTheAppLeaves() {
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                val backStack = rememberNavBackStack(ActivityKey)
                stack = backStack
                back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
                RvNavHost(backStack) { entry<ActivityKey> { Picker("activity") } }
            }
        }
        compose.waitForIdle()
        assertTrue(back.hasEnabledCallbacks())
        onUi { back.onBackPressed() }
        assertFalse(pull!!.open)
        assertFalse("then back leaves the app", back.hasEnabledCallbacks())
    }

    @Test
    fun aTabSwitchIsACutNotAPush() {
        start(ActivityKey)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stack.selectTab(Tab.Policies) }
        val cut = framesTogether("activity", "policies")
        assertTrue("a tab switch shows both pages only while the frames catch up: $cut", cut <= CUT_FRAMES)

        compose.runOnIdle { stack.push(GroupKey("g")) }
        val push = framesTogether("policies", "group g")
        assertTrue("a push cross-fades for its whole $PUSH_FRAMES frames: $push", push >= PUSH_FRAMES)
    }

    /** Advances the clock [n] frames, letting the main looper's work between them run, as it does on a phone. */
    private fun frames(n: Int) = repeat(n) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    /**
     * Advances the clock a frame at a time until [old] has gone, and counts the frames in which [old] and [new] both
     * show; fails if [old] has not gone within a second.
     */
    private fun framesTogether(old: String, new: String): Int {
        var together = 0
        repeat(SECOND_FRAMES) {
            frames(1)
            if (shown(new) == 1 && shown(old) == 1) together++
            if (shown(new) == 1 && shown(old) == 0) return together
        }
        throw AssertionError("$old did not leave")
    }

    @Test
    fun aPageOpeningAnotherTabsRootKeyStaysOnItsTab() {
        start(ActivityKey)
        onUi { stack.selectTab(Tab.Policies) }
        onUi { stack.push(GroupKey("g")) }
        tabEntries.clear()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { stack.push(SettingsKey) }
        assertEquals("the tab is the root above 活動, not the last root key", Tab.Policies, currentTab(stack))
        val push = framesTogether("group g", "settings")
        assertTrue("played as a push, not a tab switch: $push", push >= PUSH_FRAMES)
        assertEquals(listOf("settings" to false), tabEntries)
    }

    @Test
    fun tappingTheSelectedTabAbovePagesPopsBackToItsRoot() {
        compose.setContent { BarHost(listOf(ActivityKey, PoliciesKey, GroupKey("a"), GroupKey("b"))) }
        compose.waitForIdle()
        tapSelectedTab()
        assertEquals(listOf(ActivityKey, PoliciesKey), stack.toList())
        assertEquals(1, shown("policies"))
    }

    @Test
    fun tappingTheSelectedTabOnItsRootScrollsThePageToItsTop() {
        compose.setContent { BarHost(listOf(ActivityKey, PoliciesKey)) }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { scroll!!.scrollTo(SCROLLED) } }
        assertEquals(SCROLLED, scroll!!.value)
        tapSelectedTab()
        assertEquals("the page is back at its top", 0, scroll!!.value)
        assertEquals("and the stack is as it was", listOf(ActivityKey, PoliciesKey), stack.toList())
    }

    @Test
    fun tappingTheSelectedTabOnARootWithoutAScrollDoesNothing() {
        compose.setContent { BarHost(listOf(ActivityKey, SettingsKey)) }
        compose.waitForIdle()
        tapSelectedTab()
        assertEquals(listOf(ActivityKey, SettingsKey), stack.toList())
        assertEquals(1, shown("settings"))
    }

    @Test
    fun onlyATabSwitchFadesTheNewTabsHeroIn() {
        start(ActivityKey)
        onUi { stack.selectTab(Tab.Policies) }
        onUi { stack.push(GroupKey("g")) }
        onUi { back.onBackPressed() }
        onUi { back.onBackPressed() }
        // 活動 at launch; 策略 by a tab switch; a pushed page; 策略 again by a pop; 活動 by back from a tab root.
        val expected = listOf("activity" to false, "policies" to true, "group g" to false, "policies" to false, "activity" to true)
        assertEquals(expected, tabEntries)
    }

    @Test
    fun aDoubleTapOpensOnePage() {
        val keys = mutableListOf<NavKey>(ActivityKey, PoliciesKey)
        keys.push(GroupKey("g"))
        keys.push(GroupKey("g"))
        assertEquals(listOf(ActivityKey, PoliciesKey, GroupKey("g")), keys)
        keys.push(GroupKey("h"))
        keys.push(GroupKey("g"))
        assertEquals("a key lower in the stack is popped back to", listOf(ActivityKey, PoliciesKey, GroupKey("g")), keys)
    }
}

/** Whether [text] is anywhere in this saved state, which nests maps and lists. */
private fun Any?.holds(text: String): Boolean = when (this) {
    is String -> this == text
    is Map<*, *> -> entries.any { (k, v) -> k.holds(text) || v.holds(text) }
    is Iterable<*> -> any { it.holds(text) }
    is Array<*> -> any { it.holds(text) }
    else -> false
}

/** A ViewModel that records its page's [name] in [cleared] when Navigation 3 clears it. */
private class Probe(val name: String, private val cleared: MutableList<String>) : ViewModel() {
    override fun onCleared() {
        cleared += name
    }
}

/**
 * Frames between a change of the stack and the new page's first frame, and as many again before a cut's old page has
 * left: NavDisplay's scene state, its transition and AnimatedContent each take a frame (measured: the new page shows on
 * the 3rd frame, the old one leaves 2 frames later).
 */
private const val PIPELINE_FRAMES = 3

/** The most frames a cut may show both pages: [PIPELINE_FRAMES] and one more for rounding, as [PUSH_FRAMES] allows one less. */
private const val CUT_FRAMES = PIPELINE_FRAMES + 1

/** The frames of a push ([RvMotion.PUSH_MS], 130 ms) at 16 ms a frame, less one for rounding. */
private const val PUSH_FRAMES = (RvMotion.PUSH_MS / 16) - 1
private const val SECOND_FRAMES = 60

/** Long enough for any pop, or a cancelled one running back, to finish. */
private const val SETTLE_FRAMES = 30
private const val SAVED = "saved state of "
private val TALL = 3000.dp
private const val SCROLLED = 600
private const val EDGE_X = 4f
private const val EDGE_Y = 800f
private const val HALF = 0.5f
