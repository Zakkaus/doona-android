package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaConfirmDialog
import dev.doona.android.designsystem.components.DoonaConfirmKind
import dev.doona.android.designsystem.components.DoonaConfirmPanel
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the confirmation dialog does and shows: both buttons answer, the title is a heading, and a removal is in the destructive fill. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class DialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theButtonsAnswerAndTheTitleIsAHeading() {
        var confirmed = 0
        var dismissed = 0
        compose.setDoonaContent {
            DoonaConfirmDialog("Remove it?", "Its nodes go too.", "Yes, remove", "Keep it", onConfirm = { confirmed++ }, onDismiss = { dismissed++ }, kind = DoonaConfirmKind.Destructive)
        }
        compose.onNodeWithText("Remove it?").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Its nodes go too.").assertExists()
        compose.onNodeWithText("Yes, remove").performClick()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(1, dismissed)
        assertEquals("a dismiss is not a confirm", 1, confirmed)
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    @Test
    fun aRemovalIsFilledDestructiveAndAChoiceIsFilledAccent() {
        val c = DoonaColors(Dawn)
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(DoonaTheme.colors.window)) {
                DoonaConfirmPanel("A", "a", "Choose", "No", {}, {}, DoonaConfirmKind.Default)
                DoonaConfirmPanel("B", "b", "Remove", "No", {}, {}, DoonaConfirmKind.Destructive)
            }
        }
        val image = compose.onRoot().captureToImage().toPixelMap()
        val density = compose.density.density
        fun fill(label: String): Color {
            val button = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            return image[(button.left + FILL_INSET * density).toInt(), button.center.y.toInt()]
        }
        assertEquals(hex(c.accent), hex(fill("Choose")))
        assertEquals(hex(c.destructive), hex(fill("Remove")))
    }

    private companion object {
        /** Inside a primary button's round end, where the fill is. */
        const val FILL_INSET = 6f
    }
}
