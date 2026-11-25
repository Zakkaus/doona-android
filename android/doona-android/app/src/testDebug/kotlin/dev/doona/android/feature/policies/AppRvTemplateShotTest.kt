package dev.doona.android.feature.policies

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 範本, its review and 生效設定 as the app draws them from an engine (goldens `app-rv-template-*`,
 * `app-rv-template-impact-*`, `app-rv-effective-config-*`). The core builds one template, so 範本 lists it in use; the
 * review is reached only from rules that come from no template, as [handWritten]'s do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvTemplateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun templateLight() = shot("template", dark = false) { TemplateRoute(rvSampleEngine(), onBack = {}, onReview = {}) }

    @Test
    fun templateHandWrittenLight() = shot("template-hand", dark = false) { TemplateRoute(handWritten(), onBack = {}, onReview = {}) }

    @Test
    fun impactLight() = shot("template-impact", dark = false) { TemplateImpactRoute(handWritten(), "cn-direct", onBack = {}, onApplied = {}) }

    @Test
    fun impactDark() = shot("template-impact", dark = true) { TemplateImpactRoute(handWritten(), "cn-direct", onBack = {}, onApplied = {}) }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE)
    fun effectiveLight() = shot("effective-config", dark = false, whole = true) { Effective() }

    @Test
    @Config(qualifiers = WHOLE_EFFECTIVE)
    fun effectiveDark() = shot("effective-config", dark = true, whole = true) { Effective() }

    /** With the template in use 檢視變更 stays off; from a hand-written file it opens the review of the one template. */
    @Test
    fun theReviewOpensOnlyForAnotherTemplate() {
        val opened = mutableListOf<String>()
        compose.rvContent(dark = false) { TemplateRoute(handWritten(), onBack = {}, onReview = { opened += it }) }
        compose.onNode(hasText(text(R.string.rv_template_review)), useUnmergedTree = true).performClick()
        assertEquals(listOf("cn-direct"), opened)
    }

    /** 套用 sets the template, then leaves for 策略. */
    @Test
    fun applyingSetsTheTemplateAndLeaves() {
        val engine = handWritten()
        var left = 0
        compose.rvContent(dark = false) { TemplateImpactRoute(engine, "cn-direct", onBack = {}, onApplied = { left++ }) }
        compose.onNode(hasText(text(R.string.rv_policy_apply)), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertEquals(listOf("cn-direct"), engine.applied)
        assertEquals(1, left)
    }

    @Composable
    private fun Effective() {
        val engine = remember { rvSampleEngine() }
        val vm = remember { EffectiveConfigViewModel(engine, reads = Dispatchers.Unconfined) }
        EffectiveConfigRoute(vm, onBack = {}, onRules = {})
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, whole: Boolean = false, content: @Composable () -> Unit) {
        compose.rvContent(dark, content = content)
        if (whole) compose.assertWhole()
        compose.drawFully()
        val name = "app-rv-$screen-${if (dark) "dark" else "light"}"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    /** The sample engine with its rules from the configuration file, no template; it records the templates set. */
    private class HandWritten(private val base: Engine = rvSampleEngine()) : Engine by base {
        val applied = mutableListOf<String>()

        override val catalog: StateFlow<Catalog?> = base.catalog
            .map { it?.copy(ruleSource = it.ruleSource.copy(template = null, subscriptionId = null)) }
            .stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, null)

        override suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit> {
            applied += template
            return Result.success(Unit)
        }
    }

    private fun handWritten() = HandWritten()

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"

        /** Taller than 生效設定 whole, as `proto-rv-effective-config-*`'s window (+h2106dp) and the app's longer rules need. */
        const val WHOLE_EFFECTIVE = "+h2600dp"
    }
}
