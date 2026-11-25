package dev.doona.android.app

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.localnetwork.LocalNetworkHost
import dev.doona.android.feature.localnetwork.LocalNetworkPrompt
import dev.doona.android.feature.permissions.Permission
import dev.doona.android.feature.permissions.PermissionAsks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** The permission explanation and the local network prompt from one host: one sheet at a time, the other after it. */
@RunWith(AndroidJUnit4::class)
class AppSheetsTest {
    @get:Rule
    val compose = createComposeRule()

    private val asks = PermissionAsks(SavedStateHandle())
    private val access = LocalNetworkAccess { false }
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun show() = compose.setContent {
        RvTheme(rvSystemScheme(false), dark = false) { AppSheets(asks, onStep = {}, LocalNetworkHost(access, request = {}, openSettings = {})) }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun askLocalNetwork() {
        scope.launch { access.ask() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
    }

    @Test
    fun theLocalNetworkPromptWaitsBehindThePermissionsExplanation() {
        asks.connect(consentNeeded = true)
        askLocalNetwork()
        show()
        compose.onNodeWithText(text(R.string.rv_perm_vpn_body)).assertExists()
        compose.onNodeWithText(text(R.string.rv_lan_ask_body)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.rv_cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.rv_perm_vpn_body)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.rv_lan_ask_body)).assertExists()
    }

    @Test
    fun aPermissionsExplanationWaitsBehindTheLocalNetworkPromptOnScreen() {
        askLocalNetwork()
        show()
        compose.onNodeWithText(text(R.string.rv_lan_ask_body)).assertExists()

        asks.connect(consentNeeded = true)
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.rv_lan_ask_body)).assertExists()
        compose.onNodeWithText(text(R.string.rv_perm_vpn_body)).assertDoesNotExist()

        compose.onNodeWithText(text(R.string.rv_lan_ask_not_now)).performClick()
        compose.waitForIdle()
        assertNull(access.prompt.value)
        compose.onNodeWithText(text(R.string.rv_lan_ask_body)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.rv_perm_vpn_body)).assertExists()
        assertEquals(Permission.Vpn, asks.showing.value)
    }
}
