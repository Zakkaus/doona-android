package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvSourceField
import dev.doona.android.designsystem.rv.RvSourceMark
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvBlankColumns
import dev.doona.android.designsystem.rv.rvUtf16Index
import dev.doona.android.prototype.rv.gallery.RV_GALLERY_SOURCE
import dev.doona.android.prototype.rv.gallery.RvGalleryCode
import dev.doona.android.prototype.rv.gallery.RvGalleryDiff
import dev.doona.android.prototype.rv.gallery.RvGallerySource
import dev.doona.android.prototype.rv.gallery.rvByteOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library parts the configuration pages need (PV-f item 1, round 2 item 3), each on the page under the status bar
 * in light, dark and the two larger font sizes: the source field with a mark after a multibyte word
 * (`proto-rv-source-field-*`), the diff of that file after the fix (`proto-rv-source-diff-*`), and code text with every
 * role doona colours (`proto-rv-code-text-*`); then what each does.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSourceShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+h420dp")
    fun fieldLight() = compose.rvShot("source-field", dark = false) { Page { RvGallerySource() } }

    @Test
    @Config(qualifiers = "+h420dp")
    fun fieldDark() = compose.rvShot("source-field", dark = true) { Page { RvGallerySource() } }

    @Test
    @Config(qualifiers = "+h480dp")
    fun fieldFs115() = compose.rvShot("source-field", dark = false, fontScale = RV_FS115) { Page { RvGallerySource() } }

    @Test
    @Config(qualifiers = "+h720dp")
    fun fieldFs200() = compose.rvShot("source-field", dark = false, fontScale = RV_FS200) { Page { RvGallerySource() } }

    @Test
    @Config(qualifiers = "+h480dp")
    fun diffLight() = compose.rvShot("source-diff", dark = false) { Page { RvGalleryDiff() } }

    @Test
    @Config(qualifiers = "+h480dp")
    fun diffDark() = compose.rvShot("source-diff", dark = true) { Page { RvGalleryDiff() } }

    @Test
    @Config(qualifiers = "+h560dp")
    fun diffFs115() = compose.rvShot("source-diff", dark = false, fontScale = RV_FS115) { Page { RvGalleryDiff() } }

    @Test
    @Config(qualifiers = "+h915dp")
    fun diffFs200() = compose.rvShot("source-diff", dark = false, fontScale = RV_FS200) { Page { RvGalleryDiff() } }

    @Test
    @Config(qualifiers = "+h440dp")
    fun codeLight() = compose.rvShot("code-text", dark = false) { Page { RvGalleryCode() } }

    @Test
    @Config(qualifiers = "+h440dp")
    fun codeDark() = compose.rvShot("code-text", dark = true) { Page { RvGalleryCode() } }

    @Test
    @Config(qualifiers = "+h520dp")
    fun codeFs115() = compose.rvShot("code-text", dark = false, fontScale = RV_FS115) { Page { RvGalleryCode() } }

    @Test
    @Config(qualifiers = "+h915dp")
    fun codeFs200() = compose.rvShot("code-text", dark = false, fontScale = RV_FS200) { Page { RvGalleryCode() } }

    @Test
    fun aByteOffsetLandsOnTheCharacterItCounts() {
        val text = "a 影音 b"
        // a, space: 2 bytes; 影 and 音: 3 bytes each; space: 1; so b is byte 9 and UTF-16 index 5.
        assertEquals(5, rvUtf16Index(text, 9))
        assertEquals(2, rvUtf16Index(text, 3))
        assertEquals(text.length, rvUtf16Index(text, 99))
        // A character outside the BMP: four bytes, two UTF-16 units.
        assertEquals(2, rvUtf16Index("😀x", 4))
    }

    @Test
    fun aLinesIndentCountsItsLeadingBlanks() {
        assertEquals(0, rvBlankColumns("routing {"))
        assertEquals(2, rvBlankColumns("  domain(geosite:cn) -> direct"))
        assertEquals(7, rvBlankColumns(" \t  x"))
        assertEquals(3, rvBlankColumns("   \n"))
        assertEquals(0, rvBlankColumns(""))
    }

    @Test
    fun tappingAMarksMessagePutsTheCursorOnItsSpan() {
        val start = rvByteOf(RV_GALLERY_SOURCE, WORD)
        var value by mutableStateOf(TextFieldValue(RV_GALLERY_SOURCE))
        compose.setContent { Themed { RvSourceField(value, { value = it }, FILE, marks = listOf(RvSourceMark(start, start + WORD.length, MESSAGE))) } }
        compose.onNodeWithText("第 3 行：$MESSAGE").performClick()
        val at = RV_GALLERY_SOURCE.indexOf(WORD)
        assertEquals(TextRange(at, at + WORD.length), value.selection)
    }

    @Test
    fun aScreenReaderHearsEachChangedLineAsAddedOrRemoved() {
        compose.setContent { Themed { RvGalleryDiff() } }
        compose.onNodeWithContentDescription("新增，第 12 行：  domain(suffix: radio.example) -> media").assertExists()
        compose.onNodeWithContentDescription("移除，第 3 行：  domain(keyword: 影音) -> stream").assertExists()
    }

    @Test
    fun tappingALineOpensItsFileThere() {
        var opened: RvDiffAt? = null
        compose.setContent { Themed { RvGalleryDiff(onOpen = { opened = it }) } }
        compose.onNodeWithContentDescription("新增，第 12 行：  domain(suffix: radio.example) -> media").performClick()
        assertEquals(RvDiffAt(12, base = false), opened)
        compose.onNodeWithContentDescription("移除，第 3 行：  domain(keyword: 影音) -> stream").performClick()
        assertEquals(RvDiffAt(3, base = true), opened)
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    /** The page's colour, the part on the gutter under the status bar. */
    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).padding(top = TOP)) { RvGutter { content() } }
    }

    private companion object {
        val TOP = 72.dp
        const val WORD = "stream"
        const val FILE = "rules/media.dae"
        const val MESSAGE = "找不到策略組 stream"
    }
}
