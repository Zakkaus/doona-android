package dev.doona.android.feature.permissions

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/** The explanation's two buttons as the user taps them, wired to a real [PermissionAsks]. */
@RunWith(AndroidJUnit4::class)
class PermissionPromptsTest {
    @get:Rule
    val compose = createComposeRule()

    private val asks = PermissionAsks(SavedStateHandle())
    private val steps = mutableListOf<PermissionStep>()

    private fun show() = compose.setContent {
        RvTheme(rvSystemScheme(false), dark = false) { PermissionPrompts(asks) { steps += it } }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun theVpnExplanationSaysWhatTheVpnIsForAndContinueAsksTheSystem() {
        asks.connect(consentNeeded = true)
        show()
        compose.onNodeWithText(text(R.string.rv_perm_vpn_body)).assertExists()
        compose.onNodeWithText(text(R.string.rv_lan_ask_continue)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(PermissionStep.AskConsent), steps)
        assertNull(asks.showing.value)
    }

    @Test
    fun cancelOnTheVpnExplanationAsksNothing() {
        asks.connect(consentNeeded = true)
        show()
        compose.onNodeWithText(text(R.string.rv_cancel)).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<PermissionStep>(), steps)
        assertNull(asks.showing.value)
    }
}
