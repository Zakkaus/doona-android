package dev.doona.android.designsystem

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a sheet's scrim says to a screen reader. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvSheetSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun clickLabelled(label: String) = SemanticsMatcher("click labelled $label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }

    @Test
    fun theScrimReadsAsDismissNotAsTheSheetsTitle() {
        val title = "刪除「香港 01」?"
        compose.setDoonaContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvSheetLayout({ 1f }, {}, title) {} }
        }
        compose.onAllNodes(clickLabelled("關閉")).assertCountEquals(1)
        compose.onAllNodes(clickLabelled(title)).assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
    }
}
