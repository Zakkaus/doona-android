package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCheckRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.gallery.RvGalleryChecks
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's check row (`proto-rv-check-row`), as the gallery draws it: three apps in a card, the first and the last
 * checked; light, dark and fs200. Then what it does: each row is one checkbox to a screen reader, as tall as a row, and
 * a tap on it checks or unchecks it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvCheckRowShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+h420dp")
    fun checkRowLight() = compose.rvShot("check-row", dark = false) { Page { RvGalleryChecks() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun checkRowDark() = compose.rvShot("check-row", dark = true) { Page { RvGalleryChecks() } }

    @Test
    @Config(qualifiers = "+h640dp")
    fun checkRowFs200() = compose.rvShot("check-row", dark = false, fontScale = RV_FS200) { Page { RvGalleryChecks() } }

    @Test
    fun aTapChecksOrUnchecksTheRow() {
        compose.setContent { Themed { Column { RvGalleryChecks() } } }
        compose.onNode(hasText(BROWSER) and checkbox(ToggleableState.On)).assertExists()
        compose.onNode(hasText(CALENDAR) and checkbox(ToggleableState.Off)).assertHeightIsAtLeast(RvSize.rowTwoLine).performClick()
        compose.onNode(hasText(CALENDAR) and checkbox(ToggleableState.On)).assertExists()
        compose.onNodeWithText(BROWSER).performClick()
        compose.onNode(hasText(BROWSER) and checkbox(ToggleableState.Off)).assertExists()
    }

    /**
     * A row not enabled is the toggle's own: a screen reader hears it disabled and a tap reaches nothing, with no guard in
     * the caller's callback (so no press layer either; Robolectric draws none to compare).
     */
    @Test
    fun aRowNotEnabledTakesNoTap() {
        val changed = mutableListOf<Boolean>()
        compose.setContent { Themed { Page { RvCard { RvCheckRow({ RvIconDisc(R.drawable.rv_sym_apps) }, CALENDAR, null, false, { changed += it }, enabled = false) } } } }
        compose.onNode(hasText(CALENDAR) and checkbox(ToggleableState.Off)).assertIsNotEnabled().performClick()
        compose.onNode(hasText(CALENDAR) and checkbox(ToggleableState.Off)).assertExists()
        assertEquals("taps that reached the row", emptyList<Boolean>(), changed)
    }

    private fun checkbox(state: ToggleableState) =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, state)

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    /** The page's colour, the card on the gutter under the status bar, as the library's other part goldens. */
    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP)) { RvGutter { content() } }
    }

    private companion object {
        val TOP = 72.dp
        const val BROWSER = "Browser"
        const val CALENDAR = "Calendar"
    }
}
