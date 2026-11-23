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

enum class UpdateInterval(val duration: Duration) { Six(6.hours), Twelve(12.hours), TwentyFour(24.hours), FortyEight(48.hours) }

enum class ThemeMode { System, Light, Dark }

enum class ThemeAccent { Default, Blue, Purple, Orange, Pink }

data class ThemeAppearance(val mode: ThemeMode, val followWallpaper: Boolean, val accent: ThemeAccent)

/** App preferences only; the engine still owns the subscriptions and the platform owns app locales. */
class AppSettings internal constructor(private val store: DataStore<Preferences>) : DropNoticeSettings, SubscriptionUpdateSettings {
    override val notifyOnDrop = preference { it[NOTIFY_ON_DROP] ?: true }
    override val subscriptionUpdate = preference { prefs ->
        val duration = prefs[UPDATE_HOURS]?.hours
        val interval = UpdateInterval.entries.firstOrNull { it.duration == duration } ?: UpdateInterval.Twelve
        SubscriptionUpdate(prefs[AUTO_UPDATE] ?: true, interval)
    }
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

    override suspend fun setUpdateInterval(interval: UpdateInterval) = set(UPDATE_HOURS, interval.duration.inWholeHours.toInt())

    suspend fun setThemeMode(mode: ThemeMode) = set(THEME_MODE, mode.name)

    suspend fun setFollowWallpaper(enabled: Boolean) = set(FOLLOW_WALLPAPER, enabled)

    suspend fun setAccent(accent: ThemeAccent) = set(ACCENT, accent.name)

    companion object {
        private val NOTIFY_ON_DROP = booleanPreferencesKey("notify_on_drop")
        private val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        private val UPDATE_HOURS = intPreferencesKey("update_hours")
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
