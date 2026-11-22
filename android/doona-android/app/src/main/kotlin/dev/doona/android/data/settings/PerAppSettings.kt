package dev.doona.android.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The per-app policy, the only setting the app stores itself: the core's `state.json` owns mode, subscriptions and
 * selections (ARCH D7). Only a file that never held a policy reads as [AppPolicy.All]: an unreadable or corrupt file, or
 * a policy this version does not know, fails the read, because falling back to All would capture apps the user excluded.
 */
class PerAppSettings(private val store: DataStore<Preferences>) {
    /** Throws an `IOException` when the stored policy cannot be read. */
    val perApp: Flow<PerApp> = store.data.map(::decode).distinctUntilChanged()

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
            // No corruption handler: replacing a corrupt file with an empty one would read as All.
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("per_app") },
        )

        private fun decode(prefs: Preferences) = PerApp(
            policy = prefs[POLICY]?.let { name ->
                AppPolicy.entries.firstOrNull { it.name == name } ?: throw CorruptionException("unknown per-app policy")
            } ?: AppPolicy.All,
            packages = prefs[PACKAGES].orEmpty(),
        )
    }
}
