package dev.doona.android.feature.localnetwork

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
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

/** The prompts' buttons as the user taps them, wired to a real [LocalNetworkAccess]. */
@RunWith(AndroidJUnit4::class)
class LocalNetworkPromptsTest {
    @get:Rule
    val compose = createComposeRule()

    private var granted = false
    private val access = LocalNetworkAccess { granted }

    /** Runs an action's side of the prompts at once, on the test's thread. */
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    @Test
    fun theRefusalsButtonOpensTheSettingsAndTheActionWaitsForTheReturn() {
        // An action failed on the local network, the user refused in the system's dialog, and the action waits on the
        // refusal, as LocalNetworkEngine does.
        var retry: Boolean? = null
        scope.launch { if (!access.ask()) retry = access.allowedInSettings() }
        access.requesting()
        access.answered(false)
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        var opened = 0
        compose.setContent {
            DoonaTheme { LocalNetworkPrompts(LocalNetworkHost(access, request = {}, openSettings = { opened++ })) }
        }
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.local_network_open_settings)).performClick()
        compose.waitForIdle()
        assertEquals("the app's settings page", 1, opened)
        assertNull("the refusal is closed", access.prompt.value)
        assertNull("the action waits for the user's return, its failure not yet final", retry)
        granted = true
        access.resumed()
        assertEquals(true, retry)
    }
}
