package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RV_NAV_ACTIVITY
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What the bottom bar says to a screen reader, open and collapsed. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvNavSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val names = listOf("活動", "策略", "連線", "設定")

    private fun hasName(name: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name))

    @Test
    fun everyTabHasItsNameOpenAndCollapsed() {
        val scroll = androidx.compose.foundation.ScrollState(0)
        compose.setDoonaContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(scroll)) { Spacer(Modifier.height(3000.dp)) }
                    RvFloatingNav(RV_NAV_ACTIVITY, {}, rememberRvGlass(), scroll, WindowInsets(0))
                }
            }
        }
        compose.onAllNodes(isSelectable()).assertCountEquals(4)
        names.forEach { compose.onAllNodes(hasName(it)).assertCountEquals(1) }
        compose.runOnIdle { kotlinx.coroutines.runBlocking { scroll.scrollTo(2000) } }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        names.forEach { compose.onAllNodes(hasName(it)).assertCountEquals(1) }
        compose.onAllNodes(isSelectable()).assertCountEquals(4)
    }
}
