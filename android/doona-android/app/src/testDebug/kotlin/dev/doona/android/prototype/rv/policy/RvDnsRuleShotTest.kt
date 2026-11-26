package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.feature.policies.RvNativeRuleAt
import dev.doona.android.feature.settings.RvDnsAction
import dev.doona.android.feature.settings.RvDnsRuleDraft
import dev.doona.android.feature.settings.RvDnsRuleEditPage
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Samples of a DNS rule's page (brief p14, config plan row 17; schema 4 §8), on the sample config's dns.dae: three
 * servers, four query rules and one response rule. `dns-query` sends `corp.example` to the server `local`, so the
 * server list shows; `dns-query-paused` is the paused ad block, a `geosite` match shown as its source line, rejected;
 * `dns-query-asis` the last query rule, `asis`, with no server list; `dns-response` the only response rule, an `&&`
 * match shown as source, asked again of `backup`, with no 位置.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvDnsRuleShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE_QUERY)
    fun queryLight() = compose.rvShot("dns-query", dark = false, before = { assertWhole() }) { Page(RvNativeSample.dnsQuery, THIRD) }

    @Test
    @Config(qualifiers = WHOLE_QUERY)
    fun queryDark() = compose.rvShot("dns-query", dark = true, before = { assertWhole() }) { Page(RvNativeSample.dnsQuery, THIRD) }

    @Test
    fun queryFs200() = compose.rvShot("dns-query", dark = false, fontScale = RV_FS200) { Page(RvNativeSample.dnsQuery, THIRD) }

    @Test
    @Config(qualifiers = WHOLE_QUERY_EN)
    fun queryEnLight() = compose.rvShot("dns-query", dark = false, before = { assertWhole() }, english = true) { Page(RvNativeSample.dnsQuery, THIRD) }

    @Test
    @Config(qualifiers = WHOLE_PAUSED)
    fun pausedLight() = compose.rvShot("dns-query-paused", dark = false, before = { assertWhole() }) { Page(RvNativeSample.dnsAds, ADS) }

    @Test
    @Config(qualifiers = WHOLE_PAUSED)
    fun pausedDark() = compose.rvShot("dns-query-paused", dark = true, before = { assertWhole() }) { Page(RvNativeSample.dnsAds, ADS) }

    @Test
    fun pausedFs200() = compose.rvShot("dns-query-paused", dark = false, fontScale = RV_FS200) { Page(RvNativeSample.dnsAds, ADS) }

    @Test
    @Config(qualifiers = WHOLE_PAUSED_EN)
    fun pausedEnLight() = compose.rvShot("dns-query-paused", dark = false, before = { assertWhole() }, english = true) { Page(RvNativeSample.dnsAds, ADS) }

    @Test
    @Config(qualifiers = WHOLE_AS_IS)
    fun asIsLight() = compose.rvShot("dns-query-asis", dark = false, before = { assertWhole() }) { Page(RvNativeSample.dnsAsIs, LAST) }

    @Test
    @Config(qualifiers = WHOLE_AS_IS)
    fun asIsDark() = compose.rvShot("dns-query-asis", dark = true, before = { assertWhole() }) { Page(RvNativeSample.dnsAsIs, LAST) }

    @Test
    fun asIsFs200() = compose.rvShot("dns-query-asis", dark = false, fontScale = RV_FS200) { Page(RvNativeSample.dnsAsIs, LAST) }

    @Test
    @Config(qualifiers = WHOLE_AS_IS_EN)
    fun asIsEnLight() = compose.rvShot("dns-query-asis", dark = false, before = { assertWhole() }, english = true) { Page(RvNativeSample.dnsAsIs, LAST) }

    @Test
    @Config(qualifiers = WHOLE_RESPONSE)
    fun responseLight() = compose.rvShot("dns-response", dark = false, before = { assertWhole() }) { Page(RvNativeSample.dnsPrivate, RESPONSE) }

    @Test
    @Config(qualifiers = WHOLE_RESPONSE)
    fun responseDark() = compose.rvShot("dns-response", dark = true, before = { assertWhole() }) { Page(RvNativeSample.dnsPrivate, RESPONSE) }

    @Test
    fun responseFs200() = compose.rvShot("dns-response", dark = false, fontScale = RV_FS200) { Page(RvNativeSample.dnsPrivate, RESPONSE) }

    @Test
    @Config(qualifiers = WHOLE_RESPONSE_EN)
    fun responseEnLight() = compose.rvShot("dns-response", dark = false, before = { assertWhole() }, english = true) { Page(RvNativeSample.dnsPrivate, RESPONSE) }

    @Test
    fun choosingAServerPicksTheFirstAndShowsTheList() {
        var draft by mutableStateOf(RvNativeSample.dnsAsIs)
        compose.setContent { Themed { RvDnsRuleEditPage(draft, { draft = it }, RvNativeSample.upstreams, LAST, {}, {}, {}) } }
        compose.onNodeWithText("remote").assertDoesNotExist()
        compose.onNodeWithText("指定伺服器").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(RvDnsAction.Upstream to "local", draft.action to draft.upstream)
        compose.onNodeWithText("remote").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("remote", draft.upstream)
    }

    @Test
    fun aResponseIsAcceptedRejectedOrAskedAgain() {
        compose.setContent { Themed { RvDnsRuleEditPage(RvNativeSample.dnsPrivate, {}, RvNativeSample.upstreams, RESPONSE, {}, {}, {}) } }
        listOf("接受", "拒絕", "重新查詢").forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("原伺服器").assertDoesNotExist()
    }

    @Test
    fun aDnsRuleHasNoMustAndTheOnlyRuleNoPosition() {
        compose.setContent { Themed { RvDnsRuleEditPage(RvNativeSample.dnsPrivate, {}, RvNativeSample.upstreams, RESPONSE, {}, {}, {}) } }
        compose.onNodeWithText("所有模式都生效").assertDoesNotExist()
        compose.onNodeWithText("位置").assertDoesNotExist()
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = dev.doona.android.designsystem.rv.RvTheme(
        dev.doona.android.prototype.rv.seedColorScheme(dev.doona.android.prototype.rv.Seed.Cyan.argb, false),
        false,
        content,
    )

    /** The page over [start], held here as a route would hold it, at [at]. */
    @Composable
    private fun Page(start: RvDnsRuleDraft, at: RvNativeRuleAt) {
        var draft by remember { mutableStateOf(start) }
        RvDnsRuleEditPage(draft, { draft = it }, RvNativeSample.upstreams, at, onBack = {}, onApply = {}, onDelete = {})
    }

    private companion object {
        val THIRD = RvNativeRuleAt(3, 4)
        val LAST = RvNativeRuleAt(4, 4)
        val ADS = RvNativeRuleAt(1, 4, dnsLine(9, "      # qname(geosite: category-ads-all) -> reject"))
        val RESPONSE = RvNativeRuleAt(1, 1, dnsLine(16, "      upstream(remote) && ip(geoip: private) -> backup"))

        fun dnsLine(line: Int, text: String) = RvDiffFile("dns.dae", listOf(listOf(RvDiffLine(RvDiffKind.Same, line, text))))

        /** Windows as tall as each page whole. */
        const val WHOLE_QUERY = "+h1224dp"
        const val WHOLE_QUERY_EN = "en-w412dp-h1222dp-notnight-xhdpi"
        const val WHOLE_PAUSED = "+h917dp"
        const val WHOLE_PAUSED_EN = "en-w412dp-h908dp-notnight-xhdpi"
        const val WHOLE_AS_IS = "+h871dp"
        const val WHOLE_AS_IS_EN = "en-w412dp-h890dp-notnight-xhdpi"
        const val WHOLE_RESPONSE = "+h1065dp"
        const val WHOLE_RESPONSE_EN = "en-w412dp-h1055dp-notnight-xhdpi"
    }
}
