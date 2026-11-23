package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.drawFully
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone

private val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

/**
 * Draws [content] in the scheme of the owner's wallpaper, light or [dark], runs [before] (a scroll, an assertion) and
 * records the golden `proto-rv-<screen>-light` or `-dark`.
 */
internal fun ComposeContentTestRule.rvShot(
    screen: String,
    dark: Boolean,
    before: ComposeContentTestRule.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
    setContent {
        DoonaTheme(dark) {
            PhoneFrame {
                RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
            }
        }
    }
    before()
    drawFully()
    onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/proto-rv-$screen-${if (dark) "dark" else "light"}.png")
}

/** A full capture holds the whole page: its window is as tall as the page, so nothing is left to scroll. */
internal fun ComposeContentTestRule.assertWhole() {
    val page = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNode()
    val left = page.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() / density.density
    assertEquals("the page outgrew its window by $left dp", 0f, left)
}

/** The pages print times in the device's zone; UTC keeps the goldens the same on every machine. */
class Utc : ExternalResource() {
    private val zone = TimeZone.getDefault()

    override fun before() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    override fun after() = TimeZone.setDefault(zone)
}
