package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.toSize
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.subscriptions.RvAddFailure
import dev.doona.android.feature.subscriptions.RvAddPhase
import dev.doona.android.feature.subscriptions.RvAddState
import dev.doona.android.feature.subscriptions.RvSubAdd
import dev.doona.android.feature.subscriptions.RvSubsActions
import dev.doona.android.feature.subscriptions.RvSubsPage
import dev.doona.android.feature.subscriptions.RvSubsState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.RvNodePickerSample
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyScreenSample
import dev.doona.android.prototype.rv.settings.RvPerAppActions
import dev.doona.android.prototype.rv.settings.RvPerAppScreen
import dev.doona.android.prototype.rv.settings.RvSave
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import dev.doona.android.prototype.rv.states.RvActionFailed
import dev.doona.android.prototype.rv.states.RvActivityNotice
import dev.doona.android.prototype.rv.states.rvActionFailedBanner
import dev.doona.android.prototype.rv.states.rvActivityBanner
import dev.doona.android.prototype.rv.subs.RvSubAddSample
import dev.doona.android.prototype.rv.subs.rvSubsStateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The one banner and its one place. Goldens of a failed action where it happened (pv-d M8, on the unified part): 策略
 * after a mode switch met a `busy` core (`proto-rv-policy-action-failed-*`) and the pull-down node picker after a node
 * switch met an `internal` error (`-node-picker-action-failed-*`), light, dark, fs115 and fs200, the first screen. Then,
 * on every page that shows a banner, the page without it and with it: the banner stands where the first card stood, on
 * the gutter, [RvSpace.cardGap] over the next card, and everything under it moves down by its height and that gap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvBannerTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun policyLight() = shot(POLICY, dark = false)

    @Test fun policyDark() = shot(POLICY, dark = true)

    @Test fun policyFs115() = shot(POLICY, fontScale = RV_FS115)

    @Test fun policyFs200() = shot(POLICY, fontScale = RV_FS200)

    @Test fun pickerLight() = shot(PICKER, dark = false)

    @Test fun pickerDark() = shot(PICKER, dark = true)

    @Test fun pickerFs115() = shot(PICKER, fontScale = RV_FS115)

    @Test fun pickerFs200() = shot(PICKER, fontScale = RV_FS200)

    @Test fun activityPlacesItsBanner() = places(ACTIVITY)

    @Test fun policyPlacesItsBanner() = places(POLICY)

    @Test fun pickerPlacesItsBanner() = places(PICKER)

    @Test fun subsPlacesItsBanner() = places(SUBS)

    @Test fun subAddPlacesItsBanner() = places(SUB_ADD)

    @Test fun perAppPlacesItsBanner() = places(PER_APP)

    // Not 回報問題: it leads with a section title, so its banner takes the title's place and keeps the section's gap
    // under it; RvLayoutTest.reportFailed measures that.

    private fun shot(page: String, dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot("$page-action-failed", dark, fontScale = fontScale) { Page(page, banner = true) }

    /** The page's cards without the banner, then with it: where the banner stands, and how far the cards moved. */
    private fun places(page: String) {
        val on = mutableStateOf(false)
        compose.rvContent(dark = false) { Page(page, on.value) }
        compose.waitForIdle()
        val without = cards()
        on.value = true
        compose.waitForIdle()
        val shown = cards()
        val banner = shown.single { it.second }.first
        val after = shown.filter { !it.second && it.first.top > banner.top }.map { it.first }
        val before = without.map { it.first }
        val width = compose.onRoot().fetchSemanticsNode().let { it.size.width / it.layoutInfo.density.density }
        val gutter = RvSpace.gutter.value
        val gap = RvSpace.cardGap.value
        assertTrue("$page has no card under its banner", after.isNotEmpty() && before.isNotEmpty())
        assertEquals("$page: the banner's start", gutter, banner.left, TOLERANCE)
        assertEquals("$page: the banner's end", width - gutter, banner.right, TOLERANCE)
        assertEquals("$page: the banner stands where the first card stood", before.first().top, banner.top, TOLERANCE)
        assertEquals("$page: from the banner to the next card", gap, after.first().top - banner.bottom, TOLERANCE)
        val moved = before.zip(after).filter { (old, new) -> abs(new.top - old.top - banner.height - gap) > TOLERANCE }
        assertTrue("$page: cards that did not move by the banner and its gap: $moved", moved.isEmpty())
    }

    /** Every card on the page in root dp, top to bottom, with whether it is the banner (the one card a screen reader announces). */
    private fun cards(): List<Pair<Rect, Boolean>> = compose.onAllNodesWithTag(RvPart.CARD, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
        val d = node.layoutInfo.density.density
        Rect(node.positionInRoot / d, node.size.toSize() / d) to (node.config.getOrNull(SemanticsProperties.LiveRegion) != null)
    }.filter { it.first.left >= 0f }.sortedWith(compareBy({ it.first.top }, { it.first.left }))

    @Composable
    private fun Page(page: String, banner: Boolean) {
        when (page) {
            ACTIVITY -> RvActivityScreenSample(rvIdleActivity(), onAction = {}, onConnect = {}, banner = if (banner) rvActivityBanner(RvActivityNotice.VpnTaken) {} else null)

            POLICY -> RvPolicyScreenSample(RvPolicyState(), onChange = {}, banner = failed(banner, RvActionFailed.SwitchMode, R.string.error_busy))

            PICKER -> RvNodePickerSample(
                RvData.nodes,
                RvData.subscriptions,
                RvData.CURRENT_ID,
                onPick = {},
                onDetail = {},
                onClose = {},
                banner = failed(banner, RvActionFailed.SwitchNode, R.string.error_internal),
            )

            SUBS -> RvSubsPage(rvSubsStateSample(failedId = if (banner) RvData.subscriptions.first().id else null), RvSubsActions())

            SUB_ADD -> RvSubAddSample(RvAddState(phase = if (banner) RvAddPhase.Failed(RvAddFailure.Unreachable) else RvAddPhase.Editing), {}, onPaste = {}, onImport = {}, onBack = {})

            else -> RvPerAppScreen(rvPerAppSample("").copy(save = if (banner) RvSave.Failed else RvSave.Idle), RvPerAppActions(), onBack = {})
        }
    }

    @Composable
    private fun failed(banner: Boolean, action: RvActionFailed, reason: Int) = if (banner) rvActionFailedBanner(action, reason) {} else null

    private companion object {
        const val ACTIVITY = "activity"
        const val POLICY = "policy"
        const val PICKER = "node-picker"
        const val SUBS = "subs"
        const val SUB_ADD = "sub-add"
        const val PER_APP = "perapp"
        const val TOLERANCE = 0.5f
    }
}
