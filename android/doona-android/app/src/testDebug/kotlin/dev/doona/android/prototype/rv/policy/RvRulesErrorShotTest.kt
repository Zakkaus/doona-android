package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 全部規則 when its source did not parse (`proto-rv-rules-error-parse-*`) and when the rules could not be read
 * (`-rules-error-read-*`); 檢視生效設定 when the core cut the text at 1 MiB (`-effective-config-truncated-*`, the whole
 * page under a caption) and when the config could not be read (`-effective-config-failed-*`). Each a banner or a caption,
 * light, dark, fs115 and fs200, its first screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRulesErrorShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun parseLight() = shot("rules-error-parse", dark = false) { Parse() }

    @Test fun parseDark() = shot("rules-error-parse", dark = true) { Parse() }

    @Test fun parseFs115() = shot("rules-error-parse", fontScale = RV_FS115) { Parse() }

    @Test fun parseFs200() = shot("rules-error-parse", fontScale = RV_FS200) { Parse() }

    @Test fun readLight() = shot("rules-error-read", dark = false) { Read() }

    @Test fun readDark() = shot("rules-error-read", dark = true) { Read() }

    @Test fun readFs115() = shot("rules-error-read", fontScale = RV_FS115) { Read() }

    @Test fun readFs200() = shot("rules-error-read", fontScale = RV_FS200) { Read() }

    @Test fun truncatedLight() = shot("effective-config-truncated", dark = false) { Truncated() }

    @Test fun truncatedDark() = shot("effective-config-truncated", dark = true) { Truncated() }

    @Test fun truncatedFs115() = shot("effective-config-truncated", fontScale = RV_FS115) { Truncated() }

    @Test fun truncatedFs200() = shot("effective-config-truncated", fontScale = RV_FS200) { Truncated() }

    @Test fun failedLight() = shot("effective-config-failed", dark = false) { Failed() }

    @Test fun failedDark() = shot("effective-config-failed", dark = true) { Failed() }

    @Test fun failedFs115() = shot("effective-config-failed", fontScale = RV_FS115) { Failed() }

    @Test fun failedFs200() = shot("effective-config-failed", fontScale = RV_FS200) { Failed() }

    private fun shot(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) =
        compose.rvShot(name, dark, fontScale = fontScale, content = content)

    /** The source did not compile, so no row came from it: the banner stands where the list would be, and 範本 is the way out. */
    @Composable
    private fun Parse() = Rules { Banner(R.string.rv_rules_parse_failed, R.string.rv_rules_parse_failed_reason, R.string.rv_policy_template) }

    /** The read failed with the engine's `internal`: the banner stands where the list would be, and 重試 reads again. */
    @Composable
    private fun Read() = Rules { Banner(R.string.rv_rules_read_failed, R.string.error_internal, R.string.rv_retry) }

    @Composable
    private fun Rules(banner: @Composable () -> Unit) =
        RvRulesPage(RvPolicyData.allRules, emptyList(), editing = false, onBack = {}, notice = banner, noticeOnly = true)

    @Composable
    private fun Truncated() = RvEffectiveConfigPage(RvPolicyState(), rvSampleSettings(), onBack = {}, truncated = true)

    @Composable
    private fun Failed() = RvEffectiveConfigPage(
        RvPolicyState(),
        rvSampleSettings(),
        onBack = {},
        notice = { Banner(R.string.rv_effective_failed, R.string.error_internal, R.string.rv_retry) },
        noticeOnly = true,
    )

    @Composable
    private fun Banner(title: Int, reason: Int, action: Int) = RvBanner(stringResource(title), stringResource(reason), stringResource(action), {})
}
