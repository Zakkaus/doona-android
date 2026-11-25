package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 依 App's two layouts for the owner, the whole page (windows measured with `assertWhole`), light, dark and fs200, over
 * the layouts' sample (twelve apps, three checked under 只有勾選的, saved): `perapp-v1` keeps the policies' card and
 * checks each app with `RvCheckRow`; `perapp-v2` puts the policies in 策略's segmented control under the title. Then
 * what the two layouts do: a segment picks its policy, and an app row toggles its app as a checkbox.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPerAppLookShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+h1526dp")
    fun v1Light() = shot(RvPerAppLook.Checks, "v1", dark = false)

    @Test
    @Config(qualifiers = "+h1526dp")
    fun v1Dark() = shot(RvPerAppLook.Checks, "v1", dark = true)

    @Test
    @Config(qualifiers = "+h2225dp")
    fun v1Fs200() = shot(RvPerAppLook.Checks, "v1", fontScale = RV_FS200)

    @Test
    @Config(qualifiers = "+h1384dp")
    fun v2Light() = shot(RvPerAppLook.Segmented, "v2", dark = false)

    @Test
    @Config(qualifiers = "+h1384dp")
    fun v2Dark() = shot(RvPerAppLook.Segmented, "v2", dark = true)

    @Test
    @Config(qualifiers = "+h2045dp")
    fun v2Fs200() = shot(RvPerAppLook.Segmented, "v2", fontScale = RV_FS200)

    @Test
    fun aSegmentPicksItsPolicy() {
        var picked: RvAppPolicy? = null
        val actions = RvPerAppActions(policy = { picked = it })
        compose.setContent { Themed { RvPerAppScreen(rvPerAppLookSample(""), actions, onBack = {}, look = RvPerAppLook.Segmented) } }
        compose.onNodeWithText(rvText(R.string.perapp_policy_except)).performClick()
        assertEquals(RvAppPolicy.Except, picked)
    }

    @Test
    fun anAppRowIsACheckboxThatTogglesItsApp() {
        var toggled: Pair<String, Boolean>? = null
        val actions = RvPerAppActions(toggle = { pkg, on -> toggled = pkg to on })
        compose.setContent { Themed { RvPerAppScreen(rvPerAppLookSample(""), actions, onBack = {}, look = RvPerAppLook.Checks) } }
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onNode(hasText("Chat") and checkbox and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On)).assertExists()
        compose.onNode(hasText("Calendar") and checkbox and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)).assertExists()
        // Chat is one of the three checked, so a tap unchecks it.
        compose.onNodeWithText("Chat").performClick()
        assertEquals("com.example.chat" to false, toggled)
    }

    private fun shot(look: RvPerAppLook, name: String, dark: Boolean = false, fontScale: Float = 1f) {
        compose.rvShot("perapp-$name", dark, before = { assertWhole() }, fontScale = fontScale) {
            RvPerAppScreen(rvPerAppLookSample(""), RvPerAppActions(), onBack = {}, look = look)
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
}
