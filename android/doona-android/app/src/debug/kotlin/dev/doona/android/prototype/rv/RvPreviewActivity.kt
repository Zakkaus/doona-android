package dev.doona.android.prototype.rv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample

/**
 * Debug builds only: the Revolut-style sample with its invented state, full screen on a phone, so the glass blur can be
 * seen where the platform draws it (Robolectric's goldens cannot). 活動 pushes 流量詳情 (a traffic tile), 統計 (the top
 * bar), 節點 (the search pill and the outbound row) and the node in use (its row); 節點 pushes a node's page; the nav
 * switches to 設定, which pushes 主題. Back returns. Start it from the launcher's "doona RV sample", or with
 * `adb shell am start -n dev.doona.android/dev.doona.android.prototype.rv.RvPreviewActivity`; add `--es hero Ring` (any
 * [RvHeroVariant] by name) to see 活動 with one of the hero alternatives.
 */
class RvPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val hero = intent.getStringExtra(EXTRA_HERO)?.let { name -> RvHeroVariant.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } } ?: RvHeroVariant.Map
        setContent {
            val dark = isSystemInDarkTheme()
            RvTheme(rvSystemScheme(dark), dark) {
                // The pages pushed over the tab home, last on top: a route's name, and a node's id after a colon.
                var stack by rememberSaveable { mutableStateOf(listOf(ACTIVITY)) }
                BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
                RvPreviewPage(stack.last(), hero, push = { stack = stack + it }, pop = { stack = stack.dropLast(1) }, tab = { stack = listOf(it) })
            }
        }
    }
}

@Composable
private fun RvPreviewPage(route: String, hero: RvHeroVariant, push: (String) -> Unit, pop: () -> Unit, tab: (String) -> Unit) {
    val nodes = remember { sampleNodesPage() }
    var settings by remember { mutableStateOf(rvSampleSettings()) }
    // 策略 has no sample page yet.
    val onTab: (Int) -> Unit = { i -> TABS[i]?.let(tab) }
    when (route.substringBefore(':')) {
        TRAFFIC -> RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = pop)

        STATS -> RvStatsScreen(rvStatsSample(), onBack = pop)

        NODES -> RvNodesPage(nodes, onBack = pop, onFilter = {}, onOpen = { push("$NODE:$it") }, onAllNodes = {})

        NODE -> {
            val node = nodes.nodes.first { it.id == route.substringAfter(':') }
            RvNodeDetail(node, current = node.id == nodes.currentId, nodes.testedAtEpochMs, onBack = pop, onUse = {}, onTest = {})
        }

        SETTINGS -> RvSettingsScreen(settings, onChange = { settings = it }, onOpenTheme = { push(THEME) }, onTab = onTab)

        THEME -> RvThemeScreen(settings.appearance, { settings = settings.copy(appearance = it) }, rvSystemScheme(false), rvSystemScheme(true), onBack = pop)

        else -> {
            val routes = RvActivityRoutes({ push(TRAFFIC) }, { push(STATS) }, { push(NODES) }, { push("$NODE:${nodes.currentId}") }, onTab)
            RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, routes, hero = hero)
        }
    }
}

private const val EXTRA_HERO = "hero"
private const val ACTIVITY = "activity"
private const val TRAFFIC = "traffic"
private const val STATS = "stats"
private const val NODES = "nodes"
private const val NODE = "node"
private const val SETTINGS = "settings"
private const val THEME = "theme"
private val TABS = mapOf(RV_NAV_ACTIVITY to ACTIVITY, RV_NAV_SETTINGS to SETTINGS)
