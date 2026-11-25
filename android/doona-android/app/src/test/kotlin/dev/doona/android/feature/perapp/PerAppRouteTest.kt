package dev.doona.android.feature.perapp

import android.app.Activity
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** The page as a user drives it: the real route, ViewModel, mapper and RV page, on the fake engine and apps. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h915dp")
class PerAppRouteTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent { RvTheme(rvSystemScheme(false), false, content) }

    @Test
    fun `searching, checking a result and saving hands the engine exactly one policy`() {
        val fake = FakeEngine()
        val saved = CopyOnWriteArrayList<PerApp>()
        val engine = object : Engine by fake {
            override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
                saved += perApp
                return fake.setPerApp(perApp)
            }
        }
        show { PerAppRoute(engine, FakeInstalledApps(), onConnect = {}, onAddSubscription = {}, onCheckUpdate = {}, onBack = {}) }

        compose.onNodeWithText("Only checked").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("tele")
        compose.waitForIdle()
        compose.onNodeWithText("Chrome").assertDoesNotExist()
        compose.onNodeWithText("Telegram").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { saved.isNotEmpty() }
        compose.waitForIdle()

        assertEquals(listOf(PerApp(AppPolicy.OnlyListed, setOf("org.telegram.messenger"))), saved.toList())
        assertEquals(saved.single(), fake.settings.value.perApp)
    }

    /**
     * Saves on an engine whose rebuild fails with [failure], taps [action] on the notice and returns what reached the
     * outside: `connect`, `subscription`, `update`, or the action of an intent the page's activity started. The notice
     * must be gone afterwards.
     */
    private fun tapOnRebuildNotice(failure: RebuildFailedException, action: String): List<String> {
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
                fake.setPerApp(perApp)
                return Result.failure(failure)
            }
        }
        val calls = CopyOnWriteArrayList<String>()
        var activity: Activity? = null
        show {
            activity = LocalActivity.current
            PerAppRoute(
                engine,
                FakeInstalledApps(),
                onConnect = { calls += "connect" },
                onAddSubscription = { calls += "subscription" },
                onCheckUpdate = { calls += "update" },
                onBack = {},
            )
        }

        compose.onNodeWithText("All but checked").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(action).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(NOTICE).assertExists()
        compose.onNodeWithText(action).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(NOTICE).assertDoesNotExist()
        shadowOf(checkNotNull(activity)).nextStartedActivity?.let { calls += it.action }
        return calls.toList()
    }

    @Test
    fun `retry on the rebuild notice connects through the activity and nothing else`() {
        assertEquals(listOf("connect"), tapOnRebuildNotice(RebuildFailedException(FailureReason.StartFailed), "Try again"))
    }

    @Test
    fun `add subscription on the rebuild notice opens 訂閱 and nothing else`() {
        val failure = RebuildFailedException(FailureReason.StartFailed, ErrorCode.NoNodes)
        assertEquals(listOf("subscription"), tapOnRebuildNotice(failure, "Add subscription"))
    }

    @Test
    fun `check for updates on the rebuild notice opens the releases and nothing else`() {
        val failure = RebuildFailedException(FailureReason.IncompatibleCore)
        assertEquals(listOf("update"), tapOnRebuildNotice(failure, "Check for updates"))
    }

    @Test
    fun `open VPN settings on the rebuild notice starts the system's VPN settings and nothing else`() {
        val failure = RebuildFailedException(FailureReason.LockdownEnabled)
        assertEquals(listOf(Settings.ACTION_VPN_SETTINGS), tapOnRebuildNotice(failure, "Open VPN settings"))
    }

    @Test
    fun `edit app list on the rebuild notice only closes it`() {
        val failure = RebuildFailedException(FailureReason.NoAllowedApps)
        assertEquals(emptyList<String>(), tapOnRebuildNotice(failure, "Edit app list"))
    }

    private companion object {
        /** The rebuild notice's title in en. */
        const val NOTICE = "Saved, but the VPN could not reconnect"
    }
}
