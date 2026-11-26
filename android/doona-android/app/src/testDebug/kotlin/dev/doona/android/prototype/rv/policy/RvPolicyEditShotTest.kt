package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.policies.RvPolicyProfile
import dev.doona.android.feature.policies.rvDeleteRefusedBanner
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
 * 策略's groups with the add and remove controls in style A (the owner's open question; owner 2026-11-26
 * 「要加要減的按鈕我都要看到」): `policy-a` is the approved page with 編輯 after the bar's two actions; `policy-a-editing`
 * the approved data in edit mode (a remove disc on each group, 新增群組 ending the card, 完成 in the nav's place). On the
 * hand-written config ([RvNativeSample.profile]): `policy-a-native-editing`; `policy-a-confirm` the question for 東京,
 * which leaves 日本's members too; `policy-a-refused` the banner of 影音, which a rule still sends to; `policy-a-empty`
 * after the last group went, in edit mode, the card holding the add row alone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPolicyEditShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_APPROVED)
    fun aLight() = shot("policy-a", false) { Page(editing = false) }

    @Test
    @Config(qualifiers = WHOLE_APPROVED)
    fun aDark() = shot("policy-a", true) { Page(editing = false) }

    @Test
    @Config(qualifiers = WHOLE)
    fun editingLight() = shot("policy-a-editing", false) { Page() }

    @Test
    @Config(qualifiers = WHOLE)
    fun editingDark() = shot("policy-a-editing", true) { Page() }

    @Test
    @Config(qualifiers = WHOLE_NATIVE)
    fun nativeLight() = shot("policy-a-native-editing", false) { Page(profile = RvNativeSample.profile) }

    @Test
    @Config(qualifiers = WHOLE_NATIVE)
    fun nativeDark() = shot("policy-a-native-editing", true) { Page(profile = RvNativeSample.profile) }

    @Test
    @Config(qualifiers = WHOLE_NATIVE_FS200)
    fun nativeFs200() = compose.rvShot("policy-a-native-editing", false, before = { assertWhole() }, fontScale = RV_FS200) { Page(profile = RvNativeSample.profile) }

    @Test
    @Config(qualifiers = "en-w412dp-h1627dp-notnight-xhdpi")
    fun nativeEnLight() = compose.rvShot("policy-a-native-editing", false, before = { assertWhole() }, english = true) { Page(profile = RvNativeSample.profile) }

    @Test fun confirmLight() = confirm(false)

    @Test fun confirmDark() = confirm(true)

    @Test
    @Config(qualifiers = WHOLE_REFUSED)
    fun refusedLight() = shot("policy-a-refused", false) { Refused() }

    @Test
    @Config(qualifiers = WHOLE_REFUSED)
    fun refusedDark() = shot("policy-a-refused", true) { Refused() }

    @Test
    @Config(qualifiers = WHOLE_EMPTY)
    fun emptyLight() = shot("policy-a-empty", false) { Page(profile = RvNativeSample.profileNoGroups) }

    @Test
    @Config(qualifiers = WHOLE_EMPTY)
    fun emptyDark() = shot("policy-a-empty", true) { Page(profile = RvNativeSample.profileNoGroups) }

    @Test
    fun thePencilEntersEditModeAndTheSheetRemovesTheGroup() {
        var editing by mutableStateOf(false)
        val edits = mutableListOf<Boolean>()
        val removed = mutableListOf<String>()
        val edit = { on: Boolean ->
            edits += on
            editing = on
        }
        compose.setContent {
            Themed {
                RvPolicyScreenSample(RULE, {}, groupsEdit = RvListEdit(editing, edit, onRemove = { removed += it }), profile = RvNativeSample.profile)
            }
        }
        compose.onNodeWithContentDescription("刪除 ${RvNativeSample.TOKYO}").assertDoesNotExist()
        compose.onNodeWithContentDescription("編輯").performClick()
        compose.onNodeWithContentDescription("刪除 ${RvNativeSample.TOKYO}").performScrollTo().performClick()
        compose.onNodeWithText("這個群組會從設定檔移除，也不再是 ${RvNativeSample.JAPAN} 的成員。").assertExists()
        compose.onNodeWithText("刪除").performClick()
        compose.onNodeWithText("完成").performClick()
        assertEquals(listOf("g-${RvNativeSample.TOKYO}"), removed)
        assertEquals(listOf(true, false), edits)
    }

    @Test
    fun theApprovedPageHasNoPencil() {
        compose.setContent { Themed { RvPolicyScreenSample(RULE, {}) } }
        compose.onNodeWithContentDescription("編輯").assertDoesNotExist()
    }

    private fun confirm(dark: Boolean) = compose.rvShot(
        "policy-a-confirm",
        dark,
        before = {
            onNodeWithContentDescription("刪除 ${RvNativeSample.TOKYO}").performScrollTo().performClick()
        },
        windows = true,
    ) { Page(profile = RvNativeSample.profile) }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot(name, dark, before = { assertWhole() }, content = content)

    @Composable
    private fun Page(editing: Boolean = true, profile: RvPolicyProfile = rvSamplePolicyProfile) =
        RvPolicyScreenSample(RULE, {}, groupsEdit = RvListEdit(editing), profile = profile)

    @Composable
    private fun Refused() = RvPolicyScreenSample(
        RULE,
        {},
        banner = rvDeleteRefusedBanner(RvNativeSample.STREAMING, 1) {},
        groupsEdit = RvListEdit(true),
        profile = RvNativeSample.profile,
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(rvSystemScheme(false), false, content)

    private companion object {
        val RULE = RvPolicyState(mode = RvRouteMode.Rule)

        /** Windows as tall as each whole page (the approved 策略 and its pencil are +h1990dp). */
        const val WHOLE_APPROVED = "+h1990dp"
        const val WHOLE = "+h1969dp"
        const val WHOLE_NATIVE = "+h1603dp"
        const val WHOLE_REFUSED = "+h1776dp"
        const val WHOLE_EMPTY = "+h1105dp"
        const val WHOLE_NATIVE_FS200 = "+h2214dp"
    }
}
