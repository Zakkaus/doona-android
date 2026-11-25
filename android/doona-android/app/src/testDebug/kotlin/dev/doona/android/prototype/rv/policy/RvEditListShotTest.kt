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
import dev.doona.android.designsystem.rv.RvRemoveStyle
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
 * Samples of the add and remove controls (owner 2026-11-26: 「要加要減的按鈕我都要看到」), on the native rule list and
 * on a group's members, each whole page in one capture. The owner chooses A or B (review question 2026-11-26):
 * A (`proto-rv-rules-remove-a-*`, `proto-rv-group-edit-a-*`) shows a remove disc at each row's start in edit mode;
 * B (`proto-rv-rules-remove-b-*`, `proto-rv-group-edit-b-*`) a remove control at each row's end at all times; both end
 * the card with an add row. The rest follows A until the owner answers: the confirm sheet (`-confirm`), a delete the
 * phone could not store (`-failed`), and the list after its last removal (`-empty`).
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
    @Config(qualifiers = WHOLE_RULES_A)
    fun rulesALight() = compose.rvShot("rules-remove-a", dark = false, before = { assertWhole() }) { Rules(RvRemoveStyle.Editing) }

    @Test
    @Config(qualifiers = WHOLE_RULES_A)
    fun rulesADark() = compose.rvShot("rules-remove-a", dark = true, before = { assertWhole() }) { Rules(RvRemoveStyle.Editing) }

    @Test
    @Config(qualifiers = WHOLE_RULES_B)
    fun rulesBLight() = compose.rvShot("rules-remove-b", dark = false, before = { assertWhole() }) { Rules(RvRemoveStyle.Trailing) }

    @Test
    @Config(qualifiers = WHOLE_RULES_B)
    fun rulesBDark() = compose.rvShot("rules-remove-b", dark = true, before = { assertWhole() }) { Rules(RvRemoveStyle.Trailing) }

    @Test
    fun rulesConfirmLight() = compose.rvShot("rules-remove-a-confirm", dark = false, windows = true, before = { pickFirstRemove() }) { Rules(RvRemoveStyle.Editing) }

    @Test
    fun rulesConfirmDark() = compose.rvShot("rules-remove-a-confirm", dark = true, windows = true, before = { pickFirstRemove() }) { Rules(RvRemoveStyle.Editing) }

    @Test
    @Config(qualifiers = WHOLE_RULES_FAILED)
    fun rulesFailedLight() = compose.rvShot("rules-remove-a-failed", dark = false, before = { assertWhole() }) { Rules(RvRemoveStyle.Editing, failed = true) }

    @Test
    @Config(qualifiers = WHOLE_RULES_FAILED)
    fun rulesFailedDark() = compose.rvShot("rules-remove-a-failed", dark = true, before = { assertWhole() }) { Rules(RvRemoveStyle.Editing, failed = true) }

    @Test
    fun rulesEmptyLight() = compose.rvShot("rules-remove-a-empty", dark = false) { Rules(RvRemoveStyle.Editing, RvNativeSample.restOnly) }

    @Test
    fun rulesEmptyDark() = compose.rvShot("rules-remove-a-empty", dark = true) { Rules(RvRemoveStyle.Editing, RvNativeSample.restOnly) }

    @Test
    @Config(qualifiers = WHOLE_GROUP)
    fun groupALight() = compose.rvShot("group-edit-a", dark = false, before = { assertWhole() }) { Group(RvRemoveStyle.Editing, editing = false) }

    @Test
    @Config(qualifiers = WHOLE_GROUP)
    fun groupADark() = compose.rvShot("group-edit-a", dark = true, before = { assertWhole() }) { Group(RvRemoveStyle.Editing, editing = false) }

    @Test
    @Config(qualifiers = WHOLE_GROUP_EDIT)
    fun groupAEditLight() = compose.rvShot("group-edit-a-editing", dark = false, before = { assertWhole() }) { Group(RvRemoveStyle.Editing, editing = true) }

    @Test
    @Config(qualifiers = WHOLE_GROUP_EDIT)
    fun groupAEditDark() = compose.rvShot("group-edit-a-editing", dark = true, before = { assertWhole() }) { Group(RvRemoveStyle.Editing, editing = true) }

    @Test
    @Config(qualifiers = WHOLE_GROUP_EDIT_EN)
    fun groupAEditEn() = compose.rvShot("group-edit-a-editing", dark = false, english = true, before = { assertWhole() }) { Group(RvRemoveStyle.Editing, editing = true) }

    @Test
    fun groupAEditFs200() = compose.rvShot("group-edit-a-editing", dark = false, fontScale = RV_FS200) { Group(RvRemoveStyle.Editing, editing = true) }

    @Test
    @Config(qualifiers = WHOLE_GROUP_EDIT)
    fun groupBLight() = compose.rvShot("group-edit-b", dark = false, before = { assertWhole() }) { Group(RvRemoveStyle.Trailing, editing = false) }

    @Test
    @Config(qualifiers = WHOLE_GROUP_EDIT)
    fun groupBDark() = compose.rvShot("group-edit-b", dark = true, before = { assertWhole() }) { Group(RvRemoveStyle.Trailing, editing = false) }

    @Test
    fun groupConfirmLight() = compose.rvShot("group-edit-a-confirm", dark = false, windows = true, before = { pickFirstRemove() }) { Group(RvRemoveStyle.Editing, editing = true) }

    @Test
    fun groupConfirmDark() = compose.rvShot("group-edit-a-confirm", dark = true, windows = true, before = { pickFirstRemove() }) { Group(RvRemoveStyle.Editing, editing = true) }

    /** A's remove disc asks first, and 移除 drops only that member; B's control is there without edit mode. */
    @Test
    fun removingAMemberAsksAndDropsOnlyIt() {
        compose.setContent { dev.doona.android.designsystem.rv.RvTheme(dev.doona.android.designsystem.rv.rvSystemScheme(false), false) { Group(RvRemoveStyle.Editing, editing = true) } }
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
    private fun Rules(style: RvRemoveStyle, rules: List<RvPolicyRule> = RvNativeSample.rules, failed: Boolean = false) {
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
        RvRulesPage(rules, emptyList(), editing = style == RvRemoveStyle.Editing, onBack = {}, banner = banner, removeStyle = style)
    }

    @Composable
    private fun Group(style: RvRemoveStyle, editing: Boolean) {
        var draft by remember { mutableStateOf(RvNativeSample.overseas) }
        var removing by remember { mutableStateOf<RvMemberRef?>(null) }
        RvGroupEditPage(draft, style, editing, { draft = it }, {}, onBack = {}, removing = removing, onRemoving = { removing = it })
    }

    private companion object {
        const val WHOLE_RULES_A = "+h989dp"
        const val WHOLE_RULES_B = "+h1277dp"
        const val WHOLE_RULES_FAILED = "+h1138dp"
        const val WHOLE_GROUP = "+h1465dp"
        const val WHOLE_GROUP_EDIT = "+h1523dp"
        const val WHOLE_GROUP_EDIT_EN = "en-w412dp-h1525dp-notnight-xhdpi"
    }
}
