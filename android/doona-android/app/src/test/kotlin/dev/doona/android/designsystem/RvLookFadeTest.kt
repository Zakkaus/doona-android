package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvLookFade
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A look change holds the old frame as a picture and fades it out over the new one. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvLookFadeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun centre() = compose.onRoot().captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }

    // The picture of the old look comes from the render thread, outside the test clock, so a frame half way through the fade
    // cannot be sampled here: this holds that a change settles on the new look, shown plain with nothing left over it.
    @Test
    fun aChangeSettlesOnTheNewLook() {
        var look by mutableStateOf(Color.Red)
        compose.setContent { RvLookFade(look) { color -> Box(Modifier.fillMaxSize().background(color)) } }
        compose.waitForIdle()
        assertEquals(Color.Red, centre())
        look = Color.Blue
        compose.waitUntil(FADE_WAIT_MS) { centre() == Color.Blue }
        compose.waitForIdle()
        assertEquals(Color.Blue, centre())
    }

    private companion object {
        const val FADE_WAIT_MS = 10_000L
    }
}
