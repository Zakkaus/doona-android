package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Score group page with honk's network mode (samples on fake data, not approved), each whole in light, dark, fs115
 * and fs200, the switch 分開選擇 TCP 與 UDP over the cards: joint with UDP on TCP's node, one card headed TCP 與 UDP
 * (`proto-rv-policy-group-score-joint-*`); joint with UDP on another node because TCP's cannot carry UDP
 * (`-score-joint-unsupported-*`) or UDP's test there did not pass (`-score-joint-failed-*`); separate, a card per
 * network as on the page without the switch (`-score-separate-*`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvScoreJointShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = JOINT)
    fun jointLight() = shot(JOINT_NAME, dark = false) { Page(RvScoreSample.agreed, RvScoreSample.joint) }

    @Test
    @Config(qualifiers = JOINT)
    fun jointDark() = shot(JOINT_NAME, dark = true) { Page(RvScoreSample.agreed, RvScoreSample.joint) }

    @Test
    @Config(qualifiers = JOINT_FS115)
    fun jointFs115() = shot(JOINT_NAME, fontScale = RV_FS115) { Page(RvScoreSample.agreed, RvScoreSample.joint) }

    @Test
    @Config(qualifiers = JOINT_FS200)
    fun jointFs200() = shot(JOINT_NAME, fontScale = RV_FS200) { Page(RvScoreSample.agreed, RvScoreSample.joint) }

    @Test
    @Config(qualifiers = UNSUPPORTED)
    fun unsupportedLight() = shot(UNSUPPORTED_NAME, dark = false) { Page(RvScoreSample.chosen, RvScoreSample.jointUnsupported) }

    @Test
    @Config(qualifiers = UNSUPPORTED)
    fun unsupportedDark() = shot(UNSUPPORTED_NAME, dark = true) { Page(RvScoreSample.chosen, RvScoreSample.jointUnsupported) }

    @Test
    @Config(qualifiers = UNSUPPORTED_FS115)
    fun unsupportedFs115() = shot(UNSUPPORTED_NAME, fontScale = RV_FS115) { Page(RvScoreSample.chosen, RvScoreSample.jointUnsupported) }

    @Test
    @Config(qualifiers = UNSUPPORTED_FS200)
    fun unsupportedFs200() = shot(UNSUPPORTED_NAME, fontScale = RV_FS200) { Page(RvScoreSample.chosen, RvScoreSample.jointUnsupported) }

    @Test
    @Config(qualifiers = FAILED)
    fun failedLight() = shot(FAILED_NAME, dark = false) { Page(RvScoreSample.chosen, RvScoreSample.jointFailed) }

    @Test
    @Config(qualifiers = FAILED)
    fun failedDark() = shot(FAILED_NAME, dark = true) { Page(RvScoreSample.chosen, RvScoreSample.jointFailed) }

    @Test
    @Config(qualifiers = FAILED_FS115)
    fun failedFs115() = shot(FAILED_NAME, fontScale = RV_FS115) { Page(RvScoreSample.chosen, RvScoreSample.jointFailed) }

    @Test
    @Config(qualifiers = FAILED_FS200)
    fun failedFs200() = shot(FAILED_NAME, fontScale = RV_FS200) { Page(RvScoreSample.chosen, RvScoreSample.jointFailed) }

    @Test
    @Config(qualifiers = SEPARATE)
    fun separateLight() = shot(SEPARATE_NAME, dark = false) { Page(RvScoreSample.chosen, RvScoreSample.separate) }

    @Test
    @Config(qualifiers = SEPARATE)
    fun separateDark() = shot(SEPARATE_NAME, dark = true) { Page(RvScoreSample.chosen, RvScoreSample.separate) }

    @Test
    @Config(qualifiers = SEPARATE_FS115)
    fun separateFs115() = shot(SEPARATE_NAME, fontScale = RV_FS115) { Page(RvScoreSample.chosen, RvScoreSample.separate) }

    @Test
    @Config(qualifiers = SEPARATE_FS200)
    fun separateFs200() = shot(SEPARATE_NAME, fontScale = RV_FS200) { Page(RvScoreSample.chosen, RvScoreSample.separate) }

    /** Joint on one node: the switch off, one card headed TCP 與 UDP with TCP's comparison, no UDP card. */
    @Test
    @Config(qualifiers = JOINT)
    fun jointIsOneCard() {
        compose.rvContent(dark = false) { Page(RvScoreSample.agreed, RvScoreSample.joint) }
        assertEquals(ToggleableState.Off, switch())
        assertEquals(listOf(1, 1, 0), listOf("TCP/UDP", "已比較 4/6", "UDP").map { t -> lines().count { it == t } })
    }

    /** Joint with UDP elsewhere: TCP's card, then UDP's node saying why. */
    @Test
    @Config(qualifiers = UNSUPPORTED)
    fun fallbackSaysWhy() {
        compose.rvContent(dark = false) { Page(RvScoreSample.chosen, RvScoreSample.jointUnsupported) }
        assertEquals(listOf(1, 1, 1), listOf("TCP", "UDP", "Hong Kong 02 IPLC 不支援 UDP").map { t -> lines().count { it == t } })
    }

    @Test
    @Config(qualifiers = FAILED)
    fun failedSaysWhy() {
        compose.rvContent(dark = false) { Page(RvScoreSample.chosen, RvScoreSample.jointFailed) }
        assertEquals(1, lines().count { it == "UDP 測試未通過" })
    }

    /** Separate: the switch on and both networks' cards, each with its comparison. */
    @Test
    @Config(qualifiers = SEPARATE)
    fun separateIsTwoCards() {
        compose.rvContent(dark = false) { Page(RvScoreSample.chosen, RvScoreSample.separate) }
        assertEquals(ToggleableState.On, switch())
        assertEquals(listOf(1, 1), listOf("已比較 4/6", "已比較 2/6").map { t -> lines().count { it == t } })
    }

    private fun switch() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNode()
        .config[SemanticsProperties.ToggleableState]

    /** Every text on the page, its words as written (RvText binds them with U+2060 and no-break spaces). */
    private fun lines(): List<String> = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.map { it.text.replace("\u2060", "").replace('\u00A0', ' ') }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, before = { assertWhole() }, fontScale = fontScale, content = content)

    @Composable
    private fun Page(score: ScoreGroup, joint: RvScoreJoint) =
        RvPolicyGroupPage(RvScoreSample.group, onBack = {}, onSelect = {}, onTest = {}, score = score, joint = joint)

    private companion object {
        const val JOINT_NAME = "policy-group-score-joint"
        const val UNSUPPORTED_NAME = "policy-group-score-joint-unsupported"
        const val FAILED_NAME = "policy-group-score-joint-failed"
        const val SEPARATE_NAME = "policy-group-score-separate"

        /** Windows as tall as each page whole, rounded up to a whole dp. */
        const val JOINT = "+h1318dp"
        const val JOINT_FS115 = "+h1328dp"
        const val JOINT_FS200 = "+h2258dp"
        const val UNSUPPORTED = "+h1468dp"
        const val UNSUPPORTED_FS115 = "+h1478dp"
        const val UNSUPPORTED_FS200 = "+h2544dp"
        const val FAILED = "+h1468dp"
        const val FAILED_FS115 = "+h1478dp"
        const val FAILED_FS200 = "+h2504dp"
        const val SEPARATE = "+h1550dp"
        const val SEPARATE_FS115 = "+h1560dp"
        const val SEPARATE_FS200 = "+h2654dp"
    }
}
