package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.SeedTheme
import dev.doona.android.testing.colours
import dev.doona.android.testing.contentInk
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.showsContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screenshot test's blank check looks at the content area of the capture. The phone frame draws a clock, indicators
 * and a gesture handle over every page, so an empty page in the frame still has several colours: counting them over the
 * whole capture, as the check once did, passes a page that shows nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class BlankContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val density = 3f

    private fun capture(content: @Composable () -> Unit): ImageBitmap {
        compose.setContent { SeedTheme { PhoneFrame(content) } }
        compose.drawFully()
        return compose.onRoot().captureToImage()
    }

    @Test
    fun anEmptyPageInThePhoneFrameShowsNoContent() {
        val image = capture { Box(Modifier.fillMaxSize().background(DoonaTheme.colors.page)) }
        assertTrue("the whole capture has ${image.colours()} colours: the frame's clock and handle", image.colours() > 2)
        assertFalse("content ink ${image.contentInk(density)}", image.showsContent(density))
    }

    @Test
    fun aPageWithATitleShowsContent() {
        val image = capture {
            Box(Modifier.fillMaxSize().background(DoonaTheme.colors.page).padding(top = 80.dp, start = 16.dp)) { DoonaPageTitle("Title") }
        }
        assertTrue("content ink ${image.contentInk(density)}", image.showsContent(density))
    }
}
