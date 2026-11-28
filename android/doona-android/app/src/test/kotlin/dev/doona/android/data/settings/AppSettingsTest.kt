package dev.doona.android.data.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun editsSurviveReopeningTheStore() = runBlocking {
        val file = folder.root.resolve("app_settings.preferences_pb")
        val writer = SupervisorJob()
        try {
            val settings = AppSettings(AppSettings.store(CoroutineScope(writer + Dispatchers.IO)) { file })
            settings.setNotifyOnDrop(false)
            settings.setUpdateInterval(UpdateInterval.ofHours(36))
            settings.setAutoUpdate(false)
            settings.setAppearance(ThemeAppearance(ThemeMode.Light, followWallpaper = true, accent = ThemeAccent.Purple))
            assertFalse(settings.performanceMode.first())
            settings.setPerformanceMode(true)
            assertEquals(ThemeMode.Light, settings.themeMode.first())
        } finally {
            writer.cancelAndJoin()
        }
        val reader = SupervisorJob()
        try {
            val settings = AppSettings(AppSettings.store(CoroutineScope(reader + Dispatchers.IO)) { file })
            assertFalse(settings.notifyOnDrop.first())
            assertEquals(SubscriptionUpdate(false, UpdateInterval.ofHours(36)), settings.subscriptionUpdate.first())
            assertEquals(ThemeAppearance(ThemeMode.Light, true, ThemeAccent.Purple), settings.appearance.first())
            assertTrue(settings.performanceMode.first())
        } finally {
            reader.cancelAndJoin()
        }
    }

    @Test
    fun thePresetsAreThoseOfTheIntervalPage() {
        assertEquals(listOf(1L, 6L, 12L, 24L), UpdateInterval.PRESETS.map { it.duration.inWholeHours })
        assertEquals(12, UpdateInterval.DEFAULT.hours)
    }

    @Test
    fun `a typed interval is any whole count of hours from one to a week, and others are refused`() {
        assertEquals(1..168, UpdateInterval.RANGE)
        for (hours in listOf(1, 48, 168)) assertEquals(hours.toLong(), UpdateInterval.ofHours(hours).duration.inWholeHours)
        for (hours in listOf(0, -1, 169)) assertThrows(IllegalArgumentException::class.java) { UpdateInterval.ofHours(hours) }
    }
}
