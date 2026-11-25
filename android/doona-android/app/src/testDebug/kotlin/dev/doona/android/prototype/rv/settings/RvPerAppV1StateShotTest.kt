package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.perapp.RvPerAppActions
import dev.doona.android.feature.perapp.RvPerAppLook
import dev.doona.android.feature.perapp.RvPerAppScreen
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvShot
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 依 App's states on layout v1 (`perapp-v1-<state>`), light and dark, the phone's first screen as today's
 * `proto-rv-perapp-*`, over the layouts' sample (twelve apps, three checked): a search two apps match (`-search`), the
 * apps not yet read (`-loading`, animator scale 0, so the bar rests), a search none matches (`-no-result`), 只有勾選的
 * with nothing checked (`-none-selected`), a failed save (`-save-failed`) and a save whose tunnel would not come back
 * (`-rebuild-failed`). The names carry `v1` because today's page already records `perapp-<state>`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPerAppV1StateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun searchLight() = shot("search", dark = false)

    @Test fun searchDark() = shot("search", dark = true)

    @Test fun loadingLight() = shot("loading", dark = false)

    @Test fun loadingDark() = shot("loading", dark = true)

    @Test fun noResultLight() = shot("no-result", dark = false)

    @Test fun noResultDark() = shot("no-result", dark = true)

    @Test fun noneSelectedLight() = shot("none-selected", dark = false)

    @Test fun noneSelectedDark() = shot("none-selected", dark = true)

    @Test fun saveFailedLight() = shot("save-failed", dark = false)

    @Test fun saveFailedDark() = shot("save-failed", dark = true)

    @Test fun rebuildFailedLight() = shot("rebuild-failed", dark = false)

    @Test fun rebuildFailedDark() = shot("rebuild-failed", dark = true)

    private fun shot(state: String, dark: Boolean) {
        if (state == "loading") rvAnimatorScale(0f)
        compose.rvShot("perapp-v1-$state", dark) { RvPerAppScreen(rvPerAppLookSample(state), RvPerAppActions(), onBack = {}, look = RvPerAppLook.Checks) }
    }
}
