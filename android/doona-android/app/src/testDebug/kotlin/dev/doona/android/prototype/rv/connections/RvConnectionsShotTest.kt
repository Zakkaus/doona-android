package dev.doona.android.prototype.rv.connections

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAppDisc
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 連線 tab with four tabs in the bar (`proto-rv-connections-*`), YouTube's connection page
 * (`proto-rv-connection-*`, also fs115 and fs200) and Telegram's, which has no domain (`proto-rv-connection-ip-*`); the
 * tab searching for `google` (`proto-rv-connections-search-*`) and with the tunnel off (`proto-rv-connections-off-*`),
 * light and dark, each in one capture of the whole page; that a tap or a long press on a row opens its page; and the
 * library's app disc (`proto-rv-app-disc-*`): an installed app's icon (the
 * test's own package, given the duck as its icon) over an app the phone does not have. The sample apps are not installed under Robolectric, so the pages show the
 * fallback glyph; on a phone each row shows the app's own icon.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvConnectionsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_LIST)
    fun listLight() = compose.rvShot("connections", dark = false, before = { assertWhole() }) { List() }

    @Test
    @Config(qualifiers = WHOLE_LIST)
    fun listDark() = compose.rvShot("connections", dark = true, before = { assertWhole() }) { List() }

    @Test
    @Config(qualifiers = WHOLE_WRAPPED)
    fun detailLight() = compose.rvShot("connection", dark = false, before = { assertWhole() }) { Detail(YOUTUBE) }

    @Test
    @Config(qualifiers = WHOLE_WRAPPED)
    fun detailDark() = compose.rvShot("connection", dark = true, before = { assertWhole() }) { Detail(YOUTUBE) }

    @Test
    @Config(qualifiers = WHOLE_WRAPPED_FS115)
    fun detailFs115() = compose.rvShot("connection", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Detail(YOUTUBE) }

    @Test
    @Config(qualifiers = WHOLE_WRAPPED_FS200)
    fun detailFs200() = compose.rvShot("connection", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Detail(YOUTUBE) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun ipLight() = compose.rvShot("connection-ip", dark = false, before = { assertWhole() }) { Detail(TELEGRAM) }

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun ipDark() = compose.rvShot("connection-ip", dark = true, before = { assertWhole() }) { Detail(TELEGRAM) }

    @Test
    fun searchLight() = compose.rvShot("connections-search", dark = false, before = { assertWhole() }) { RvConnectionsPage(RvPolicyData.connections, query = "google") }

    @Test
    fun searchDark() = compose.rvShot("connections-search", dark = true, before = { assertWhole() }) { RvConnectionsPage(RvPolicyData.connections, query = "google") }

    @Test
    fun offLight() = compose.rvShot("connections-off", dark = false, before = { assertWhole() }) { RvConnectionsPage(RvPolicyData.connections, connected = false) }

    @Test
    fun offDark() = compose.rvShot("connections-off", dark = true, before = { assertWhole() }) { RvConnectionsPage(RvPolicyData.connections, connected = false) }

    @Test
    fun appDiscLight() {
        installIcon()
        compose.rvShot("app-disc", dark = false) { AppDiscs() }
    }

    @Test
    fun appDiscDark() {
        installIcon()
        compose.rvShot("app-disc", dark = true) { AppDiscs() }
    }

    @Test
    fun aTapOrALongPressOpensTheConnection() {
        val opened = mutableListOf<Int>()
        compose.setContent { Themed { RvConnectionsPage(RvPolicyData.connections, RvConnectionsRoutes(open = { opened += it })) } }
        val youtube = RvPolicyData.connections[YOUTUBE]
        val row = compose.onNodeWithText(youtube.host + PORT + youtube.port, substring = true)
        row.performClick()
        row.performTouchInput { longClick(durationMillis = LONG_PRESS_MS) }
        assertEquals(listOf(YOUTUBE, YOUTUBE), opened)
    }

    /** Gives the test's own package a launcher-style icon (the duck on white), as a phone's installed app has one. */
    private fun installIcon() {
        val context = RuntimeEnvironment.getApplication()
        val duck = InsetDrawable(context.getDrawable(R.drawable.ic_duck), ICON_INSET)
        shadowOf(context.packageManager).setApplicationIcon(DOONA, LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), duck)))
    }

    @Composable
    private fun List() = RvConnectionsPage(RvPolicyData.connections)

    @Composable
    private fun Detail(i: Int) = RvConnectionDetail(RvPolicyData.connections[i], onBack = {}, onClose = {}, onNewRule = {})

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    @Composable
    private fun AppDiscs() {
        Column(Modifier.fillMaxSize().background(Rv.colors.page).windowInsetsPadding(LocalFrameInsets.current ?: WindowInsets.systemBars).padding(top = RvSpace.gutter)) {
            RvGutter {
                RvCard {
                    RvRow({ RvAppDisc(DOONA) }, "doona", DOONA)
                    RvRow({ RvAppDisc(ABSENT) }, "Telegram", ABSENT)
                }
            }
        }
    }

    private companion object {
        const val YOUTUBE = 3
        const val TELEGRAM = 4
        const val DOONA = "dev.doona.android"
        const val ICON_INSET = 0.2f
        const val ABSENT = "org.telegram.messenger"

        /** Windows as tall as each whole page, so one capture holds it to its end. */
        const val WHOLE_LIST = "+h958dp"
        const val WHOLE_DETAIL = "+h1153dp"

        /** YouTube's page, whose host wraps after a dot at the larger font sizes. */
        const val WHOLE_WRAPPED = "+h1133dp"
        const val WHOLE_WRAPPED_FS115 = "+h1140dp"
        const val WHOLE_WRAPPED_FS200 = "+h1306dp"

        /** Past the menus' 500 ms ([RvMotion.LONG_PRESS_MS]), which the page's rows take. */
        const val LONG_PRESS_MS = 700L
    }
}
