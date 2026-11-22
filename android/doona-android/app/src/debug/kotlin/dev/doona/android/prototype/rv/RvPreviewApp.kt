package dev.doona.android.prototype.rv

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.fastest
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.RvNodesPageState
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvSettingsState
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.previewDark
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.scheme
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A launcher shortcut the sample was opened by ([action], as in xml/rv_shortcuts): 連線 and 中斷連線 run on screen as
 * soon as it shows (in the app, connecting goes through VpnService's consent first), 節點 starts with the picker open.
 */
internal enum class RvLaunch(val action: String?) {
    None(null),
    Connect("dev.doona.android.prototype.rv.CONNECT"),
    Disconnect("dev.doona.android.prototype.rv.DISCONNECT"),
    Nodes("dev.doona.android.prototype.rv.NODES"),
}

/**
 * How the sample starts: [connected] or not, whether connecting [fails], the [page] on top of its tab home (a route
 * such as `nodes` or `settings`), whether a latency [test] runs a second after launch (for recording it), and the
 * shortcut it was [launch]ed by.
 */
@Immutable
internal data class RvSampleStart(
    val connected: Boolean = true,
    val fails: Boolean = false,
    val page: String? = null,
    val test: Boolean = false,
    val launch: RvLaunch = RvLaunch.None,
) {
    val stack: List<String>
        get() = when (page) {
            null, ACTIVITY -> listOf(ACTIVITY)
            SETTINGS -> listOf(SETTINGS)
            else -> listOf(ACTIVITY, page)
        }
}

/**
 * The whole sample as [RvPreviewActivity] shows it, moving as [options] say: the pages on [RvStackHost], 活動 with its
 * pull-down to the node picker, and what the sample runs by itself ([RvSampleRun]). The theme follows 主題's choice and
 * cross-fades when it changes.
 */
@Composable
internal fun RvPreviewApp(options: RvMotionOptions = RvMotionOptions(), start: RvSampleStart = RvSampleStart()) {
    var settings by remember { mutableStateOf(rvSampleSettings()) }
    val systemDark = isSystemInDarkTheme()
    RvLookFade(settings.appearance.previewDark(systemDark) to settings.appearance) { (dark, appearance) ->
        RvTheme(appearance.scheme(rvSystemScheme(dark), dark), dark) {
            RvSample(options, start, settings) { settings = it }
        }
    }
}

@Composable
private fun RvSample(options: RvMotionOptions, start: RvSampleStart, settings: RvSettingsState, onSettings: (RvSettingsState) -> Unit) {
    val scope = rememberCoroutineScope()
    val run = remember { RvSampleRun(scope, start.connected, start.fails) }
    var stack by rememberSaveable { mutableStateOf(start.stack) }
    // While connected the minute moves on each second.
    val running = run.status is Status.Running
    LaunchedEffect(running) { if (running) rvEverySample { run.second++ } }
    LaunchedEffect(start.test) {
        if (!start.test) return@LaunchedEffect
        delay(TEST_DELAY_MS)
        run.test()
    }
    LaunchedEffect(start.launch) {
        if (start.launch == RvLaunch.Connect) run.connect()
        if (start.launch == RvLaunch.Disconnect) run.disconnect()
    }
    val pages = RvSamplePages(push = { stack = stack + it }, pop = { stack = stack.dropLast(1) }, tab = { i -> TABS[i]?.let { stack = listOf(it) } })
    val actions = remember(run) { RvRowActions(useNode = { run.currentId = it }, testNode = run::testNode, updateSubscription = run::update, deleteSubscription = run::delete) }
    CompositionLocalProvider(
        LocalRvTesting provides run.testing,
        LocalRvTestingNodes provides run.testingNodes,
        LocalRvUpdating provides run.updating,
        LocalRvRowActions provides actions,
    ) {
        RvStackHost(stack, onBack = pages.pop, options) { route -> RvSamplePage(route, run, options, settings, onSettings, pages, start.launch == RvLaunch.Nodes) }
    }
}

private class RvSamplePages(val push: (String) -> Unit, val pop: () -> Unit, val tab: (Int) -> Unit)

@Composable
private fun RvSamplePage(
    route: String,
    run: RvSampleRun,
    options: RvMotionOptions,
    settings: RvSettingsState,
    onSettings: (RvSettingsState) -> Unit,
    pages: RvSamplePages,
    picking: Boolean,
) {
    when (route.substringBefore(':')) {
        TRAFFIC -> RvTrafficScreen(run.activity(), RvData.session, onBack = pages.pop)

        STATS -> RvStatsScreen(rvStatsSample(), onBack = pages.pop)

        // The search page: the node picker, its field the search pill's; a picked node becomes the main one.
        SEARCH -> RvNodePicker(
            run.nodes,
            RvData.subscriptions,
            run.currentId,
            onPick = {
                run.currentId = it
                pages.pop()
            },
            onDetail = { pages.push("$NODE:$it") },
            onClose = pages.pop,
            searchPage = true,
        )

        NODES -> RvNodesPage(RvNodesPageState(run.nodes, run.currentId, RvData.testTimes), onBack = pages.pop, onFilter = {}, onOpen = { pages.push("$NODE:$it") }, onAllNodes = {})

        NODE -> {
            val node = run.nodes.first { it.id == route.substringAfter(':') }
            RvNodeDetail(node, current = node.id == run.currentId, RvData.testTimes, onBack = pages.pop, onUse = { run.currentId = node.id }, onTest = {})
        }

        SETTINGS -> RvSettingsScreen(settings, onChange = onSettings, onOpenTheme = { pages.push(THEME) }, onTab = pages.tab)

        THEME -> RvThemeScreen(settings.appearance, { onSettings(settings.copy(appearance = it)) }, rvSystemScheme(false), rvSystemScheme(true), onBack = pages.pop)

        else -> ActivityPage(run, options, pages, picking)
    }
}

/**
 * 活動 with its pull-down (open at first when [picking]): a picked node becomes the current one and the picker closes;
 * its page opens over 活動.
 */
@Composable
private fun ActivityPage(run: RvSampleRun, options: RvMotionOptions, pages: RvSamplePages, picking: Boolean) {
    val pull = rememberRvPullState(picking)
    val routes = RvActivityRoutes(
        traffic = { pages.push(TRAFFIC) },
        stats = { pages.push(STATS) },
        search = { pages.push(SEARCH) },
        nodes = { pages.push(NODES) },
        node = { pages.push("$NODE:${run.currentId}") },
        tab = pages.tab,
        test = run::test,
    )
    RvPullDown(
        options.pull,
        pull,
        activity = { look ->
            val onAction: (ActivityAction) -> Unit = { if (it == ActivityAction.Disconnect) run.disconnect() }
            RvActivityScreen(run.activity(), onAction, run::connect, routes, nodes = run.nodes, subscriptions = run.subscriptions, pull = look)
        },
        picker = { motion ->
            val pick: (String) -> Unit = {
                run.currentId = it
                motion.close()
            }
            RvNodePicker(run.nodes, RvData.subscriptions, run.currentId, pick, onDetail = { pages.push("$NODE:$it") }, onClose = motion.close, section = motion.section, stretch = motion.stretch)
        },
    )
}

/**
 * What the sample runs by itself, at the sample's own pace: connecting takes [RV_CONNECT_MS] and ends connected, or
 * failed when it [fails]; disconnecting takes [RV_STOP_MS]; while connected the live minute moves on a [second] at a
 * time; a latency test runs [RV_TEST_MS] and gives every node a new result ([testNode] one node); the current node is
 * the one picked last. A row menu [update]s a subscription (as long as a test) or [delete]s it with its nodes.
 */
@Stable
internal class RvSampleRun(private val scope: CoroutineScope, connected: Boolean, private val fails: Boolean) {
    var status by mutableStateOf(if (connected) Status.Running(RvData.session.startEpochMs) else Status.Stopped)
        private set
    var second by mutableIntStateOf(0)
    var nodes by mutableStateOf(RvData.nodes)
        private set
    var currentId by mutableStateOf(RvData.CURRENT_ID)
    var testing by mutableStateOf(false)
        private set
    var testingNodes by mutableStateOf(emptySet<String>())
        private set
    var subscriptions by mutableStateOf(RvData.subscriptions)
        private set
    var updating by mutableStateOf(emptySet<String>())
        private set
    private var rounds = 0
    private var link: Job? = null

    fun connect() {
        if (status is Status.Running || status == Status.Starting) return
        link?.cancel()
        link = scope.launch {
            status = Status.Starting
            delay(RV_CONNECT_MS)
            status = if (fails) Status.Failed(FailureReason.StartFailed) else Status.Running(RvData.nowEpochMs)
        }
    }

    fun disconnect() {
        link?.cancel()
        link = scope.launch {
            status = Status.Stopping
            delay(RV_STOP_MS)
            status = Status.Stopped
        }
    }

    fun test() {
        if (testing) return
        testing = true
        scope.launch {
            delay(RV_TEST_MS)
            rounds++
            nodes = nodes.map { it.retested(rounds) }
            testing = false
        }
    }

    fun testNode(id: String) {
        if (testing || id in testingNodes) return
        testingNodes += id
        scope.launch {
            delay(RV_TEST_MS)
            rounds++
            nodes = nodes.map { if (it.id == id) it.retested(rounds) else it }
            testingNodes -= id
        }
    }

    fun update(id: String) {
        if (id in updating) return
        updating += id
        scope.launch {
            delay(RV_TEST_MS)
            updating -= id
        }
    }

    /** Removes the subscription and its nodes; when the main node goes with them, the fastest left takes its place. */
    fun delete(id: String) {
        val gone = subscriptions.firstOrNull { it.id == id } ?: return
        subscriptions = subscriptions - gone
        nodes = nodes.filterNot { it.subscription == gone.name }
        if (nodes.none { it.id == currentId }) nodes.fastest(1).firstOrNull()?.let { currentId = it.id }
    }

    /** 活動 as the run has it: live while connected; otherwise its state with no rates and no history. */
    fun activity(): ActivityUiState {
        val live = rvSampleActivity(currentId, nodes, second)
        return if (status is Status.Running) live else live.copy(status = status, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())
    }
}

/** The node after one more test: the oldest result drops off and a new one, within [SWING] ms of the last, comes in. */
private fun RvNode.retested(round: Int): RvNode {
    val last = latencyMs ?: medianMs
    val next = last?.let { (it + (id.hashCode() + round * SWING_STEP).mod(SWING * 2 + 1) - SWING).coerceAtLeast(1) }
    return copy(history = history.drop(1) + next)
}

/** The sample's own pace (not motion): a connect, a disconnect, a latency test, and the wait before a launched test. */
internal const val RV_CONNECT_MS = 1_200L
internal const val RV_STOP_MS = 400L
internal const val RV_TEST_MS = 1_200L
private const val TEST_DELAY_MS = 1_000L
private const val SWING = 12
private const val SWING_STEP = 7

internal const val ACTIVITY = "activity"
internal const val TRAFFIC = "traffic"
internal const val STATS = "stats"
internal const val SEARCH = "search"
internal const val NODES = "nodes"
internal const val NODE = "node"
internal const val SETTINGS = "settings"
internal const val THEME = "theme"
private val TABS = mapOf(RV_NAV_ACTIVITY to ACTIVITY, RV_NAV_SETTINGS to SETTINGS)
