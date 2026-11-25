package dev.doona.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

interface DropNoticeSettings {
    val notifyOnDrop: Flow<Boolean>

    suspend fun setNotifyOnDrop(enabled: Boolean)
}

interface SubscriptionUpdateSettings {
    val subscriptionUpdate: Flow<SubscriptionUpdate>

    suspend fun setAutoUpdate(enabled: Boolean)

    suspend fun setUpdateInterval(interval: UpdateInterval)
}

data class SubscriptionUpdate(val enabled: Boolean, val interval: UpdateInterval)

/**
 * The subscription update intervals the 更新間隔 page offers (owner, 2026-11-25: storage follows the approved page), the
 * one list of them: the page reads [hours] of each through 設定's store.
 */
enum class UpdateInterval(val duration: Duration) {
    One(1.hours),
    Six(6.hours),
    Twelve(12.hours),
    TwentyFour(24.hours),
    ;

    val hours: Int get() = duration.inWholeHours.toInt()

    companion object {
        /** The interval of [hours]; any other count is a caller's error, not a choice to drop. */
        fun ofHours(hours: Int): UpdateInterval = requireNotNull(entries.firstOrNull { it.hours == hours }) { "no update interval of $hours h" }
    }
}

enum class GeoUpdateInterval(val duration: Duration?) { Off(null), Daily(1.days), Weekly(7.days) }

internal data class GeoUpdateSchedule(val interval: GeoUpdateInterval, val lastSuccessEpochMs: Long?)

enum class ThemeMode { System, Light, Dark }

enum class ThemeAccent { Default, Blue, Purple, Orange, Pink }

data class ThemeAppearance(val mode: ThemeMode, val followWallpaper: Boolean, val accent: ThemeAccent)

/** App preferences only; the engine still owns the subscriptions and the platform owns app locales. */
class AppSettings internal constructor(private val store: DataStore<Preferences>) : DropNoticeSettings, SubscriptionUpdateSettings {
    override val notifyOnDrop = preference { it[NOTIFY_ON_DROP] ?: true }
    override val subscriptionUpdate = preference { prefs ->
        val interval = UpdateInterval.entries.firstOrNull { it.hours == prefs[UPDATE_HOURS] } ?: UpdateInterval.Twelve
        SubscriptionUpdate(prefs[AUTO_UPDATE] ?: true, interval)
    }
    internal val geoUpdateSchedule = preference { prefs ->
        val name = prefs[GEO_UPDATE_INTERVAL]
        val interval = GeoUpdateInterval.entries.firstOrNull { it.name == name } ?: GeoUpdateInterval.Weekly
        GeoUpdateSchedule(interval, prefs[GEO_LAST_SUCCESS])
    }
    val geoUpdateInterval = geoUpdateSchedule.map { it.interval }.distinctUntilChanged()
    val themeMode = preference(::mode)
    val appearance = preference { prefs ->
        val name = prefs[ACCENT]
        val accent = ThemeAccent.entries.firstOrNull { it.name == name } ?: ThemeAccent.Default
        ThemeAppearance(mode(prefs), prefs[FOLLOW_WALLPAPER] ?: false, accent)
    }

    private fun <T> preference(read: (Preferences) -> T): Flow<T> = store.data.map(read).distinctUntilChanged()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    override suspend fun setNotifyOnDrop(enabled: Boolean) = set(NOTIFY_ON_DROP, enabled)

    override suspend fun setAutoUpdate(enabled: Boolean) = set(AUTO_UPDATE, enabled)

    override suspend fun setUpdateInterval(interval: UpdateInterval) = set(UPDATE_HOURS, interval.hours)

    suspend fun setGeoUpdateInterval(interval: GeoUpdateInterval) = set(GEO_UPDATE_INTERVAL, interval.name)

    internal suspend fun recordGeoUpdateSuccess(epochMs: Long) = set(GEO_LAST_SUCCESS, epochMs)

    suspend fun setThemeMode(mode: ThemeMode) = set(THEME_MODE, mode.name)

    suspend fun setFollowWallpaper(enabled: Boolean) = set(FOLLOW_WALLPAPER, enabled)

    suspend fun setAccent(accent: ThemeAccent) = set(ACCENT, accent.name)

    companion object {
        private val NOTIFY_ON_DROP = booleanPreferencesKey("notify_on_drop")
        private val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val UPDATE_HOURS = intPreferencesKey("update_hours")
        private val GEO_UPDATE_INTERVAL = stringPreferencesKey("geo_update_interval")
        private val GEO_LAST_SUCCESS = longPreferencesKey("geo_last_success_epoch_ms")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val FOLLOW_WALLPAPER = booleanPreferencesKey("follow_wallpaper")
        private val ACCENT = stringPreferencesKey("accent")

        private var instance: AppSettings? = null

        @Synchronized
        fun get(context: Context): AppSettings {
            val app = context.applicationContext
            return instance ?: AppSettings(store { app.preferencesDataStoreFile("app_settings") }).also { instance = it }
        }

        internal fun store(scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()), file: () -> File): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                storage = FileStorage(PreferencesFileSerializer, produceFile = file),
                scope = scope,
            )

        private fun mode(prefs: Preferences): ThemeMode {
            val name = prefs[THEME_MODE]
            return ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.System
        }
    }
}
