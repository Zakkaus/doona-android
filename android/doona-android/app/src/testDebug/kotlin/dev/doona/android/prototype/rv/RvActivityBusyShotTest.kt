package dev.doona.android.prototype.rv

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.engine.Status
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動 while it connects (`proto-rv-activity-starting-v1|v2|v3-*`) and while it disconnects (`-activity-stopping-v1|v2|v3-*`)
 * in the three looks of [RvBusyLook]: v1 the status chip, v2 the busy bar in the rates' place, v3 the map in the fair
 * tone; each light and dark. They run at animator scale 0, so every busy bar rests half full.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityBusyShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Before
    fun still() = rvAnimatorScale(0f)

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun startingV1Light() = shot(Status.Starting, RvBusyLook.Chip, dark = false)

    @Test fun startingV1Dark() = shot(Status.Starting, RvBusyLook.Chip, dark = true)

    @Test fun startingV2Light() = shot(Status.Starting, RvBusyLook.Bar, dark = false)

    @Test fun startingV2Dark() = shot(Status.Starting, RvBusyLook.Bar, dark = true)

    @Test fun startingV3Light() = shot(Status.Starting, RvBusyLook.Tint, dark = false)

    @Test fun startingV3Dark() = shot(Status.Starting, RvBusyLook.Tint, dark = true)

    @Test fun stoppingV1Light() = shot(Status.Stopping, RvBusyLook.Chip, dark = false)

    @Test fun stoppingV1Dark() = shot(Status.Stopping, RvBusyLook.Chip, dark = true)

    @Test fun stoppingV2Light() = shot(Status.Stopping, RvBusyLook.Bar, dark = false)

    @Test fun stoppingV2Dark() = shot(Status.Stopping, RvBusyLook.Bar, dark = true)

    @Test fun stoppingV3Light() = shot(Status.Stopping, RvBusyLook.Tint, dark = false)

    @Test fun stoppingV3Dark() = shot(Status.Stopping, RvBusyLook.Tint, dark = true)

    private fun shot(status: Status, look: RvBusyLook, dark: Boolean) {
        val name = if (status == Status.Starting) "activity-starting" else "activity-stopping"
        val state = rvIdleActivity().copy(status = status)
        compose.rvShot("$name-v${look.ordinal}", dark) { RvActivityScreen(state, onAction = {}, onConnect = {}, busy = look) }
    }
}
