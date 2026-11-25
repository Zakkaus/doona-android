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
            assertEquals(GeoUpdateInterval.Weekly, settings.geoUpdateInterval.first())
            settings.setGeoUpdateInterval(GeoUpdateInterval.Daily)
            settings.recordGeoUpdateSuccess(1234L)
            settings.setNotifyOnDrop(false)
            settings.setUpdateInterval(UpdateInterval.One)
            settings.setAutoUpdate(false)
            settings.setThemeMode(ThemeMode.Dark)
            settings.setFollowWallpaper(true)
            settings.setAccent(ThemeAccent.Purple)
            settings.setThemeMode(ThemeMode.Light)
            assertEquals(ThemeMode.Light, settings.themeMode.first())
        } finally {
            writer.cancelAndJoin()
        }
        val reader = SupervisorJob()
        try {
            val settings = AppSettings(AppSettings.store(CoroutineScope(reader + Dispatchers.IO)) { file })
            assertFalse(settings.notifyOnDrop.first())
            assertEquals(SubscriptionUpdate(false, UpdateInterval.One), settings.subscriptionUpdate.first())
            assertEquals(GeoUpdateSchedule(GeoUpdateInterval.Daily, 1234L), settings.geoUpdateSchedule.first())
            assertEquals(ThemeAppearance(ThemeMode.Light, true, ThemeAccent.Purple), settings.appearance.first())
        } finally {
            reader.cancelAndJoin()
        }
    }

    @Test
    fun theIntervalsAreThoseOfTheIntervalPage() {
        assertEquals(listOf(1L, 6L, 12L, 24L), UpdateInterval.entries.map { it.duration.inWholeHours })
    }

    @Test
    fun `an interval is found by its hours, and hours of no interval are refused`() {
        assertEquals(listOf(1, 6, 12, 24), UpdateInterval.entries.map { it.hours })
        for (interval in UpdateInterval.entries) assertEquals(interval, UpdateInterval.ofHours(interval.hours))
        assertThrows(IllegalArgumentException::class.java) { UpdateInterval.ofHours(48) }
    }
}
