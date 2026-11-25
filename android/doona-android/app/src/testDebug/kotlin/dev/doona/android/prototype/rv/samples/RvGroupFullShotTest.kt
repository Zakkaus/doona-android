package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.RvNodeSort
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
 * The combined group page, whole pages: `proto-rv-samples-gfull-<a|b|c|d|e>-sample-<light|dark|fs200|en-light>`, next to
 * the group page as it is today (`gfull-<two|five>-approved-...`, the page with the group's emoji names as they are).
 * a: two subscriptions, 分開選擇 TCP/UDP on; b: five subscriptions, on, the sections folded; c: a search with results
 * while on; d: off, the page without the 套用到 control; e: a Score group, on (Score picks, so no control); f: five
 * subscriptions filtered to one, ordered by 名稱; q: a search that finds nothing. Every one carries the chip row.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvGroupFullShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1834dp-notnight-xhdpi")
    fun aSampleLight() = compose.rvShot("samples-gfull-a-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1834dp-notnight-xhdpi")
    fun aSampleDark() = compose.rvShot("samples-gfull-a-sample", dark = true, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h3236dp-notnight-xhdpi")
    fun aSampleFs200() = compose.rvShot("samples-gfull-a-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupFullPage(RvGroupFullSample.two, apart = true) }

    @Test
    @Config(qualifiers = "en-w412dp-h1921dp-notnight-xhdpi")
    fun aSampleEnLight() = compose.rvShot("samples-gfull-a-sample-en", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2812dp-notnight-xhdpi")
    fun bSampleLight() = compose.rvShot("samples-gfull-b-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.five, apart = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2812dp-notnight-xhdpi")
    fun bSampleDark() = compose.rvShot("samples-gfull-b-sample", dark = true, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.five, apart = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h5122dp-notnight-xhdpi")
    fun bSampleFs200() = compose.rvShot("samples-gfull-b-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupFullPage(RvGroupFullSample.five, apart = true) }

    @Test
    @Config(qualifiers = "en-w412dp-h2892dp-notnight-xhdpi")
    fun bSampleEnLight() = compose.rvShot("samples-gfull-b-sample-en", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.five, apart = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1506dp-notnight-xhdpi")
    fun cSampleLight() = compose.rvShot("samples-gfull-c-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, query = QUERY) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1506dp-notnight-xhdpi")
    fun cSampleDark() = compose.rvShot("samples-gfull-c-sample", dark = true, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, query = QUERY) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2512dp-notnight-xhdpi")
    fun cSampleFs200() = compose.rvShot("samples-gfull-c-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupFullPage(RvGroupFullSample.two, apart = true, query = QUERY) }

    @Test
    @Config(qualifiers = "en-w412dp-h1658dp-notnight-xhdpi")
    fun cSampleEnLight() = compose.rvShot("samples-gfull-c-sample-en", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, query = QUERY) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1628dp-notnight-xhdpi")
    fun dSampleLight() = compose.rvShot("samples-gfull-d-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = false) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1628dp-notnight-xhdpi")
    fun dSampleDark() = compose.rvShot("samples-gfull-d-sample", dark = true, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = false) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2931dp-notnight-xhdpi")
    fun dSampleFs200() = compose.rvShot("samples-gfull-d-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupFullPage(RvGroupFullSample.two, apart = false) }

    @Test
    @Config(qualifiers = "en-w412dp-h1680dp-notnight-xhdpi")
    fun dSampleEnLight() = compose.rvShot("samples-gfull-d-sample-en", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = false) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1749dp-notnight-xhdpi")
    fun eSampleLight() = compose.rvShot("samples-gfull-e-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, score = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1749dp-notnight-xhdpi")
    fun eSampleDark() = compose.rvShot("samples-gfull-e-sample", dark = true, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, score = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h3201dp-notnight-xhdpi")
    fun eSampleFs200() = compose.rvShot("samples-gfull-e-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupFullPage(RvGroupFullSample.two, apart = true, score = true) }

    @Test
    @Config(qualifiers = "en-w412dp-h1797dp-notnight-xhdpi")
    fun eSampleEnLight() = compose.rvShot("samples-gfull-e-sample-en", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, score = true) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1659dp-notnight-xhdpi")
    fun fSampleLight() = compose.rvShot("samples-gfull-f-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.five, apart = true, subscription = PICKED, order = RvNodeSort.Name) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1659dp-notnight-xhdpi")
    fun fSampleDark() = compose.rvShot("samples-gfull-f-sample", dark = true, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.five, apart = true, subscription = PICKED, order = RvNodeSort.Name) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h2693dp-notnight-xhdpi")
    fun fSampleFs200() = compose.rvShot("samples-gfull-f-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupFullPage(RvGroupFullSample.five, apart = true, subscription = PICKED, order = RvNodeSort.Name) }

    @Test
    @Config(qualifiers = "en-w412dp-h1739dp-notnight-xhdpi")
    fun fSampleEnLight() = compose.rvShot("samples-gfull-f-sample-en", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.five, apart = true, subscription = PICKED, order = RvNodeSort.Name) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h923dp-notnight-xhdpi")
    fun qSampleLight() = compose.rvShot("samples-gfull-q-sample", dark = false, before = { assertWhole() }) { RvGroupFullPage(RvGroupFullSample.two, apart = true, query = NO_MATCH) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun twoApprovedLight() = compose.rvShot("samples-gfull-two-approved", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupFullSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun twoApprovedDark() = compose.rvShot("samples-gfull-two-approved", dark = true, before = { assertWhole() }) { RvGroupSamplePage(RvGroupFullSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1798dp-notnight-xhdpi")
    fun twoApprovedFs200() = compose.rvShot("samples-gfull-two-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSamplePage(RvGroupFullSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "en-w412dp-h1123dp-notnight-xhdpi")
    fun twoApprovedEnLight() = compose.rvShot("samples-gfull-two-approved-en", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupFullSample.two.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun fiveApprovedLight() = compose.rvShot("samples-gfull-five-approved", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupFullSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1124dp-notnight-xhdpi")
    fun fiveApprovedDark() = compose.rvShot("samples-gfull-five-approved", dark = true, before = { assertWhole() }) { RvGroupSamplePage(RvGroupFullSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "zh-rTW-w412dp-h1724dp-notnight-xhdpi")
    fun fiveApprovedFs200() = compose.rvShot("samples-gfull-five-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvGroupSamplePage(RvGroupFullSample.five.group, null, null, rows = ROWS) }

    @Test
    @Config(qualifiers = "en-w412dp-h1123dp-notnight-xhdpi")
    fun fiveApprovedEnLight() = compose.rvShot("samples-gfull-five-approved-en", dark = false, before = { assertWhole() }) { RvGroupSamplePage(RvGroupFullSample.five.group, null, null, rows = ROWS) }

    private companion object {
        const val ROWS = 8
        const val QUERY = "Hong"
        const val NO_MATCH = "Nowhere"

        /** A subscription of [RvGroupFullSample.five] that holds neither the TCP node nor the UDP node. */
        const val PICKED = "Meridian"
    }
}
