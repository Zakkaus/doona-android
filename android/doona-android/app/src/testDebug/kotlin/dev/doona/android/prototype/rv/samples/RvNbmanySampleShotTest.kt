package dev.doona.android.prototype.rv.samples

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The samples of nbmany v2, whole pages: `proto-rv-samples-nbmany-<two|five|none>-<approved|sample>-<light|dark|fs200|en-light>`.
 * The approved page is the group page as it is (eight nodes, 查看全部 opens the rest in place); the sample is the page built
 * like the node picker. `two` is two subscriptions with nine nodes, `five` five with forty, `none` the two-subscription
 * page with a query typed that no node matches (its approved page is `two`'s).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNbmanySampleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun twoApprovedLight() = compose.rvShot("samples-nbmany-two-approved", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupSectionsSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun twoApprovedDark() = compose.rvShot("samples-nbmany-two-approved", dark = true, before = { assertWhole() }) { RvGroupSamplePage(RvGroupSectionsSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1803dp-notnight-xhdpi")
    fun twoApprovedFs200() = compose.rvShot("samples-nbmany-two-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSamplePage(RvGroupSectionsSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "en-w412dp-h1123dp-notnight-xhdpi")
    fun twoApprovedEnLight() = compose.rvShot("samples-nbmany-two-approved-en", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupSectionsSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1452dp-notnight-xhdpi")
    fun twoSampleLight() = compose.rvShot("samples-nbmany-two-sample", dark = false, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1452dp-notnight-xhdpi")
    fun twoSampleDark() = compose.rvShot("samples-nbmany-two-sample", dark = true, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2642dp-notnight-xhdpi")
    fun twoSampleFs200() = compose.rvShot("samples-nbmany-two-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions) }

    @Test
    @Config(qualifiers = "en-w412dp-h1504dp-notnight-xhdpi")
    fun twoSampleEnLight() = compose.rvShot("samples-nbmany-two-sample-en", dark = false, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun fiveApprovedLight() = compose.rvShot("samples-nbmany-five-approved", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupSectionsSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun fiveApprovedDark() = compose.rvShot("samples-nbmany-five-approved", dark = true, before = { assertWhole() }) { RvGroupSamplePage(RvGroupSectionsSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1730dp-notnight-xhdpi")
    fun fiveApprovedFs200() = compose.rvShot("samples-nbmany-five-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSamplePage(RvGroupSectionsSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "en-w412dp-h1123dp-notnight-xhdpi")
    fun fiveApprovedEnLight() = compose.rvShot("samples-nbmany-five-approved-en", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupSectionsSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1695dp-notnight-xhdpi")
    fun fiveSampleLight() = compose.rvShot("samples-nbmany-five-sample", dark = false, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.five.group, RvGroupSectionsSample.five.subscriptions) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1695dp-notnight-xhdpi")
    fun fiveSampleDark() = compose.rvShot("samples-nbmany-five-sample", dark = true, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.five.group, RvGroupSectionsSample.five.subscriptions) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2989dp-notnight-xhdpi")
    fun fiveSampleFs200() = compose.rvShot("samples-nbmany-five-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSectionsPage(RvGroupSectionsSample.five.group, RvGroupSectionsSample.five.subscriptions) }

    @Test
    @Config(qualifiers = "en-w412dp-h1729dp-notnight-xhdpi")
    fun fiveSampleEnLight() = compose.rvShot("samples-nbmany-five-sample-en", dark = false, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.five.group, RvGroupSectionsSample.five.subscriptions) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
    fun noneSampleLight() = compose.rvShot("samples-nbmany-none-sample", dark = false, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions, query = NONE) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
    fun noneSampleDark() = compose.rvShot("samples-nbmany-none-sample", dark = true, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions, query = NONE) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
    fun noneSampleFs200() = compose.rvShot("samples-nbmany-none-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions, query = NONE) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun noneSampleEnLight() = compose.rvShot("samples-nbmany-none-sample-en", dark = false, before = { assertWhole() }) { RvGroupSectionsPage(RvGroupSectionsSample.two.group, RvGroupSectionsSample.two.subscriptions, query = NONE) }

    private companion object {
        const val ROWS = 8
        const val NONE = "zzz"
    }
}
