package dev.doona.android.feature.settings

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.prototype.rv.rvContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 實驗室: 效能模式 is stored at once; the restart banner shows while it differs from what the app started with, and 重開 App restarts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW")
class LabRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theBannerShowsOnceTheSwitchTurnsAndGoesWhenItTurnsBack() {
        var restarts = 0
        val store = FakeSettingsStore()
        compose.rvContent(dark = false) { LabRoute(store, onBack = {}, onRestart = { restarts++ }) }
        compose.onNodeWithText(HINT).assertDoesNotExist()

        compose.onNode(isToggleable()).performClick()
        compose.onNode(isToggleable()).assertIsOn()
        assertTrue("stored at once", store.performanceMode.value)
        compose.onNodeWithText(HINT).assertExists()
        compose.onNodeWithText(RESTART).performClick()
        assertEquals(1, restarts)

        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(HINT).assertDoesNotExist()
    }

    @Test
    fun anAppThatStartedWithTheModeOnOffersARestartWhenItIsTurnedOff() {
        compose.rvContent(dark = false) { LabRoute(FakeSettingsStore(performanceMode = true), onBack = {}, onRestart = {}) }
        compose.onNodeWithText(HINT).assertDoesNotExist()
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText(HINT).assertExists()
    }

    private companion object {
        const val HINT = "重開 App 後生效"
        const val RESTART = "重開 App"
    }
}
