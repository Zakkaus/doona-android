package dev.doona.android.prototype.rv.policy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.feature.settings.RvDnsServersPage
import dev.doona.android.feature.settings.rvDeleteRefusedBanner
import dev.doona.android.feature.settings.rvDnsAddressValid
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * DNS 伺服器, the servers dns.dae declares (no prototype; sample only): `dns-servers` the list; `-edit` with the add
 * and remove controls, a remove control at each server's end and 新增伺服器 ending the card; `-confirm` the question for `google`, which no rule names; `-refused` `local`, which a query rule names;
 * `-add` the new server's sheet filled in, `-add-invalid` a taken name and an address without a scheme; `-empty` a
 * config with no server.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvDnsServersShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun listLight() = shot("dns-servers", false) { Page(editing = false) }

    @Test fun listDark() = shot("dns-servers", true) { Page(editing = false) }

    @Test fun listFs200() = compose.rvShot("dns-servers", false, before = { assertWhole() }, fontScale = RV_FS200) { Page(editing = false) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun listEnLight() = compose.rvShot("dns-servers", false, before = { assertWhole() }, english = true) { Page(editing = false) }

    @Test fun editLight() = shot("dns-servers-edit", false) { Page() }

    @Test fun editDark() = shot("dns-servers-edit", true) { Page() }

    @Test
    @Config(qualifiers = "+h991dp")
    fun editFs200() = compose.rvShot("dns-servers-edit", false, before = { assertWhole() }, fontScale = RV_FS200) { Page() }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun editEnLight() = compose.rvShot("dns-servers-edit", false, before = { assertWhole() }, english = true) { Page() }

    @Test fun confirmLight() = confirm(false)

    @Test fun confirmDark() = confirm(true)

    @Test fun refusedLight() = shot("dns-servers-refused", false) { Page(banner = refused()) }

    @Test fun refusedDark() = shot("dns-servers-refused", true) { Page(banner = refused()) }

    @Test fun addLight() = compose.rvShot("dns-servers-add", false, windows = true) { Page(adding = ADD) }

    @Test fun addDark() = compose.rvShot("dns-servers-add", true, windows = true) { Page(adding = ADD) }

    @Test fun addInvalidLight() = compose.rvShot("dns-servers-add-invalid", false, windows = true) { Page(adding = INVALID) }

    @Test fun addInvalidDark() = compose.rvShot("dns-servers-add-invalid", true, windows = true) { Page(adding = INVALID) }

    @Test fun emptyLight() = shot("dns-servers-empty", false) { Page(servers = emptyList()) }

    @Test fun emptyDark() = shot("dns-servers-empty", true) { Page(servers = emptyList()) }

    @Test
    fun aServersRemoveControlAsksAndTheSheetRemovesIt() {
        val removed = mutableListOf<String>()
        compose.setContent { Themed { RvDnsServersPage(RvNativeSample.dnsServers, onBack = {}, edit = RvListEdit({}, { removed += it })) } }
        compose.onNodeWithContentDescription("刪除 google").performClick()
        compose.onNodeWithText("刪除").performClick()
        assertEquals(listOf("google"), removed)
    }

    @Test
    fun newServerNeedsAFreeNameAndAHonkAddress() {
        var adding by mutableStateOf<RvDnsServer?>(null)
        val created = mutableListOf<RvDnsServer>()
        compose.setContent {
            Themed { RvDnsServersPage(RvNativeSample.dnsServers, onBack = {}, edit = RvListEdit({}, {}), adding = adding, onAdding = { adding = it }, onCreate = { created += it }) }
        }
        compose.onNodeWithText("新增伺服器").performClick()
        assertEquals(RvDnsServer("", ""), adding)
        adding = INVALID
        compose.onNodeWithText("已有同名的伺服器。").assertExists()
        compose.onNodeWithText(CREATE).assertIsNotEnabled()
        adding = ADD
        compose.onNodeWithText(CREATE).assertIsEnabled().performClick()
        assertEquals(listOf(ADD), created)
        assertEquals(null, adding)
    }

    @Test
    fun addressesFollowHonksSchemes() {
        listOf("udp://223.5.5.5:53", "tcp+udp://1.1.1.1:53", "TLS://dns.google:853", "https://dns.example/dns-query", "h3://dns.example", "quic://dns.example:853")
            .forEach { assertTrue(it, rvDnsAddressValid(it)) }
        listOf("8.8.8.8", "dns.google", "doh://dns.example", "https://", "udp://1.1.1.1 :53").forEach { assertFalse(it, rvDnsAddressValid(it)) }
    }

    private fun confirm(dark: Boolean) = compose.rvShot(
        "dns-servers-confirm",
        dark,
        before = { onNodeWithContentDescription("刪除 google").performClick() },
        windows = true,
    ) { Page() }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) = compose.rvShot(name, dark, before = { assertWhole() }, content = content)

    @Composable
    private fun Page(editing: Boolean = true, servers: List<RvDnsServer> = RvNativeSample.dnsServers, banner: RvNotice? = null, adding: RvDnsServer? = null) {
        var draft by remember { mutableStateOf(adding) }
        RvDnsServersPage(servers, onBack = {}, edit = if (editing) RvListEdit({}, {}) else null, banner = banner, adding = draft, onAdding = { draft = it })
    }

    @Composable
    private fun refused() = rvDeleteRefusedBanner("local", 1) {}

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(rvSystemScheme(false), false, content)

    private companion object {
        const val CREATE = "新增"
        val ADD = RvDnsServer("alidns", "https://dns.alidns.com/dns-query")
        val INVALID = RvDnsServer("local", "8.8.8.8")
    }
}
