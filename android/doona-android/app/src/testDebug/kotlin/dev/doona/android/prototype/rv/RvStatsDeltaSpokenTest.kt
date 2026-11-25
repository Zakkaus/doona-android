package dev.doona.android.prototype.rv

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.stats.RvStatsDelta
import dev.doona.android.feature.stats.RvStatsRange
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A stats change is spoken with its direction: the triangle's way and the colour are for the eye. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvStatsDeltaSpokenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun spoken(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(text))

    @Test
    fun theDirectionIsSpoken() {
        compose.setDoonaContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvStatsDelta(true, "12%", RvStatsRange.Today.against!!, Rv.colors.positive)
                RvStatsDelta(false, "8%", RvStatsRange.Today.against!!, Rv.colors.positive)
                RvStatsDelta(true, "6%", RvStatsRange.Week.against!!, Rv.colors.positive)
            }
        }
        compose.onAllNodes(spoken("較昨天同時段增加 12%")).assertCountEquals(1)
        compose.onAllNodes(spoken("較昨天同時段減少 8%")).assertCountEquals(1)
        // 本週's line names the week before, and so does what is spoken.
        compose.onAllNodes(spoken("較上週同時段增加 6%")).assertCountEquals(1)
    }
}
