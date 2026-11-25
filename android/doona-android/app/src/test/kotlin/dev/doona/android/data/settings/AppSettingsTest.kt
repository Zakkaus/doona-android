package dev.doona.android.data.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun aStored48HourIntervalReadsAs24Hours() = runBlocking {
        val file = folder.root.resolve("app_settings.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = AppSettings.store(CoroutineScope(job + Dispatchers.IO)) { file }
            // What a build that still offered 48 hours wrote.
            store.edit { it[intPreferencesKey("update_hours")] = 48 }
            assertEquals(UpdateInterval.TwentyFour, AppSettings(store).subscriptionUpdate.first().interval)
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun theIntervalsAreThoseOfTheIntervalPage() {
        assertEquals(listOf(1L, 6L, 12L, 24L), UpdateInterval.entries.map { it.duration.inWholeHours })
    }
}
