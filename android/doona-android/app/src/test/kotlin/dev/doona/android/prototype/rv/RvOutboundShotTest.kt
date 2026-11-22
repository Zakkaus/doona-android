package dev.doona.android.prototype.rv

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.prototype.rv.data.RvRouteMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The four outbound card alternatives the owner compares (plans/briefs/rv-outbound-variants.md), light and dark, each in
 * its place on 活動 with the page scrolled until the whole card shows: routing by rules (`proto-rv-activity-outbound-c<N>-*`)
 * and in 全域 (`…-c<N>-global-*`). C1, the rule list, is the default's `proto-rv-activity-*`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvOutboundShotTest(private val number: Int) {
    private val outbound = OUTBOUNDS[number - 2]

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun rulesLight() = shot("", dark = false, RvRouteMode.Rule)

    @Test
    fun rulesDark() = shot("", dark = true, RvRouteMode.Rule)

    @Test
    fun globalLight() = shot("-global", dark = false, RvRouteMode.Global)

    @Test
    fun globalDark() = shot("-global", dark = true, RvRouteMode.Global)

    private fun shot(suffix: String, dark: Boolean, mode: RvRouteMode) = compose.rvShot("activity-outbound-c$number$suffix", dark, before = {
        // The page, not C3's row of chips, past the header, so the card sits under the top bar and shows whole.
        onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, SCROLL_DP * density.density) }
        waitForIdle()
    }) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, mode = mode, outbound = outbound) }

    companion object {
        @JvmStatic
        @Parameters(name = "c{0}")
        fun outbounds(): List<Array<Any>> = OUTBOUNDS.indices.map { arrayOf(it + 2) }

        /** C2 to C5 in the brief's order. */
        private val OUTBOUNDS = listOf(RvOutboundVariant.Share, RvOutboundVariant.Chips, RvOutboundVariant.Routes, RvOutboundVariant.Tiles)

        /** The header's height less the top bar and a gap, so the card starts just under the bar. */
        const val SCROLL_DP = 360f
    }
}
