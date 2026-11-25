package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sample of emoji1, whole pages: `proto-rv-samples-emoji-<today|proposed>-<light|dark|fs200>`, and the English
 * ones as `-en-light`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvEmojiNamesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = "+h1312dp")
    fun todayLight() = compose.rvShot("samples-emoji-today", dark = false, before = { assertWhole() }) { RvEmojiNamesPage(false) }

    @Test
    @Config(qualifiers = "+h1312dp")
    fun proposedLight() = compose.rvShot("samples-emoji-proposed", dark = false, before = { assertWhole() }) { RvEmojiNamesPage(true) }

    @Test
    @Config(qualifiers = "+h1312dp")
    fun todayDark() = compose.rvShot("samples-emoji-today", dark = true, before = { assertWhole() }) { RvEmojiNamesPage(false) }

    @Test
    @Config(qualifiers = "+h1312dp")
    fun proposedDark() = compose.rvShot("samples-emoji-proposed", dark = true, before = { assertWhole() }) { RvEmojiNamesPage(true) }

    @Test
    @Config(qualifiers = "+h1743dp")
    fun todayFs200() = compose.rvShot("samples-emoji-today", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvEmojiNamesPage(false) }

    @Test
    @Config(qualifiers = "+h1666dp")
    fun proposedFs200() = compose.rvShot("samples-emoji-proposed", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvEmojiNamesPage(true) }

    @Test
    @Config(qualifiers = "en-w412dp-h1311dp-notnight-xhdpi")
    fun todayEn() = compose.rvShot("samples-emoji-today-en", dark = false, before = { assertWhole() }) { RvEmojiNamesPage(false) }

    @Test
    @Config(qualifiers = "en-w412dp-h1311dp-notnight-xhdpi")
    fun proposedEn() = compose.rvShot("samples-emoji-proposed-en", dark = false, before = { assertWhole() }) { RvEmojiNamesPage(true) }
}
