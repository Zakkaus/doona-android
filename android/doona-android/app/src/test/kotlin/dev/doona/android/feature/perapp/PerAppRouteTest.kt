package dev.doona.android.feature.perapp

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
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeInstalledApps
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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
        show { PerAppRoute(engine, FakeInstalledApps(), onConnect = {}, onBack = {}) }

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

    @Test
    fun `retry on the rebuild notice connects through the activity and the notice goes`() {
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
                fake.setPerApp(perApp)
                return Result.failure(RebuildFailedException(FailureReason.StartFailed))
            }
        }
        var connects = 0
        show { PerAppRoute(engine, FakeInstalledApps(), onConnect = { connects++ }, onBack = {}) }

        compose.onNodeWithText("All but checked").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Try again").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Saved, but the VPN could not reconnect").assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.waitForIdle()

        assertEquals(1, connects)
        compose.onNodeWithText("Saved, but the VPN could not reconnect").assertDoesNotExist()
    }
}
