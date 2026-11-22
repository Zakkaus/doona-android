package dev.doona.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The per-app policy, the only setting the app stores itself: the core's `state.json` owns mode, subscriptions and
 * selections (ARCH D7). An unreadable or corrupt file reads as the default policy, [AppPolicy.All].
 */
class PerAppSettings(private val store: DataStore<Preferences>) {
    val perApp: Flow<PerApp> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::decode)
        .distinctUntilChanged()

    /** Replaces the policy with [transform] of the stored one in a single atomic edit, and returns the result. */
    suspend fun update(transform: (PerApp) -> PerApp): PerApp = decode(
        store.edit { prefs ->
            val next = transform(decode(prefs))
            prefs[POLICY] = next.policy.name
            prefs[PACKAGES] = next.packages
        },
    )

    companion object {
        private val POLICY = stringPreferencesKey("policy")
        private val PACKAGES = stringSetPreferencesKey("packages")

        /** Call once per process: DataStore allows one instance per file. */
        fun create(context: Context) = PerAppSettings(
            PreferenceDataStoreFactory.create(corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }) {
                context.preferencesDataStoreFile("per_app")
            },
        )

        private fun decode(prefs: Preferences) = PerApp(
            policy = AppPolicy.entries.firstOrNull { it.name == prefs[POLICY] } ?: AppPolicy.All,
            packages = prefs[PACKAGES].orEmpty(),
        )
    }
}
