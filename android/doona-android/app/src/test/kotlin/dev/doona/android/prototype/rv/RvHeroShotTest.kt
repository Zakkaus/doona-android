package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvRouteMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The four hero alternatives the owner compares (plans/briefs/rv-hero-variants.md), each light and dark and viewport
 * only: routing by rules (`proto-rv-activity-hero-v<N>-*`), in 全域 (`…-v<N>-global-*`) and disconnected
 * (`…-v<N>-idle-*`). V4 also with the main node slow (`proto-rv-activity-hero-v4-slow-*`), its warning tint.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvHeroShotTest(private val number: Int) {
    private val hero = HEROES[number - 1]

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun rulesLight() = shot("", dark = false) { Hero(rvSampleActivity()) }

    @Test
    fun rulesDark() = shot("", dark = true) { Hero(rvSampleActivity()) }

    @Test
    fun globalLight() = shot("-global", dark = false) { Hero(rvSampleActivity(), RvRouteMode.Global) }

    @Test
    fun globalDark() = shot("-global", dark = true) { Hero(rvSampleActivity(), RvRouteMode.Global) }

    @Test
    fun idleLight() = shot("-idle", dark = false) { Hero(rvIdleActivity()) }

    @Test
    fun idleDark() = shot("-idle", dark = true) { Hero(rvIdleActivity()) }

    @Test
    fun slowLight() = slow(dark = false)

    @Test
    fun slowDark() = slow(dark = true)

    /** Only V4 tints by the main node's latency; the others draw the same as [rulesLight]. */
    private fun slow(dark: Boolean) {
        if (hero != RvHeroVariant.Quiet) return
        shot("-slow", dark) { Hero(rvSampleActivity().let { it.copy(node = it.node?.copy(latencyMs = SLOW_MS)) }) }
    }

    private fun shot(suffix: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot("activity-hero-v$number$suffix", dark, content = content)

    @Composable
    private fun Hero(state: ActivityUiState, mode: RvRouteMode = RvRouteMode.Rule) = RvActivityScreen(state, onAction = {}, onConnect = {}, mode = mode, hero = hero)

    companion object {
        @JvmStatic
        @Parameters(name = "v{0}")
        fun heroes(): List<Array<Any>> = HEROES.indices.map { arrayOf(it + 1) }

        /** V1 to V4 in the brief's order. */
        private val HEROES = listOf(RvHeroVariant.Mix, RvHeroVariant.Wave, RvHeroVariant.Ring, RvHeroVariant.Quiet)

        /** A latency the app calls degraded: 100 ms and over is its warning band. */
        const val SLOW_MS = 240
    }
}
