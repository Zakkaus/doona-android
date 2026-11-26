package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 路由測試 (config plan line 122; no prototype, sample only), each whole page in one capture: as it opens
 * (`trace`), a rule decides (`-rule`), a `block` rule refuses (`-block`), no rule matches and the fallback decides
 * (`-fallback`), and a skipped statement sits before the winner (`-skipped`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRouteTraceShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun emptyLight() = shot("trace", false) { Page(RvNativeSample.traceEmpty, null) }

    @Test fun emptyDark() = shot("trace", true) { Page(RvNativeSample.traceEmpty, null) }

    @Test
    @Config(qualifiers = WHOLE_ANSWER)
    fun ruleLight() = shot("trace-rule", false) { Page(RvNativeSample.traceRuleQuery, RvNativeSample.traceRule) }

    @Test
    @Config(qualifiers = WHOLE_ANSWER)
    fun ruleDark() = shot("trace-rule", true) { Page(RvNativeSample.traceRuleQuery, RvNativeSample.traceRule) }

    @Test fun ruleFs200() = compose.rvShot("trace-rule", false, fontScale = RV_FS200) { Page(RvNativeSample.traceRuleQuery, RvNativeSample.traceRule) }

    @Test
    @Config(qualifiers = "en-w412dp-h931dp-notnight-xhdpi")
    fun ruleEnLight() = compose.rvShot("trace-rule", false, before = { assertWhole() }, english = true) { Page(RvNativeSample.traceRuleQuery, RvNativeSample.traceRule) }

    @Test
    @Config(qualifiers = WHOLE_ANSWER)
    fun blockLight() = shot("trace-block", false) { Page(RvNativeSample.traceBlockQuery, RvNativeSample.traceBlock) }

    @Test
    @Config(qualifiers = WHOLE_ANSWER)
    fun blockDark() = shot("trace-block", true) { Page(RvNativeSample.traceBlockQuery, RvNativeSample.traceBlock) }

    @Test
    @Config(qualifiers = WHOLE_ANSWER)
    fun fallbackLight() = shot("trace-fallback", false) { Page(RvNativeSample.traceFallbackQuery, RvNativeSample.traceFallback) }

    @Test
    @Config(qualifiers = WHOLE_ANSWER)
    fun fallbackDark() = shot("trace-fallback", true) { Page(RvNativeSample.traceFallbackQuery, RvNativeSample.traceFallback) }

    @Test
    @Config(qualifiers = WHOLE_SKIPPED)
    fun skippedLight() = shot("trace-skipped", false) { Page(RvNativeSample.traceSkippedQuery, RvNativeSample.traceSkipped) }

    @Test
    @Config(qualifiers = WHOLE_SKIPPED)
    fun skippedDark() = shot("trace-skipped", true) { Page(RvNativeSample.traceSkippedQuery, RvNativeSample.traceSkipped) }

    @Test
    @Config(qualifiers = WHOLE_SKIPPED_EN)
    fun skippedEnLight() = compose.rvShot("trace-skipped", false, before = { assertWhole() }, english = true) { Page(RvNativeSample.traceSkippedQuery, RvNativeSample.traceSkipped) }

    /** 測試 waits for a destination and a port in range; the warning's button opens the skipped line. */
    @Test
    fun testNeedsADestinationAndTheWarningOpensTheSkippedLine() {
        var query by mutableStateOf(RvNativeSample.traceEmpty)
        val opened = mutableListOf<Pair<String, Int>>()
        compose.setContent {
            RvTheme(rvSystemScheme(false), false) {
                RvRouteTracePage(query, { query = it }, {}, RvNativeSample.traceSkipped, onOpenSource = { file, line -> opened += file to line })
            }
        }
        compose.onNodeWithText("測試").assertIsNotEnabled()
        query = RvNativeSample.traceSkippedQuery.copy(port = "70000")
        compose.onNodeWithText("測試").assertIsNotEnabled()
        query = RvNativeSample.traceSkippedQuery
        compose.onNodeWithText("測試").assertIsEnabled()
        compose.onNodeWithText("編輯原始碼").performScrollTo().performClick()
        assertEquals(listOf("rules/lan.dae" to 3), opened)
    }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot(name, dark, before = { assertWhole() }, content = content)

    @Composable
    private fun Page(start: RvTraceQuery, result: RvTraceResult?) {
        var query by remember { mutableStateOf(start) }
        RvRouteTracePage(query, { query = it }, onBack = {}, result = result)
    }

    private companion object {
        /** Windows as tall as each whole page. */
        const val WHOLE_ANSWER = "+h936dp"
        const val WHOLE_SKIPPED = "+h1109dp"
        const val WHOLE_SKIPPED_EN = "en-w412dp-h1129dp-notnight-xhdpi"
    }
}
