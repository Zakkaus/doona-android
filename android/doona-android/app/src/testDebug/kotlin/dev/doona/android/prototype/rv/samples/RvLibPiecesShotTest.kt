package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedSchemes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library pieces lifted from the pages (2026-11-27), whole pages: `proto-rv-samples-lib-<piece>-<light|dark|fs200>`,
 * one per [RvLibPiece], each beside the page crop it came from in the review panels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLibPiecesShotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(piece: RvLibPiece, dark: Boolean, fontScale: Float = 1f) =
        compose.rvShot("samples-lib-${piece.name.lowercase()}", dark = dark, before = { assertWhole() }, fontScale = fontScale) { RvLibPiecesPage(piece, seedSchemes()) }

    @Test
    fun meterLight() = shot(RvLibPiece.Meter, dark = false)

    @Test
    fun meterDark() = shot(RvLibPiece.Meter, dark = true)

    @Test
    fun meterFs200() = shot(RvLibPiece.Meter, dark = false, fontScale = RV_FS200)

    @Test
    fun ringLight() = shot(RvLibPiece.Ring, dark = false)

    @Test
    fun ringDark() = shot(RvLibPiece.Ring, dark = true)

    @Test
    fun ringFs200() = shot(RvLibPiece.Ring, dark = false, fontScale = RV_FS200)

    @Test
    fun barsLight() = shot(RvLibPiece.Bars, dark = false)

    @Test
    fun barsDark() = shot(RvLibPiece.Bars, dark = true)

    @Test
    fun barsFs200() = shot(RvLibPiece.Bars, dark = false, fontScale = RV_FS200)

    @Test
    fun roundButtonLight() = shot(RvLibPiece.RoundButton, dark = false)

    @Test
    fun roundButtonDark() = shot(RvLibPiece.RoundButton, dark = true)

    @Test
    fun roundButtonFs200() = shot(RvLibPiece.RoundButton, dark = false, fontScale = RV_FS200)

    @Test
    fun pictureLight() = shot(RvLibPiece.Picture, dark = false)

    @Test
    fun pictureDark() = shot(RvLibPiece.Picture, dark = true)

    @Test
    fun pictureFs200() = shot(RvLibPiece.Picture, dark = false, fontScale = RV_FS200)

    @Test
    fun colorLight() = shot(RvLibPiece.Color, dark = false)

    @Test
    fun colorDark() = shot(RvLibPiece.Color, dark = true)

    @Test
    fun colorFs200() = shot(RvLibPiece.Color, dark = false, fontScale = RV_FS200)

    @Test
    fun frameLight() = shot(RvLibPiece.Frame, dark = false)

    @Test
    fun frameDark() = shot(RvLibPiece.Frame, dark = true)

    @Test
    fun frameFs200() = shot(RvLibPiece.Frame, dark = false, fontScale = RV_FS200)
}
