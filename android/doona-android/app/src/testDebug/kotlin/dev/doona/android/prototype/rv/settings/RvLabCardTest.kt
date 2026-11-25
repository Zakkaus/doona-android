package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvFootnote
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvRowRead
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvRowNodes
import dev.doona.android.prototype.rv.rvRowsOf
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 實驗室's card (the samples branch's `RvLabScreen` at 110bcc47, rebuilt here from the same library parts: the large
 * title, one [RvSettingsSwitchRow] in a settings card, a footnote), for the owner's 19:06 note that its text sits tighter
 * in its card than other settings cards'. The row is the library's switch row with no padding of the page's, so its
 * padding is the row rule's (LAYOUT-RULES.md R18): a subtitle of two lines makes the row its text plus 16 dp above and
 * below, as any settings switch row with a two-line subtitle; a one-line subtitle stands in the 82 dp minimum, 19.25 dp
 * from either edge. Goldens `proto-rv-lab-{light,dark,fs200}` show the page for the panels.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLabCardTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun labLight() = compose.rvShot("lab", dark = false) { Lab() }

    @Test
    fun labDark() = compose.rvShot("lab", dark = true) { Lab() }

    @Test
    fun labFs200() = compose.rvShot("lab", dark = false, fontScale = RV_FS200) { Lab() }

    @Test
    fun theLabRowKeepsTheSwitchRowsPaddingOfItsSubtitlesLines() {
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                Column(Modifier.padding(horizontal = RvSettingsSpace.margin)) {
                    RvSettingsCard { RvSettingsSwitchRow(LAB, R.drawable.rv_sym_speed, true, {}, LAB_SUB) }
                    RvSettingsCard { RvSettingsSwitchRow(OTHER, R.drawable.rv_sym_rule, true, {}, OTHER_SUB) }
                    RvSettingsCard { RvSettingsSwitchRow(SHORT, R.drawable.rv_sym_rule, true, {}, SHORT_SUB) }
                }
            }
        }
        compose.waitForIdle()
        val rows = rvRowsOf(compose.rvRowNodes()).associateBy { it.title.text }
        val lab = rows.getValue(LAB)
        val other = rows.getValue(OTHER)
        assertEquals("both subtitles take two lines", listOf(2, 2), listOf(lab, other).map { it.subtitles.single().line?.lines })
        assertEquals("the lab row's padding above, as another two-line switch row's", other.above(), lab.above(), HALF)
        assertEquals("and below", other.below(), lab.below(), HALF)
        assertEquals("R18: 16 dp above a text taller than the minimum", RvSpace.rowTextInset.value, lab.above(), HALF)
        val short = rows.getValue(SHORT)
        assertEquals("a one-line subtitle centred in the 82 dp minimum", (82f - (short.textBottom - short.textTop)) / 2, short.above(), HALF)
    }

    private fun RvRowRead.above() = textTop - row.top

    private fun RvRowRead.below() = row.bottom - textBottom

    @Composable
    private fun Lab() {
        val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
        RvScrollPage(LAB_TITLE, insets, onBack = {}) { titleBlock ->
            RvLargeTitle(LAB_TITLE, titleBlock, insets)
            Column(Modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin)) {
                RvSettingsCard { RvSettingsSwitchRow(LAB, R.drawable.rv_sym_speed, true, {}, LAB_SUB) }
                RvFootnote(LAB_NOTE, inset = RvSpace.gutter - RvSettingsSpace.margin)
            }
        }
    }

    private companion object {
        const val LAB_TITLE = "實驗室"
        const val LAB = "效能模式"
        const val LAB_SUB = "用較多 CPU 與電量換取速度；下次開啟 App 生效"
        const val LAB_NOTE = "這些選項可能變動或移除。"
        const val OTHER = "允許區域網路連線"
        const val OTHER_SUB = "讓同一個 Wi-Fi 下的電腦經過這支手機連線"
        const val SHORT = "連線中斷時通知"
        const val SHORT_SUB = "顯示一則通知"
        const val HALF = 0.5f
    }
}
