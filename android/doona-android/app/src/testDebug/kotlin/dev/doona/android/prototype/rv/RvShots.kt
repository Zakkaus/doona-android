package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.drawFully
import org.junit.Assert.assertEquals
import org.junit.rules.ExternalResource
import org.robolectric.RuntimeEnvironment
import java.util.TimeZone
import kotlin.math.roundToInt

private val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

/**
 * Draws [content] in the scheme of the owner's wallpaper, light or [dark], runs [before] (a scroll, an assertion) and
 * records the golden `proto-rv-<screen>-light` or `-dark`. At a [fontScale] other than 1 the page's text scales as the
 * phone's font size setting scales it (the frame's status bar does not), and the golden is
 * `proto-rv-<screen>-fs<percent>`, as the app's `fs115` and `fs200`.
 */
internal fun ComposeContentTestRule.rvShot(
    screen: String,
    dark: Boolean,
    before: ComposeContentTestRule.() -> Unit = {},
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    RuntimeEnvironment.setQualifiers(if (dark) "+night" else "+notnight")
    setContent {
        DoonaTheme(dark) {
            PhoneFrame {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    RvTheme(seedColorScheme(Seed.Cyan.argb, dark), dark, content)
                }
            }
        }
    }
    before()
    drawFully()
    val variant = when {
        fontScale != 1f -> "fs${(fontScale * PERCENT).roundToInt()}"
        dark -> "dark"
        else -> "light"
    }
    onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/proto-rv-$screen-$variant.png")
}

private const val PERCENT = 100

/** Android's two font sizes beyond the default that the goldens cover. */
internal const val RV_FS115 = 1.15f
internal const val RV_FS200 = 2f

/** Scrolls the page's content up by [dp], as a finger would, and lets what follows the scroll (the nav's collapse) settle. */
internal fun ComposeContentTestRule.rvScrollBy(dp: Float) {
    onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dp * density.density) }
    waitForIdle()
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
