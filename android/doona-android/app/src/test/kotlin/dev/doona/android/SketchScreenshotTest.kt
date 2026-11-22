package dev.doona.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.app.ActivityKey
import dev.doona.android.app.DoonaApp
import dev.doona.android.app.GroupKey
import dev.doona.android.app.PoliciesKey
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.activity.ActivityScreen
import dev.doona.android.feature.activity.sampleActivityState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val LIGHT = "zh-rTW-w412dp-h915dp-notnight-xxhdpi"
private const val DARK = "zh-rTW-w412dp-h915dp-night-xxhdpi"

private val ACTIVITY = listOf(ActivityKey)
private val POLICIES = listOf(ActivityKey, PoliciesKey)
private val GROUP = listOf(ActivityKey, PoliciesKey, GroupKey("g-Proxy"))

/**
 * The 活動 and 策略 screens and the Proxy group page on a connected [FakeEngine] at 412 × 915 dp, zh-TW, light and
 * dark, and light at font scale 1.15. Robolectric draws no system bars, so [PhoneFrame] draws a status bar and a gesture bar over the
 * edge-to-edge app and hands it their insets.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SketchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val dir = System.getProperty("doona.shots") ?: "build/outputs/roborazzi"

    private val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0), traffic = FakeEngine.SAMPLE_TRAFFIC)

    private fun shoot(name: String, start: List<NavKey>, fontScale: Float = 1f) =
        shootContent(name, fontScale) { DoonaApp(engine, onConnect = {}, start) }

    private fun shootContent(name: String, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                DoonaTheme { PhoneFrame(content) }
            }
        }
        compose.onRoot().captureRoboImage("$dir/$name.png")
    }

    @Test
    @Config(qualifiers = LIGHT)
    fun activityLight() = shoot("01-activity-light", ACTIVITY)

    @Test
    @Config(qualifiers = DARK)
    fun activityDark() = shoot("01-activity-dark", ACTIVITY)

    @Test
    @Config(qualifiers = LIGHT)
    fun activityFontScale() = shoot("01-activity-fs115", ACTIVITY, 1.15f)

    @Test
    @Config(qualifiers = LIGHT)
    fun activityError() = shootContent("01-activity-error-light") { ActivityScreen(sampleActivityState(ErrorCode.Busy)) {} }

    @Test
    @Config(qualifiers = LIGHT)
    fun policiesLight() = shoot("02-policies-light", POLICIES)

    @Test
    @Config(qualifiers = DARK)
    fun policiesDark() = shoot("02-policies-dark", POLICIES)

    @Test
    @Config(qualifiers = LIGHT)
    fun policiesFontScale() = shoot("02-policies-fs115", POLICIES, 1.15f)

    @Test
    @Config(qualifiers = LIGHT)
    fun groupLight() = shoot("03-policy-group-light", GROUP)

    @Test
    @Config(qualifiers = DARK)
    fun groupDark() = shoot("03-policy-group-dark", GROUP)

    @Test
    @Config(qualifiers = LIGHT)
    fun groupFontScale() = shoot("03-policy-group-fs115", GROUP, 1.15f)
}

private val StatusBar = 40.dp
private val GestureBar = 24.dp

/** A phone's status bar (time, Wi-Fi, battery) and gesture handle, drawn over the app in the theme's text colour. */
@Composable
private fun PhoneFrame(content: @Composable () -> Unit) {
    val ink = DoonaTheme.colors.text
    CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = StatusBar, bottom = GestureBar)) {
        Box(Modifier.fillMaxSize()) {
            content()
            Row(Modifier.fillMaxWidth().height(StatusBar).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("10:24", color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Canvas(Modifier.size(16.dp)) {
                    val wifi = Path().apply {
                        moveTo(size.width / 2, size.height * 0.9f)
                        lineTo(0f, size.height * 0.3f)
                        quadraticTo(size.width / 2, -size.height * 0.1f, size.width, size.height * 0.3f)
                        close()
                    }
                    drawPath(wifi, ink)
                }
                Spacer(Modifier.width(8.dp))
                Canvas(Modifier.size(width = 22.dp, height = 12.dp)) {
                    val r = CornerRadius(3.dp.toPx())
                    drawRoundRect(ink, size = Size(size.width - 2.dp.toPx(), size.height), cornerRadius = r, style = Stroke(1.5.dp.toPx()))
                    drawRoundRect(ink, topLeft = Offset(2.5.dp.toPx(), 2.5.dp.toPx()), size = Size((size.width - 7.dp.toPx()) * 0.8f, size.height - 5.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()))
                    drawRect(ink, topLeft = Offset(size.width - 1.5.dp.toPx(), size.height * 0.3f), size = Size(1.5.dp.toPx(), size.height * 0.4f))
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .size(width = 108.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ink),
            )
        }
    }
}
