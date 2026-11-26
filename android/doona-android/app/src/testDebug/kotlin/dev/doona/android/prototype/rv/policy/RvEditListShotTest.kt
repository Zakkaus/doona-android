package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvEditPart
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.policies.RvRulesPage
import dev.doona.android.feature.policies.title
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
 * Samples of the add and remove controls (owner 2026-11-26: 「要加要減的按鈕我都要看到」; on the right, 13:53), on the
 * native rule list and on a group's members, each whole page in one capture: a remove control at each row's end and an
 * add row ending the card, the confirm sheet (`-confirm`), a delete the phone could not store (`-failed`), and the list
 * after its last removal (`-empty`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvEditListShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_RULES)
    fun rulesLight() = compose.rvShot("rules-remove", dark = false, before = { assertWhole() }) { Rules() }

    @Test
    @Config(qualifiers = WHOLE_RULES)
    fun rulesDark() = compose.rvShot("rules-remove", dark = true, before = { assertWhole() }) { Rules() }

    @Test
    fun rulesConfirmLight() = compose.rvShot("rules-remove-confirm", dark = false, windows = true, before = { pickFirstRemove() }) { Rules() }

    @Test
    fun rulesConfirmDark() = compose.rvShot("rules-remove-confirm", dark = true, windows = true, before = { pickFirstRemove() }) { Rules() }

    @Test
    @Config(qualifiers = WHOLE_RULES_FAILED)
    fun rulesFailedLight() = compose.rvShot("rules-remove-failed", dark = false, before = { assertWhole() }) { Rules(failed = true) }

    @Test
    @Config(qualifiers = WHOLE_RULES_FAILED)
    fun rulesFailedDark() = compose.rvShot("rules-remove-failed", dark = true, before = { assertWhole() }) { Rules(failed = true) }

    @Test
    fun rulesEmptyLight() = compose.rvShot("rules-remove-empty", dark = false) { Rules(RvNativeSample.restOnly) }

    @Test
    fun rulesEmptyDark() = compose.rvShot("rules-remove-empty", dark = true) { Rules(RvNativeSample.restOnly) }

    @Test
    @Config(qualifiers = WHOLE_GROUP)
    fun groupLight() = compose.rvShot("group-edit", dark = false, before = { assertWhole() }) { Group() }

    @Test
    @Config(qualifiers = WHOLE_GROUP)
    fun groupDark() = compose.rvShot("group-edit", dark = true, before = { assertWhole() }) { Group() }

    @Test
    @Config(qualifiers = WHOLE_GROUP_EN)
    fun groupEn() = compose.rvShot("group-edit", dark = false, english = true, before = { assertWhole() }) { Group() }

    @Test
    fun groupFs200() = compose.rvShot("group-edit", dark = false, fontScale = RV_FS200) { Group() }

    @Test
    fun groupConfirmLight() = compose.rvShot("group-edit-confirm", dark = false, windows = true, before = { pickFirstRemove() }) { Group() }

    @Test
    fun groupConfirmDark() = compose.rvShot("group-edit-confirm", dark = true, windows = true, before = { pickFirstRemove() }) { Group() }

    /** A member's remove control asks first, and 移除 drops only that member. */
    @Test
    fun removingAMemberAsksAndDropsOnlyIt() {
        compose.setContent { dev.doona.android.designsystem.rv.RvTheme(dev.doona.android.designsystem.rv.rvSystemScheme(false), false) { Group() } }
        val before = compose.onAllNodesWithTag(RvEditPart.REMOVE).fetchSemanticsNodes().size
        assertEquals(RvNativeSample.overseas.members.size, before)
        compose.onAllNodesWithTag(RvEditPart.REMOVE)[0].performClick()
        compose.onNodeWithText("移除").performClick()
        compose.waitForIdle()
        assertEquals(before - 1, compose.onAllNodesWithTag(RvEditPart.REMOVE).fetchSemanticsNodes().size)
        compose.onAllNodesWithTag(RvEditPart.ADD).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.pickFirstRemove() {
        onAllNodesWithTag(RvEditPart.REMOVE)[0].performClick()
    }

    @Composable
    private fun Rules(rules: List<RvPolicyRule> = RvNativeSample.rules, failed: Boolean = false) {
        val banner = if (failed) {
            RvNotice(
                RvNoticeKind.Error,
                stringResource(R.string.rv_list_delete_failed_title, rules.first().match.title()),
                stringResource(R.string.rv_list_delete_failed_storage),
                stringResource(R.string.rv_retry),
                {},
                titleKeepWords = false,
            )
        } else {
            null
        }
        RvRulesPage(rules, emptyList(), editing = false, onBack = {}, banner = banner, removable = true)
    }

    @Composable
    private fun Group() {
        var draft by remember { mutableStateOf(RvNativeSample.overseas) }
        var removing by remember { mutableStateOf<RvMemberRef?>(null) }
        RvGroupEditPage(draft, { draft = it }, onBack = {}, removing = removing, onRemoving = { removing = it })
    }

    private companion object {
        const val WHOLE_RULES = "+h1277dp"
        const val WHOLE_RULES_FAILED = "+h1402dp"
        const val WHOLE_GROUP = "+h1523dp"
        const val WHOLE_GROUP_EN = "en-w412dp-h1525dp-notnight-xhdpi"
    }
}
