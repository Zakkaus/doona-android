package dev.doona.android.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** An app's disc reads its icon off the main thread: with none to read, the disc is the placeholder and keeps its name. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvAppDiscTest {
    @get:Rule
    val compose = createComposeRule()

    private fun themed(content: @Composable () -> Unit) = compose.setDoonaContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { content() } }

    @Test
    fun anUninstalledAppKeepsItsPlaceholderAndItsName() {
        themed { RvAppDisc("not.installed.app", "Some app") }
        compose.waitForIdle()
        compose.onAllNodesWithTag(RvRowPart.APP_DISC, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithContentDescription("Some app").assertExists()
    }
}
