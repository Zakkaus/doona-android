package dev.doona.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.checkRoboAccessibility
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.GalleryVariants
import dev.doona.android.testing.PhoneFrame
import dev.doona.android.testing.ScreenCase
import dev.doona.android.testing.ScreenVariants
import dev.doona.android.testing.Variant
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.galleryCases
import dev.doona.android.testing.screenCases
import dev.doona.android.testing.showsContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every screen in every [Variant], and every component gallery page at the phone's variants: draws it inside a phone frame, compares or records the golden in
 * `src/test/screenshots/<screen>-<variant>.png` (`-Ddoona.shots=<dir>` records elsewhere, for looking), and runs
 * Google's accessibility checks on the same render. The content area of the capture, without the frame's bars, must show
 * ink: the frame draws a clock and a handle even when the page is empty.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class ScreenshotTest(private val name: String, private val variant: Variant) {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "src/test/screenshots"

    @Test
    fun matchesGoldenAndPassesAccessibilityChecks() {
        RuntimeEnvironment.setQualifiers(variant.qualifiers)
        val case = (screenCases() + galleryCases()).first { it.name == name }
        compose.setContent { Framed(case, variant) }
        compose.drawFully()
        // One capture is checked, recorded and compared, so what is checked is what the golden holds.
        val image = compose.onRoot().captureToImage()
        assertTrue("$name-${variant.name}: the content area shows nothing but fills", image.showsContent(compose.density.density))
        image.asAndroidBitmap().captureRoboImage("$dir/$name-${variant.name}.png")
        compose.onRoot().checkRoboAccessibility()
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> =
            screenCases().flatMap { case -> ScreenVariants.map { arrayOf(case.name, it) } } +
                galleryCases().flatMap { case -> GalleryVariants.map { arrayOf(case.name, it) } }
    }
}

@Composable
private fun Framed(case: ScreenCase, variant: Variant) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
        DoonaTheme { PhoneFrame(case.content) }
    }
}
