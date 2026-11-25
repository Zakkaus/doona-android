package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The footnote check itself ([rvFootnoteRules], run on every golden): [RvFootnote] under a card stands where
 * [RvSpace.footnoteStart] and [RvSpace.footnoteEnd] put it, in a page's gutter and in a settings column that insets
 * the cards itself; a footnote-tagged text set on the gutter (16 dp) is caught.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvFootnoteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aFootnoteUnderItsCardKeepsToTheTokens() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    RvFootnote(NOTE)
                    Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
                        RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) }
                        RvFootnote(NOTE, inset = RvSpace.none)
                    }
                }
            }
        }
        compose.waitForIdle()
        val read = rvFootnotes(compose.rvRowNodes())
        assertEquals("both footnotes read", 2, read.size)
        read.forEach { assertTrue("${it.line("test")} keeps to the tokens", !it.off) }
    }

    @Test
    fun aFootnoteOnTheGutterIsCaught() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column {
                    RvGutter { RvCard { RvText(CARD, Rv.type.rowTitle, Modifier.padding(RvSpace.rowPadding)) } }
                    // A page-local footnote on the gutter, as pages drew them before RvFootnote: 16 dp in, to 16 dp from the edge.
                    RvText(NOTE, Rv.type.rowSub, Modifier.padding(horizontal = RvSpace.gutter).fillMaxWidth().testTag(RvPart.FOOTER), color = Rv.colors.textSecondary)
                }
            }
        }
        compose.waitForIdle()
        assertTrue("the gutter footnote is off", rvFootnotes(compose.rvRowNodes()).single().off)
    }

    private companion object {
        const val CARD = "卡片"
        const val NOTE = "這些選項可能變動或移除。"
    }
}
