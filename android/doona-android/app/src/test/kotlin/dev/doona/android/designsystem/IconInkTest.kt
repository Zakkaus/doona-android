package dev.doona.android.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaIconInk
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.inkInsets
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The ink table the top bar aligns by is what the drawables draw: each icon is rendered and its shape's margins measured. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class IconInkTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTableHoldsWhatEveryIconDraws() {
        val icons = DoonaIconInk.icons.toList()
        val size = DoonaTheme.size.icon
        compose.setDoonaContent { androidx.compose.foundation.layout.Column { icons.forEach { DoonaIcon(it, DoonaTheme.colors.text.primary, Modifier.testTag("icon-$it")) } } }
        icons.forEach { icon ->
            val ink = compose.onNodeWithTag("icon-$icon").inkInsets(compose.density.density)
            assertNotNull("icon $icon draws nothing", ink)
            assertEquals("icon $icon: space before its shape", DoonaIconInk.start(icon, size).value, ink!!.left, 0.6f)
            assertEquals("icon $icon: space after its shape", DoonaIconInk.end(icon, size).value, ink.right, 0.6f)
        }
    }
}
