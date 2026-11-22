package dev.doona.android.designsystem

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.testing.RvGalleryVariants
import dev.doona.android.testing.RvSpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.rvGalleryCases
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each gallery page of the design system at font scale 1.0, in a window tall enough to show it whole, and
 * asserts [RvSpacingRules] on its layout against the [dev.doona.android.testing.GalleryLayout] the page states.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSpacingCheckTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun galleryPageKeepsTheSpacingRules() {
        val case = rvGalleryCases().first { it.name == name }
        compose.setVariantContent(RvGalleryVariants.first()) { case.content() }
        val violations = RvSpacingRules.check(compose.layoutTree(), case.layout)
        assertTrue("$name breaks the design system's spacing rules:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = rvGalleryCases().map { arrayOf(it.name) }
    }
}
