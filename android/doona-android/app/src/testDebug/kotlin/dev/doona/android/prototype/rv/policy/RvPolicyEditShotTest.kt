package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
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
import dev.doona.android.feature.settings.rvDeleteRefusedBanner
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
 * 策略's groups with the add and remove controls (owner 2026-11-26 「要加要減的按鈕我都要看到」; on the right, 13:53):
 * `policy-edit` the approved data, a remove control at each group's end and 新增群組 ending the card. On the hand-written
 * config ([RvNativeSample.profile]): `policy-edit-native`; `policy-edit-confirm` the question for 東京, which leaves
 * 日本's members too; `policy-edit-refused` the banner of 影音, which a rule still sends to; `policy-edit-empty` after the
 * last group went, the card holding the add row alone.
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
    @Config(qualifiers = WHOLE)
    fun editLight() = shot("policy-edit", false) { Page() }

    @Test
    @Config(qualifiers = WHOLE)
    fun editDark() = shot("policy-edit", true) { Page() }

    @Test
    @Config(qualifiers = WHOLE_NATIVE)
    fun nativeLight() = shot("policy-edit-native", false) { Page(RvNativeSample.profile) }

    @Test
    @Config(qualifiers = WHOLE_NATIVE)
    fun nativeDark() = shot("policy-edit-native", true) { Page(RvNativeSample.profile) }

    @Test
    @Config(qualifiers = WHOLE_NATIVE_FS200)
    fun nativeFs200() = compose.rvShot("policy-edit-native", false, before = { assertWhole() }, fontScale = RV_FS200) { Page(RvNativeSample.profile) }

    @Test
    @Config(qualifiers = "en-w412dp-h1647dp-notnight-xhdpi")
    fun nativeEnLight() = compose.rvShot("policy-edit-native", false, before = { assertWhole() }, english = true) { Page(RvNativeSample.profile) }

    @Test fun confirmLight() = confirm(false)

    @Test fun confirmDark() = confirm(true)

    @Test
    @Config(qualifiers = WHOLE_REFUSED)
    fun refusedLight() = shot("policy-edit-refused", false) { Refused() }

    @Test
    @Config(qualifiers = WHOLE_REFUSED)
    fun refusedDark() = shot("policy-edit-refused", true) { Refused() }

    @Test
    @Config(qualifiers = WHOLE_EMPTY)
    fun emptyLight() = shot("policy-edit-empty", false) { Page(RvNativeSample.profileNoGroups) }

    @Test
    @Config(qualifiers = WHOLE_EMPTY)
    fun emptyDark() = shot("policy-edit-empty", true) { Page(RvNativeSample.profileNoGroups) }

    @Test
    fun aGroupsRemoveControlAsksAndTheSheetRemovesTheGroup() {
        val removed = mutableListOf<String>()
        compose.setContent {
            Themed { RvPolicyScreenSample(RULE, {}, groupsEdit = RvListEdit({}, { removed += it }), profile = RvNativeSample.profile) }
        }
        compose.onNodeWithContentDescription("刪除 ${RvNativeSample.TOKYO}").performScrollTo().performClick()
        compose.onNodeWithText("這個群組會從設定檔移除，也不再是 ${RvNativeSample.JAPAN} 的成員。").assertExists()
        compose.onNodeWithText("刪除").performClick()
        assertEquals(listOf("g-${RvNativeSample.TOKYO}"), removed)
    }

    @Test
    fun theApprovedPageHasNoRemoveControl() {
        compose.setContent { Themed { RvPolicyScreenSample(RULE, {}, profile = RvNativeSample.profile) } }
        compose.onNodeWithContentDescription("刪除 ${RvNativeSample.TOKYO}").assertDoesNotExist()
    }

    private fun confirm(dark: Boolean) = compose.rvShot(
        "policy-edit-confirm",
        dark,
        before = {
            onNodeWithContentDescription("刪除 ${RvNativeSample.TOKYO}").performScrollTo().performClick()
        },
        windows = true,
    ) { Page(RvNativeSample.profile) }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot(name, dark, before = { assertWhole() }, content = content)

    @Composable
    private fun Page(profile: RvPolicyProfile = rvSamplePolicyProfile) = RvPolicyScreenSample(RULE, {}, groupsEdit = RvListEdit({}, {}), profile = profile)

    @Composable
    private fun Refused() = RvPolicyScreenSample(
        RULE,
        {},
        banner = rvDeleteRefusedBanner(RvNativeSample.STREAMING, 1) {},
        groupsEdit = RvListEdit({}, {}),
        profile = RvNativeSample.profile,
    )

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(rvSystemScheme(false), false, content)

    private companion object {
        val RULE = RvPolicyState(mode = RvRouteMode.Rule)

        /** Windows as tall as each whole page (the approved 策略 and its pencil are +h1990dp). */
        const val WHOLE = "+h1989dp"
        const val WHOLE_NATIVE = "+h1603dp"
        const val WHOLE_REFUSED = "+h1777dp"
        const val WHOLE_EMPTY = "+h1125dp"
        const val WHOLE_NATIVE_FS200 = "+h2246dp"
    }
}
