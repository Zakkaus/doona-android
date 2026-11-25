package dev.doona.android.prototype.rv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.LocalRvUpdating
import dev.doona.android.feature.subscriptions.RvSubsActions
import dev.doona.android.feature.subscriptions.RvSubsPage
import dev.doona.android.feature.subscriptions.RvSubsState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.subs.rvSubsStateSample
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 訂閱 while one subscription updates (`proto-rv-subs-updating-*`): a busy bar before its row's chevron
 * ([LocalRvUpdating], as the node picker's subscription row shows it); light, dark, fs115 and fs200, at animator scale 0,
 * so the bar rests half full. The glass update-all button has no busy form and keeps its glyph.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSubsUpdatingShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun updatingLight() = shot(dark = false)

    @Test fun updatingDark() = shot(dark = true)

    @Test fun updatingFs115() = shot(dark = false, fontScale = RV_FS115)

    @Test fun updatingFs200() = shot(dark = false, fontScale = RV_FS200)

    private fun shot(dark: Boolean, fontScale: Float = 1f) {
        rvAnimatorScale(0f)
        compose.rvShot("subs-updating", dark, fontScale = fontScale) {
            CompositionLocalProvider(LocalRvUpdating provides setOf(RvData.subscriptions.first().id)) { RvSubsPage(rvSubsStateSample(), RvSubsActions()) }
        }
    }
}
