package dev.doona.android.feature.activity

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvPullLook
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 活動 holds what it shows while the pull or the picker covers it (plan 2.2). */
@RunWith(AndroidJUnit4::class)
class ActivityHoldTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a tick under the cover neither changes nor composes 活動, which catches up when uncovered`() {
        val tick = mutableIntStateOf(0)
        val progress = mutableFloatStateOf(0f)
        val look = RvPullLook(null, { progress.floatValue }, null)
        var shown = -1
        var compositions = 0
        compose.setContent {
            shown = rememberHeldWhileCovered(look) { tick.intValue }
            SideEffect { compositions++ }
        }
        compose.runOnIdle { tick.intValue = 1 }
        compose.runOnIdle { assertEquals(1, shown) }
        compose.runOnIdle { progress.floatValue = 0.5f }
        compose.runOnIdle {
            val before = compositions
            tick.intValue = 2
            tick.intValue = 3
            before
        }.let { before ->
            compose.runOnIdle {
                assertEquals(1, shown)
                assertEquals(before, compositions)
            }
        }
        compose.runOnIdle { progress.floatValue = 0f }
        compose.runOnIdle { assertEquals(3, shown) }
    }
}
