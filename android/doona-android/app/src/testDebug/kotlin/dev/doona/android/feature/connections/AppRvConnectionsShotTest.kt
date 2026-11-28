package dev.doona.android.feature.connections

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RV_NOW
import dev.doona.android.prototype.rv.rvShot
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 連線 and a connection's page as the app draws them: the RV sample's connections as honk serves them
 * (`honk/connections/connections-sample.json`, with the sample's groups and nodes) through [ConnectionsViewModel], at the
 * sample's 10:24 UTC. Goldens `app-rv-connections-*` and `app-rv-connection-*` beside the prototype's `proto-rv-*` of
 * the same screens: the list light, dark, fs200 and English, searching for `google`, the tunnel off, YouTube's page and
 * Telegram's, which has no domain.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvConnectionsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val model = ConnectionsViewModel(
        HonkFixtures.rvSample().apply { on("GET", LIST_PATH, 200, HonkFixtures.body("connections/connections-sample")) },
        CoreNodePlaces,
        { RV_NOW },
        io = Dispatchers.Unconfined,
    )

    @Test
    @Config(qualifiers = WHOLE_LIST)
    fun listLight() = list("connections", dark = false, whole = true)

    @Test
    @Config(qualifiers = WHOLE_LIST)
    fun listDark() = list("connections", dark = true, whole = true)

    @Test
    fun listFs200() = list("connections", fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun listEn() = list("connections-en")

    @Test
    fun searchLight() = list("connections-search", query = "google", whole = true)

    @Test
    fun offLight() = list("connections-off", connected = false, whole = true)

    @Test
    @Config(qualifiers = WHOLE_WRAPPED)
    fun detailLight() = detail("connection", YOUTUBE)

    @Test
    @Config(qualifiers = WHOLE_WRAPPED)
    fun detailDark() = detail("connection", YOUTUBE, dark = true)

    @Test
    @Config(qualifiers = WHOLE_WRAPPED_FS200)
    fun detailFs200() = detail("connection", YOUTUBE, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE_DETAIL)
    fun ipLight() = detail("connection-ip", TELEGRAM)

    private fun list(screen: String, dark: Boolean = false, fontScale: Float = 1f, query: String? = null, connected: Boolean = true, whole: Boolean = false) {
        if (connected) model.load()
        compose.rvShot(screen, dark, before = { if (whole) assertWhole() }, fontScale = fontScale, prefix = APP) {
            val ui by model.ui.collectAsState()
            RvConnectionsPage(ui.connections, query = query, connected = connected, nowEpochMs = ui.nowEpochMs, loaded = ui.loaded)
        }
    }

    private fun detail(screen: String, id: String, dark: Boolean = false, fontScale: Float = 1f) {
        model.load()
        compose.rvShot(screen, dark, before = { assertWhole() }, fontScale = fontScale, prefix = APP) {
            ConnectionRoute(model, id, onBack = {}, onNewRule = {})
        }
    }

    private companion object {
        const val APP = "app-rv"
        const val LIST_PATH = "/api/v1/connections?limit=1000&detail=full"
        const val YOUTUBE = "udp-sample-04"
        const val TELEGRAM = "tcp-sample-05"

        /** The prototype's windows, as tall as each whole page. */
        const val WHOLE_LIST = "+h958dp"
        const val WHOLE_DETAIL = "+h1167dp"
        const val WHOLE_WRAPPED = "+h1147dp"
        const val WHOLE_WRAPPED_FS200 = "+h1455dp"
    }
}
