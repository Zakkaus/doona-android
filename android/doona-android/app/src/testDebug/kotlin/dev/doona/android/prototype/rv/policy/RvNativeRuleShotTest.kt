package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.feature.model.RV_BLOCK
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.policies.RvNativeRuleAt
import dev.doona.android.feature.policies.RvRuleDraft
import dev.doona.android.feature.policies.RvRuleEditPage
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
 * Samples of a native honk rule's page (brief p14, config plan row 17): the approved rule page (`proto-rv-rule-edit-*`)
 * with the config's own groups in 送往 plus 拒絕, 所有模式都生效 (`must`) under 啟用 (pause and resume), and 位置 (move).
 * `rule-native` is a rule the form can represent; `rule-native-source` one it cannot (`&&`), shown as its source lines,
 * which open the editor there; `rule-native-paused` a paused `block` rule with `must`, first in its list.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNativeRuleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_NATIVE)
    fun nativeLight() = compose.rvShot("rule-native", dark = false, before = { assertWhole() }) { Page(OPENAI, MIDDLE) }

    @Test
    @Config(qualifiers = WHOLE_NATIVE)
    fun nativeDark() = compose.rvShot("rule-native", dark = true, before = { assertWhole() }) { Page(OPENAI, MIDDLE) }

    @Test
    fun nativeFs200() = compose.rvShot("rule-native", dark = false, fontScale = RV_FS200) { Page(OPENAI, MIDDLE) }

    @Test
    @Config(qualifiers = WHOLE_NATIVE_EN)
    fun nativeEnLight() = compose.rvShot("rule-native", dark = false, before = { assertWhole() }, english = true) { Page(OPENAI, MIDDLE) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun sourceLight() = compose.rvShot("rule-native-source", dark = false, before = { assertWhole() }) { Page(CDN, IN_SOURCE) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE)
    fun sourceDark() = compose.rvShot("rule-native-source", dark = true, before = { assertWhole() }) { Page(CDN, IN_SOURCE) }

    @Test
    fun sourceFs200() = compose.rvShot("rule-native-source", dark = false, fontScale = RV_FS200) { Page(CDN, IN_SOURCE) }

    @Test
    @Config(qualifiers = WHOLE_SOURCE_EN)
    fun sourceEnLight() = compose.rvShot("rule-native-source", dark = false, before = { assertWhole() }, english = true) { Page(CDN, IN_SOURCE) }

    @Test
    @Config(qualifiers = WHOLE_PAUSED)
    fun pausedLight() = compose.rvShot("rule-native-paused", dark = false, before = { assertWhole() }) { Page(ADS, FIRST) }

    @Test
    @Config(qualifiers = WHOLE_PAUSED)
    fun pausedDark() = compose.rvShot("rule-native-paused", dark = true, before = { assertWhole() }) { Page(ADS, FIRST) }

    @Test
    fun pausedFs200() = compose.rvShot("rule-native-paused", dark = false, fontScale = RV_FS200) { Page(ADS, FIRST) }

    @Test
    @Config(qualifiers = WHOLE_PAUSED_EN)
    fun pausedEnLight() = compose.rvShot("rule-native-paused", dark = false, before = { assertWhole() }, english = true) { Page(ADS, FIRST) }

    @Test
    fun moveUpAndDownNameTheNeighbouringPlaces() {
        val moves = mutableListOf<Int>()
        compose.setContent { Themed { RvRuleEditPage(OPENAI, {}, {}, {}, {}, RvNativeSample.targets, MIDDLE, onMove = { moves += it }) } }
        // The rows sit under the floating 套用 once scrolled to, so they are pressed by their action, not a touch.
        compose.onNodeWithText("上移").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("下移").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(MIDDLE.index - 1, MIDDLE.index + 1), moves)
    }

    @Test
    fun theFirstRuleOffersOnlyMoveDown() {
        compose.setContent { Themed { RvRuleEditPage(ADS, {}, {}, {}, {}, RvNativeSample.targets, FIRST) } }
        compose.onNodeWithText("上移").assertDoesNotExist()
        compose.onNodeWithText("下移").assertExists()
    }

    @Test
    fun blockIsATargetOfANativeRule() {
        var draft by mutableStateOf(OPENAI)
        compose.setContent { Themed { RvRuleEditPage(draft, { draft = it }, {}, {}, {}, RvNativeSample.targets, MIDDLE) } }
        compose.onNodeWithText("拒絕").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(RV_BLOCK, draft.targetId)
    }

    @Test
    fun theApprovedPageOffersNoBlock() {
        compose.setContent { Themed { RvRuleEditPage(OPENAI, {}, {}, {}, {}, rvSampleTargets) } }
        compose.onNodeWithText("拒絕").assertDoesNotExist()
    }

    @Test
    fun aStatementLineOpensTheSourceThere() {
        var opened: RvDiffAt? = null
        compose.setContent { Themed { RvRuleEditPage(CDN, {}, {}, {}, {}, RvNativeSample.targets, IN_SOURCE, onOpenSource = { opened = it }) } }
        compose.onNodeWithContentDescription("第 $CDN_LINE 行：$CDN_TEXT", substring = true).performClick()
        assertEquals(RvDiffAt(CDN_LINE, base = false), opened)
    }

    @Test
    fun aStatementShownAsSourceCanBeApplied() {
        compose.setContent { Themed { RvRuleEditPage(CDN, {}, {}, {}, {}, RvNativeSample.targets, IN_SOURCE) } }
        compose.onNodeWithText("套用").assertIsEnabled()
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = dev.doona.android.designsystem.rv.RvTheme(
        dev.doona.android.prototype.rv.seedColorScheme(dev.doona.android.prototype.rv.Seed.Cyan.argb, false),
        false,
        content,
    )

    /** The page over [start], held here as a route would hold it, at [at]. */
    @Composable
    private fun Page(start: RvRuleDraft, at: RvNativeRuleAt) {
        var draft by remember { mutableStateOf(start) }
        RvRuleEditPage(draft, { draft = it }, onBack = {}, onApply = {}, onDelete = {}, targets = RvNativeSample.targets, native = at)
    }

    private companion object {
        const val CDN_LINE = 18
        const val CDN_TEXT = "  domain(suffix: cdn.example) && l4proto(tcp) -> direct"
        val OPENAI = RvRuleDraft(RvMatchKind.Keyword, "openai", "g-美國")
        val CDN = RvRuleDraft(RvMatchKind.Suffix, "", RV_DIRECT)
        val ADS = RvRuleDraft(RvMatchKind.Suffix, "ads.example", RV_BLOCK, enabled = false, must = true)
        val MIDDLE = RvNativeRuleAt(3, 8)
        val FIRST = RvNativeRuleAt(1, 8)
        val IN_SOURCE = RvNativeRuleAt(
            12,
            20,
            RvDiffFile("rules/cn.dae", listOf(listOf(RvDiffLine(RvDiffKind.Same, CDN_LINE, CDN_TEXT)))),
        )

        /** Windows as tall as each page whole. */
        const val WHOLE_NATIVE = "+h1449dp"
        const val WHOLE_NATIVE_EN = "en-w412dp-h1449dp-notnight-xhdpi"
        const val WHOLE_SOURCE = "+h1495dp"
        const val WHOLE_SOURCE_EN = "en-w412dp-h1491dp-notnight-xhdpi"
        const val WHOLE_PAUSED = "+h1395dp"
        const val WHOLE_PAUSED_EN = "en-w412dp-h1391dp-notnight-xhdpi"
    }
}
