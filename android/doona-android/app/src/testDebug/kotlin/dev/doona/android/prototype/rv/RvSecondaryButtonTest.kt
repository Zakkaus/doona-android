package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The secondary pill floating over a page ([RvSecondaryButton]'s `over`), as 回報問題's 分享檔案 does: enabled or
 * disabled, nothing under it shows through, where the plain pill lets it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h200dp-notnight-xhdpi")
class RvSecondaryButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val under = mutableStateOf(Color.Red)
    private val enabled = mutableStateOf(true)
    private val floating = mutableStateOf(true)

    @Test
    fun aFloatingPillHidesWhatIsUnderIt() {
        show()
        for (on in listOf(true, false)) {
            enabled.value = on
            assertEquals("enabled = $on", fillOver(Color.Red), fillOver(Color.Blue))
        }
    }

    @Test
    fun aPlainPillLetsItThrough() {
        show()
        floating.value = false
        for (on in listOf(true, false)) {
            enabled.value = on
            assertNotEquals("enabled = $on", fillOver(Color.Red), fillOver(Color.Blue))
        }
    }

    private fun show() = compose.setContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            Box(Modifier.background(under.value).padding(RvSpace.gutter)) {
                val over = if (floating.value) Rv.colors.page else null
                RvSecondaryButton("分享檔案", {}, Modifier.testTag(PILL), enabled = enabled.value, over = over)
            }
        }
    }

    /** The pill's fill beside its label, on its middle line, a sixth of the way in, with [color] under it. */
    private fun fillOver(color: Color): Color {
        under.value = color
        val pixels = compose.onNodeWithTag(PILL).captureToImage().toPixelMap()
        return pixels[pixels.width / 6, pixels.height / 2]
    }

    private companion object {
        const val PILL = "pill"
    }
}
