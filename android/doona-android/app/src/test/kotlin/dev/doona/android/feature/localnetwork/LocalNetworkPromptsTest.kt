package dev.doona.android.feature.localnetwork

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        show(LocalNetworkHost(access, request = {}, openSettings = { opened++ }))
        compose.onNodeWithText(text(R.string.rv_lan_open_settings)).performClick()
        compose.waitForIdle()
        assertEquals("the app's settings page", 1, opened)
        assertNull("the refusal is closed", access.prompt.value)
        assertNull("the action waits for the user's return, its failure not yet final", retry)
        granted = true
        access.resumed()
        assertEquals(true, retry)
    }

    @Test
    fun theReasonsContinueGoesOnToTheSystemsQuestion() {
        var answer: Boolean? = null
        scope.launch { answer = access.ask() }
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        var requested = 0
        show(LocalNetworkHost(access, request = { requested++ }, openSettings = {}))
        compose.onNodeWithText(text(R.string.rv_lan_ask_continue)).performClick()
        compose.waitForIdle()
        assertEquals("the system's question", 1, requested)
        access.answered(true)
        assertEquals(true, answer)
    }

    @Test
    fun theReasonsNotNowIsARefusalAndTheNextFailureShowsTheSettingsWay() {
        var answer: Boolean? = null
        scope.launch { answer = access.ask() }
        var requested = 0
        show(LocalNetworkHost(access, request = { requested++ }, openSettings = {}))
        compose.onNodeWithText(text(R.string.rv_lan_ask_not_now)).performClick()
        compose.waitForIdle()
        assertEquals("no system question", 0, requested)
        assertEquals(false, answer)
        scope.launch { assertFalse(access.ask()) }
        assertEquals("a refusal is final for the process", LocalNetworkPrompt.Denied, access.prompt.value)
    }

    private fun show(host: LocalNetworkHost) = compose.setContent {
        RvTheme(rvSystemScheme(false), dark = false) { LocalNetworkPrompts(host) }
    }

    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)
}
