package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's primary button in its three states, light and dark (`proto-rv-primary-button-*`): at rest, pressed and
 * disabled, on the page under the status bar; and what each state does with a tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h360dp-notnight-xhdpi")
class RvPrimaryButtonShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = compose.rvShot("primary-button", dark = false) { States() }

    @Test
    fun dark() = compose.rvShot("primary-button", dark = true) { States() }

    @Test
    fun onlyTheEnabledButtonTakesATap() {
        var taps = 0
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvPrimaryButton(ON, { taps++ })
                    RvPrimaryButton(OFF, { taps++ }, enabled = false)
                }
            }
        }
        compose.onNodeWithText(ON).assertIsEnabled().assertHeightIsAtLeast(RvSize.touch).performClick()
        compose.onNodeWithText(OFF).assertIsNotEnabled().assertHeightIsAtLeast(RvSize.touch).performClick()
        assertEquals(1, taps)
    }

    @Composable
    private fun States() {
        val held = remember { MutableInteractionSource() }
        Column(
            Modifier.fillMaxSize().background(Rv.colors.page).padding(start = RvSpace.gutter, top = TOP, end = RvSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(RvSpace.gutter + RvSpace.gutter),
        ) {
            RvPrimaryButton(ON, {})
            RvPrimaryButton(PRESSED, {}, interactionSource = held)
            RvPrimaryButton(OFF, {}, enabled = false)
        }
        // After the button, so its collector is listening when the press arrives.
        LaunchedEffect(held) { held.emit(PressInteraction.Press(Offset.Zero)) }
    }

    private companion object {
        const val ON = "測試此群組"
        const val PRESSED = "套用"
        const val OFF = "新增規則"
        val TOP = 72.dp
    }
}
