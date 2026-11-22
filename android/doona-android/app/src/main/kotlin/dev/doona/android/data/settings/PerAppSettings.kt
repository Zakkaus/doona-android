package dev.doona.android.data.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The per-app policy, the only setting the app stores itself: the core's `state.json` owns mode, subscriptions and
 * selections (ARCH D7). Only a file that never held a policy reads as [AppPolicy.All]: an unreadable or corrupt file, or
 * a policy this version does not know, fails the read, because falling back to All would capture apps the user excluded.
 * A corrupt file is replaced with an [UNREADABLE] marker that fails every read the same way, while [update] still writes
 * over it, so the next save repairs the file without clearing app data.
 */
class PerAppSettings(private val store: DataStore<Preferences>) {
    /** Throws an `IOException` when the stored policy cannot be read. */
    val perApp: Flow<PerApp> = store.data.map(::decode).distinctUntilChanged()

    /**
     * Replaces the policy with [transform] of the stored one in a single atomic edit, and returns the result. A stored
     * policy that cannot be read is passed to [transform] as the default, so a save of a whole new policy repairs it.
     */
    suspend fun update(transform: (PerApp) -> PerApp): PerApp = decode(
        store.edit { prefs ->
            val stored = try {
                decode(prefs)
            } catch (_: CorruptionException) {
                PerApp()
            }
            val next = transform(stored)
            prefs[POLICY] = next.policy.name
            prefs[PACKAGES] = next.packages
        },
    )

    companion object {
        private val POLICY = stringPreferencesKey("policy")
        private val PACKAGES = stringSetPreferencesKey("packages")

        /** Not an [AppPolicy] name: a file holding it reads as an unknown policy. */
        private const val UNREADABLE = "Unreadable"

        /** Call once per process: DataStore allows one instance per file. */
        fun create(context: Context) = PerAppSettings(store { context.preferencesDataStoreFile("per_app") })

        /** The store [create] builds; tests pass their own [scope]. */
        internal fun store(scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()), file: () -> File) =
            PreferenceDataStoreFactory.create(
                // The marker, not an empty file: an empty file would read as All.
                corruptionHandler = ReplaceFileCorruptionHandler { preferencesOf(POLICY to UNREADABLE) },
                scope = scope,
                produceFile = file,
            )

        private fun decode(prefs: Preferences) = PerApp(
            policy = prefs[POLICY]?.let { name ->
                AppPolicy.entries.firstOrNull { it.name == name } ?: throw CorruptionException("unknown per-app policy")
            } ?: AppPolicy.All,
            packages = prefs[PACKAGES].orEmpty(),
        )
    }
}
