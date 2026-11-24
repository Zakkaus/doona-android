package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 節點 page with the nine nodes the 48 leave out ([RvData.worldNodes]), on Dubai 01, in one capture of the whole
 * page (`proto-rv-nodes-world-*`): their countries filled on the map, and the list under every part of the world,
 * 非洲 among them, each with its flag. Light, dark, fs115 and fs200.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvWorldNodesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun light() = compose.rvShot("nodes-world", dark = false, before = { assertWhole() }) { Page() }

    @Test
    @Config(qualifiers = WHOLE)
    fun dark() = compose.rvShot("nodes-world", dark = true, before = { assertWhole() }) { Page() }

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun fs115() = compose.rvShot("nodes-world", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Page() }

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun fs200() = compose.rvShot("nodes-world", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Page() }

    @Composable
    private fun Page() = RvNodesPage(RvNodesPageState(RvData.worldNodes, "ae-01", RvData.testTimes), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {})

    private companion object {
        /** Windows as tall as the whole page at each font scale, so one capture holds every card. */
        const val WHOLE = "+h2043dp"
        const val WHOLE_FS115 = "+h2100dp"
        const val WHOLE_FS200 = "+h3120dp"
    }
}
