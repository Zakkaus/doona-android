package dev.doona.android.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.Landscape
import dev.doona.android.testing.SeedTheme
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.screenCases
import dev.doona.android.testing.sheetScroll
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TOL = 0.5f

/**
 * A phone turned on its side leaves a sheet about 220 dp high between the bars. Whatever the screen holds must still
 * be reachable: scrolled to the end, the sheet's last block and its last action lie in view above the hub bar, with the
 * sheet's bottom padding.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w915dp-h412dp-notnight-xhdpi")
class ReachabilityTest(private val name: String) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theLastActionScrollsIntoView() {
        RuntimeEnvironment.setQualifiers(Landscape.qualifiers)
        val case = screenCases().first { it.name == name }
        compose.setContent { CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { SeedTheme { case.content() } } }
        compose.onNode(sheetScroll()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        compose.waitForIdle()

        val tree = compose.layoutTree()
        val sheet = tree.descendants().first { it.role == LayoutRole.Sheet }
        val viewport = tree.viewportOf(sheet)
        val last = sheet.descendants().filter { it.actionable }.maxByOrNull { it.bottom } ?: sheet.content.last()
        assertTrue("$name: the sheet's end is out of view, its bottom ${sheet.bottom} under the viewport's ${viewport.bottom}", sheet.bottom <= viewport.bottom + TOL)
        assertTrue("$name: the last action $last is not in view, in ${viewport.top}..${viewport.bottom}", last.top >= viewport.top - TOL && last.bottom <= viewport.bottom + TOL)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun parameters(): List<String> = screenCases().map { it.name }
    }
}
