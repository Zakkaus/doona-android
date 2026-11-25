package dev.doona.android.app

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.GeoUpdateInterval
import dev.doona.android.data.settings.ThemeAccent
import dev.doona.android.data.settings.ThemeAppearance
import dev.doona.android.data.settings.ThemeMode
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.feature.settings.ThemeViewModel
import dev.doona.android.vpn.QuickTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/** 設定's store writes in the app's scope, so a choice the user makes just before leaving a page is still stored. */
@RunWith(AndroidJUnit4::class)
class AppSettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Unconfined starts each write at once, as the app's main-thread scope does when a page calls from the main thread.
    private val writes = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val settings: AppSettings by lazy { AppSettings(AppSettings.store(io) { folder.root.resolve("app_settings.preferences_pb") }) }

    private val store: AppSettingsStore by lazy {
        val tile = QuickTile(QuickTile.store(io) { folder.root.resolve("quick_tile.preferences_pb") }, io)
        AppSettingsStore(ApplicationProvider.getApplicationContext(), settings, tile, writes)
    }

    @After
    fun stop() {
        writes.cancel()
        io.cancel()
    }

    private fun <T> Flow<T>.reaches(value: T): T = runBlocking { withTimeout(5.seconds) { first { it == value } } }

    @Test
    fun `a theme chosen just before 主題 closes is stored, the last choice last`() {
        val pages = ViewModelStore()
        val viewModel = ViewModelProvider.create(pages, ThemeViewModel.factory(store))[ThemeViewModel::class]
        viewModel.set(RvAppearance(RvThemeMode.Light, followWallpaper = false, accent = 1))
        viewModel.set(RvAppearance(RvThemeMode.Dark, followWallpaper = true, accent = 2))
        // Back at once: the page's view model is cleared, and its scope cancelled, before the edits are written.
        pages.clear()

        val chosen = ThemeAppearance(ThemeMode.Dark, followWallpaper = true, accent = ThemeAccent.entries[2])
        assertEquals(chosen, settings.appearance.reaches(chosen))
    }

    @Test
    fun `the other switches and the interval are stored the same way`() {
        store.setNotifyOnDrop(false)
        store.setAutoUpdate(false)
        store.setUpdateHours(36)
        store.setPerformanceMode(true)

        assertEquals(false, settings.notifyOnDrop.reaches(false))
        assertEquals(36, store.updateHours.reaches(36))
        assertEquals(false, store.autoUpdate.reaches(false))
        assertEquals(true, settings.performanceMode.reaches(true))
    }

    @Test
    fun `自動更新's choice is the interval GeoRefresh reads, each way`() {
        assertEquals(RvGeoInterval.Weekly, store.geoInterval.reaches(RvGeoInterval.Weekly))
        for ((chosen, stored) in listOf(RvGeoInterval.Off to GeoUpdateInterval.Off, RvGeoInterval.Daily to GeoUpdateInterval.Daily, RvGeoInterval.Weekly to GeoUpdateInterval.Weekly)) {
            store.setGeoInterval(chosen)
            assertEquals(stored, settings.geoUpdateInterval.reaches(stored))
            assertEquals(chosen, store.geoInterval.reaches(chosen))
        }
    }

    @Test
    fun `an interval out of range is refused to the caller`() {
        assertThrows(IllegalArgumentException::class.java) { store.setUpdateHours(0) }
    }
}
