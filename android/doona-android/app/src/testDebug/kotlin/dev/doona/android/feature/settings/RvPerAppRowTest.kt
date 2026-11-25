package dev.doona.android.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvPerApp
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.prototype.rv.settings.RV_SAMPLE_GEO_SHIPPED
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.prototype.rv.settings.rvSettingsRoutes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 依 App's second line on 設定 in each policy: 全部 App, how many apps only, how many kept out (each in the plural the
 * count takes), and no line at all until the core's per-app policy has been read, so a read that fails or has not
 * answered never shows a policy the user did not choose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW-w412dp-h2400dp-notnight-xhdpi")
class RvPerAppRowTest {
    @get:Rule
    val compose = createComposeRule()

    private var perApp by mutableStateOf(RvPerApp(RvAppPolicy.All, emptySet()))
    private var loaded by mutableStateOf(true)

    private fun show() = compose.setContent {
        RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
            RvSettingsScreen(
                rvSampleSettings().copy(perApp = perApp),
                rvSampleVersions.app,
                onChange = {},
                onOpenTheme = {},
                routes = rvSettingsRoutes(),
                geoData = RvGeoData(RV_SAMPLE_GEO_SHIPPED),
                perAppLoaded = loaded,
            )
        }
    }

    private fun apps(vararg names: String) = names.toSet()

    @Test
    fun eachPolicySaysItselfAndNothingShowsUntilItIsRead() {
        show()
        expect(rvText(R.string.perapp_policy_all))
        compose.runOnIdle { perApp = RvPerApp(RvAppPolicy.Only, apps("a", "b", "c")) }
        expect("只代理 3 個 App")
        compose.runOnIdle { perApp = RvPerApp(RvAppPolicy.Except, apps("a", "b")) }
        expect("不代理 2 個 App")
        compose.runOnIdle { loaded = false }
        for (text in listOf(rvText(R.string.perapp_policy_all), "只代理", "不代理")) compose.onAllNodes(shows(text, whole = false)).assertCountEquals(0)
    }

    @Test
    @Config(qualifiers = "en-w412dp-h2400dp-notnight-xhdpi")
    fun englishTakesTheCountsPlural() {
        perApp = RvPerApp(RvAppPolicy.Only, apps("a"))
        show()
        expect("1 app only")
        compose.runOnIdle { perApp = RvPerApp(RvAppPolicy.Only, apps("a", "b")) }
        expect("2 apps only")
        compose.runOnIdle { perApp = RvPerApp(RvAppPolicy.Except, apps("a")) }
        expect("All but 1 app")
        compose.runOnIdle { perApp = RvPerApp(RvAppPolicy.Except, apps("a", "b", "c")) }
        expect("All but 3 apps")
        compose.runOnIdle { perApp = RvPerApp(RvAppPolicy.All, emptySet()) }
        expect("All apps")
    }

    private fun expect(text: String) = compose.onAllNodes(shows(text, whole = true)).assertCountEquals(1)

    /** A text node showing [text] (or containing it, unless [whole]), whatever joiners the line breaking put in it. */
    private fun shows(text: String, whole: Boolean) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any {
            val shown = it.text.replace("⁠", "").replace("​", "").replace(' ', ' ')
            if (whole) shown == text else text in shown
        } == true
    }
}
