package dev.doona.android.prototype.rv.layout

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The layout catalogue's goldens `proto-rv-layout-<zh|en>-{light,dark,fs200}` (docs/LAYOUT-RULES.md; the sheets are made from them). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h3000dp-notnight-xhdpi")
class RvLayoutCatalogueShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun zhLight() = compose.rvShot("layout-zh", false) { RvLayoutCatalogue(RV_CATALOGUE_ZH) }

    @Test fun zhDark() = compose.rvShot("layout-zh", true) { RvLayoutCatalogue(RV_CATALOGUE_ZH) }

    @Test
    @Config(qualifiers = "+h5200dp")
    fun zhFs200() = compose.rvShot("layout-zh", false, fontScale = RV_FS200) { RvLayoutCatalogue(RV_CATALOGUE_ZH) }

    @Test
    @Config(qualifiers = "en-w412dp-h3000dp-notnight-xhdpi")
    fun enLight() = compose.rvShot("layout-en", false) { RvLayoutCatalogue(RV_CATALOGUE_EN) }

    @Test
    @Config(qualifiers = "en-w412dp-h5200dp-notnight-xhdpi")
    fun enFs200() = compose.rvShot("layout-en", false, fontScale = RV_FS200) { RvLayoutCatalogue(RV_CATALOGUE_EN) }
}
