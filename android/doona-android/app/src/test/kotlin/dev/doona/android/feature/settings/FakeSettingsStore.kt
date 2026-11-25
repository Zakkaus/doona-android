package dev.doona.android.feature.settings

import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import kotlinx.coroutines.flow.MutableStateFlow

/** A [SettingsStore] in memory: each set lands in its flow, and [languages] stands for Android's per-app locale list. */
internal class FakeSettingsStore(
    notifyOnDrop: Boolean = true,
    autoUpdate: Boolean = true,
    updateHours: Int = 12,
    appearance: RvAppearance = RvAppearance(RvThemeMode.System, followWallpaper = false, accent = 0),
    tileAdded: Boolean = true,
    performanceMode: Boolean = false,
    override val shippedGeoDate: String = "2026-11-23",
    var languages: String = "",
) : SettingsStore {
    override val notifyOnDrop = MutableStateFlow(notifyOnDrop)
    override val autoUpdate = MutableStateFlow(autoUpdate)
    override val updateHours = MutableStateFlow(updateHours)
    override val updateIntervals = UpdateInterval.entries.map { it.hours }
    override val appearance = MutableStateFlow(appearance)
    override val tileAdded = MutableStateFlow(tileAdded)
    override val performanceMode = MutableStateFlow(performanceMode)
    private val started = performanceMode

    override suspend fun setAppearance(appearance: RvAppearance) {
        this.appearance.value = appearance
    }

    override fun languageTags(): String = languages

    override fun setLanguageTags(tags: String) {
        languages = tags
    }

    override suspend fun setNotifyOnDrop(enabled: Boolean) {
        notifyOnDrop.value = enabled
    }

    override suspend fun setAutoUpdate(enabled: Boolean) {
        autoUpdate.value = enabled
    }

    override suspend fun setUpdateHours(hours: Int) {
        require(hours in updateIntervals) { "no update interval of $hours h" }
        updateHours.value = hours
    }

    override suspend fun performanceAtStart(): Boolean = started

    override suspend fun setPerformanceMode(enabled: Boolean) {
        performanceMode.value = enabled
    }
}
