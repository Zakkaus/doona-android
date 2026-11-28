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
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The group editor as the app draws it (goldens `app-rv-group-edit-*`): the RV sample's 串流 group (g-Streaming, a
 * selector with 全部不可用時 直連) over honk's native API: 名稱 and 成員 opening the file, 策略's five policies, and
 * 選項; 全部不可用時 opens its picker.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvGroupEditShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = TALL)
    fun editLight() = shot("group-edit", dark = false) { Page() }

    @Test
    @Config(qualifiers = TALL)
    fun editDark() = shot("group-edit", dark = true) { Page() }

    @Test
    fun finalPickerLight() = shot("group-edit-final", dark = false, then = {
        compose.onNode(hasText(text(R.string.rv_group_edit_final)), useUnmergedTree = true).performClick()
    }) { Page() }

    /** Source editing stays available when the group name cannot be changed inline. */
    @Test
    fun editSourceOpensTheGroupsFile() {
        val opened = mutableListOf<String>()
        compose.rvContent(dark = false) { Page(onOpenSource = { opened += it }) }
        compose.onNode(hasText(text(R.string.rv_config_edit_source))).performClick()
        compose.waitForIdle()
        assertEquals(listOf("config.dae"), opened)
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, then: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.rvContent(dark, content = content)
        then()
        compose.waitForIdle()
        compose.drawFully()
        val name = "app-rv-$screen-${if (dark) "dark" else "light"}"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    @Composable
    private fun Page(onOpenSource: (String) -> Unit = {}) {
        val vm = remember {
            val honk = PolicyHonkFixtures.rvSample().apply {
                on("GET", "/api/v1/groups/g-Streaming/config", 200, HonkFixtures.body("group-config/current").replace("\"urltest\"", "\"selector\""))
            }
            GroupEditViewModel(honk, "g-Streaming", io = Dispatchers.Unconfined)
        }
        GroupEditRoute(vm, onBack = {}, onOpenSource = onOpenSource)
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val TALL = "+h1600dp"
    }
}
