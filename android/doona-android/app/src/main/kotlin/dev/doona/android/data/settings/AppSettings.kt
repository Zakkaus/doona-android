package dev.doona.android.data.settings

import android.content.Context
import androidx.annotation.VisibleForTesting
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
 * How often the subscriptions update, in whole [hours]: one of [PRESETS], the 更新間隔 page's radios (owner,
 * 2026-11-25: storage follows the approved page), or a count in [RANGE] typed in its 自訂 sheet.
 */
@JvmInline
value class UpdateInterval private constructor(val hours: Int) {
    val duration: Duration get() = hours.hours

    companion object {
        /** The counts 自訂 takes: an hour, the shortest preset, to a week. */
        val RANGE = 1..168

        val PRESETS = listOf(1, 6, 12, 24).map(::UpdateInterval)

        val DEFAULT = UpdateInterval(12)

        /** The interval of [hours]; a count outside [RANGE] is a caller's error, not a choice to drop. */
        fun ofHours(hours: Int): UpdateInterval {
            require(hours in RANGE) { "no update interval of $hours h" }
            return UpdateInterval(hours)
        }
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
        val interval = prefs[UPDATE_HOURS]?.takeIf { it in UpdateInterval.RANGE }?.let(UpdateInterval::ofHours) ?: UpdateInterval.DEFAULT
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

    /** More core threads for more throughput over several flows, at the cost of CPU and power; read when the core opens. */
    val performanceMode = preference { it[PERFORMANCE_MODE] ?: false }

    /** Whether doona has asked for the notification permission, explained or not: it asks once, after a connection. */
    val notifyAsked = preference { it[NOTIFY_ASKED] ?: false }

    private fun <T> preference(read: (Preferences) -> T): Flow<T> = store.data.map(read).distinctUntilChanged()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    override suspend fun setNotifyOnDrop(enabled: Boolean) = set(NOTIFY_ON_DROP, enabled)

    override suspend fun setAutoUpdate(enabled: Boolean) = set(AUTO_UPDATE, enabled)

    override suspend fun setUpdateInterval(interval: UpdateInterval) = set(UPDATE_HOURS, interval.hours)

    suspend fun setGeoUpdateInterval(interval: GeoUpdateInterval) = set(GEO_UPDATE_INTERVAL, interval.name)

    internal suspend fun recordGeoUpdateSuccess(epochMs: Long) = set(GEO_LAST_SUCCESS, epochMs)

    /** Stores 主題's three choices in one edit, so [appearance] never shows a mix of the old and the new. */
    suspend fun setAppearance(appearance: ThemeAppearance) {
        store.edit {
            it[THEME_MODE] = appearance.mode.name
            it[FOLLOW_WALLPAPER] = appearance.followWallpaper
            it[ACCENT] = appearance.accent.name
        }
    }

    suspend fun setPerformanceMode(enabled: Boolean) = set(PERFORMANCE_MODE, enabled)

    suspend fun recordNotifyAsked() = set(NOTIFY_ASKED, true)

    companion object {
        private val NOTIFY_ON_DROP = booleanPreferencesKey("notify_on_drop")
        private val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val UPDATE_HOURS = intPreferencesKey("update_hours")
        private val GEO_UPDATE_INTERVAL = stringPreferencesKey("geo_update_interval")
        private val GEO_LAST_SUCCESS = longPreferencesKey("geo_last_success_epoch_ms")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val FOLLOW_WALLPAPER = booleanPreferencesKey("follow_wallpaper")
        private val ACCENT = stringPreferencesKey("accent")
        private val PERFORMANCE_MODE = booleanPreferencesKey("performance_mode")
        private val NOTIFY_ASKED = booleanPreferencesKey("notify_asked")

        private var instance: AppSettings? = null
        private var owner: Context? = null

        /**
         * The settings of [context]'s application, one instance for it. Keyed by the application, so a new one, as
         * each Robolectric test makes over its own files, gets its own store rather than the first test's.
         */
        @Synchronized
        fun get(context: Context): AppSettings {
            val app = context.applicationContext
            instance?.takeIf { owner === app }?.let { return it }
            return AppSettings(store { app.preferencesDataStoreFile("app_settings") }).also {
                owner = app
                instance = it
            }
        }

        /** Makes [settings] the ones [get] returns for [context]'s application; tests give a store of their own. */
        @VisibleForTesting
        @Synchronized
        internal fun use(context: Context, settings: AppSettings) {
            owner = context.applicationContext
            instance = settings
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
