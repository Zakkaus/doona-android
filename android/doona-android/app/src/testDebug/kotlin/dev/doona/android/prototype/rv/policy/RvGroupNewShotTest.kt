package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvEditPart
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvRemoveStyle
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
 * 新增群組, the page the groups list's add row opens (owner 2026-11-26: 「要加要減的按鈕我都要看到」), each whole page in
 * one capture: as it opens (`proto-rv-group-new`), filled in (`-filled`: Score over two nested groups, a node and
 * 直連), a name another group has (`-taken`), and a group the phone could not stage (`-failed`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvGroupNewShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_NEW)
    fun newLight() = compose.rvShot("group-new", dark = false, before = { assertWhole() }) { New(RvNativeSample.newGroup) }

    @Test
    @Config(qualifiers = WHOLE_NEW)
    fun newDark() = compose.rvShot("group-new", dark = true, before = { assertWhole() }) { New(RvNativeSample.newGroup) }

    @Test
    @Config(qualifiers = WHOLE_NEW_EN)
    fun newEn() = compose.rvShot("group-new", dark = false, english = true, before = { assertWhole() }) { New(RvNativeSample.newGroup) }

    @Test
    fun newFs200() = compose.rvShot("group-new", dark = false, fontScale = RV_FS200) { New(RvNativeSample.newGroup) }

    @Test
    @Config(qualifiers = WHOLE_FILLED)
    fun filledLight() = compose.rvShot("group-new-filled", dark = false, before = { assertWhole() }) { New(RvNativeSample.newStreaming) }

    @Test
    @Config(qualifiers = WHOLE_FILLED)
    fun filledDark() = compose.rvShot("group-new-filled", dark = true, before = { assertWhole() }) { New(RvNativeSample.newStreaming) }

    @Test
    @Config(qualifiers = WHOLE_FILLED_EN)
    fun filledEn() = compose.rvShot("group-new-filled", dark = false, english = true, before = { assertWhole() }) { New(RvNativeSample.newStreaming) }

    @Test
    fun filledFs200() = compose.rvShot("group-new-filled", dark = false, fontScale = RV_FS200) { New(RvNativeSample.newStreaming) }

    @Test
    @Config(qualifiers = WHOLE_TAKEN)
    fun takenLight() = compose.rvShot("group-new-taken", dark = false, before = { assertWhole() }) { New(TAKEN) }

    @Test
    @Config(qualifiers = WHOLE_TAKEN)
    fun takenDark() = compose.rvShot("group-new-taken", dark = true, before = { assertWhole() }) { New(TAKEN) }

    @Test
    @Config(qualifiers = WHOLE_FAILED)
    fun failedLight() = compose.rvShot("group-new-failed", dark = false, before = { assertWhole() }) { New(RvNativeSample.newStreaming, failed = true) }

    @Test
    @Config(qualifiers = WHOLE_FAILED)
    fun failedDark() = compose.rvShot("group-new-failed", dark = true, before = { assertWhole() }) { New(RvNativeSample.newStreaming, failed = true) }

    /** 新增 waits for a name nobody uses and one member; a member added on this page goes at once, with no sheet. */
    @Test
    fun createNeedsAFreeNameAndAMember() {
        var draft by mutableStateOf(RvNativeSample.newStreaming)
        compose.setContent { RvTheme(rvSystemScheme(false), false) { New(draft) { draft = it } } }
        compose.onNodeWithText(CREATE).assertIsEnabled()
        val members = draft.members.size
        assertEquals(members, compose.onAllNodesWithTag(RvEditPart.REMOVE).fetchSemanticsNodes().size)
        compose.onAllNodesWithTag(RvEditPart.REMOVE)[0].performClick()
        compose.waitForIdle()
        assertEquals(members - 1, draft.members.size)
        compose.onNodeWithText("移除").assertDoesNotExist()
        draft = draft.copy(name = RvNativeSample.JAPAN)
        compose.onNodeWithText(CREATE).assertIsNotEnabled()
        draft = RvNativeSample.newStreaming.copy(members = emptyList())
        compose.onNodeWithText(CREATE).assertIsNotEnabled()
        assertEquals(1, compose.onAllNodesWithTag(RvEditPart.ADD).fetchSemanticsNodes().size)
    }

    @Composable
    private fun New(start: RvGroupDraft, failed: Boolean = false, onChange: ((RvGroupDraft) -> Unit)? = null) {
        var local by remember { mutableStateOf(start) }
        val draft = if (onChange != null) start else local
        val taken = "g-${draft.name}" in RvNativeSample.targets.map { it.id }
        val banner = if (failed) {
            RvNotice(
                RvNoticeKind.Error,
                stringResource(R.string.rv_list_create_failed_title, draft.name),
                stringResource(R.string.rv_list_delete_failed_storage),
                stringResource(R.string.rv_retry),
                {},
                titleKeepWords = false,
            )
        } else {
            null
        }
        RvGroupEditPage(
            draft,
            RvRemoveStyle.Editing,
            editing = false,
            onChange = onChange ?: { local = it },
            onEdit = {},
            onBack = {},
            create = true,
            nameError = if (taken) stringResource(R.string.rv_group_name_taken) else null,
            banner = banner,
        )
    }

    private companion object {
        val TAKEN = RvNativeSample.newStreaming.copy(name = RvNativeSample.JAPAN)
        const val CREATE = "新增"

        /** Windows as tall as each whole page. */
        const val WHOLE_NEW = "+h1137dp"
        const val WHOLE_NEW_EN = "en-w412dp-h1139dp-notnight-xhdpi"
        const val WHOLE_FILLED = "+h1441dp"
        const val WHOLE_FILLED_EN = "en-w412dp-h1443dp-notnight-xhdpi"
        const val WHOLE_TAKEN = "+h1467dp"
        const val WHOLE_FAILED = "+h1598dp"
    }
}
