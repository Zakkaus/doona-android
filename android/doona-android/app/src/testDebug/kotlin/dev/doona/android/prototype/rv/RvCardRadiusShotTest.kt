package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.LocalRvCardRadius
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyScreenSample
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The card radius sample (owner-fix item 8, 20 or 24 dp): 活動 and 策略 in light and dark at the token's 20 dp
 * (`proto-rv-radius20-*`) and at 24 dp (`proto-rv-radius24-*`), through the preview-only [LocalRvCardRadius]. The 20 dp
 * shots draw exactly what `proto-rv-activity-*` and `proto-rv-policy-*` do (same windows and data), so they must be byte
 * identical to them; the token itself stays 20 dp and no page sets the local.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvCardRadiusShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun activity20Light() = activity(RADIUS_20, dark = false)

    @Test fun activity20Dark() = activity(RADIUS_20, dark = true)

    @Test fun activity24Light() = activity(RADIUS_24, dark = false)

    @Test fun activity24Dark() = activity(RADIUS_24, dark = true)

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun policy20Light() = policy(RADIUS_20, dark = false)

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun policy20Dark() = policy(RADIUS_20, dark = true)

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun policy24Light() = policy(RADIUS_24, dark = false)

    @Test
    @Config(qualifiers = WHOLE_RULE)
    fun policy24Dark() = policy(RADIUS_24, dark = true)

    @Test
    fun theTokenIsTwentyAndTheHighlightFollowsTheCard() {
        assertEquals(RADIUS_20, RvSize.cardRadius)
        var seen = emptyList<Dp>()
        compose.rvContent(dark = false) {
            seen = listOf(Rv.cardRadius, Rv.highlightRadius)
            CompositionLocalProvider(LocalRvCardRadius provides RADIUS_24) { seen = seen + listOf(Rv.cardRadius, Rv.highlightRadius) }
        }
        compose.waitForIdle()
        assertEquals(listOf(20.dp, 16.dp, 24.dp, 20.dp), seen)
    }

    private fun activity(radius: Dp, dark: Boolean) =
        compose.rvShot("radius${radius.value.toInt()}-activity", dark) { At(radius) { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}) } }

    private fun policy(radius: Dp, dark: Boolean) =
        compose.rvShot("radius${radius.value.toInt()}-policy", dark, before = { assertWhole() }) {
            At(radius) { RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }
        }

    @Composable
    private fun At(radius: Dp, content: @Composable () -> Unit) = CompositionLocalProvider(LocalRvCardRadius provides radius, content = content)

    private companion object {
        val RADIUS_20 = 20.dp
        val RADIUS_24 = 24.dp

        /** The window `RvPolicyShotTest` holds 策略 in 規則 in, so the radius 20 shot is its golden. */
        const val WHOLE_RULE = "+h1990dp"
    }
}
