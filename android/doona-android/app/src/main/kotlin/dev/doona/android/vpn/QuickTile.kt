package dev.doona.android.vpn

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.doona.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** What a request to add [ToggleTileService] came to: the tile is there, nothing changed, or the request failed. */
enum class QuickTileResult { Added, Kept, Failed }

/**
 * [StatusBarManager.requestAddTileService]'s result [code]: added or already added is [QuickTileResult.Added]; a
 * dismissed dialog, or one already showing for an earlier tap, is [QuickTileResult.Kept]; every error is
 * [QuickTileResult.Failed]. The codes are compile-time constants, so reading one the API 33 callback delivered needs
 * no API level (InlinedApi).
 */
@SuppressLint("InlinedApi")
fun quickTileResult(code: Int): QuickTileResult = when (code) {
    TILE_ADD_REQUEST_RESULT_TILE_ADDED, TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> QuickTileResult.Added
    TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED, TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> QuickTileResult.Kept
    else -> QuickTileResult.Failed
}

/**
 * Whether the quick settings tile is added, as far as the app has seen it: the system's answer to [request], and
 * [ToggleTileService]'s `onTileAdded` and `onTileRemoved`, which also cover a tile added or removed by hand. Android has
 * no call that asks, so a tile added before this was stored reads as not added until one of those reports it.
 */
class QuickTile(private val store: DataStore<Preferences>, private val scope: CoroutineScope) {
    val added: Flow<Boolean> = store.data.map { it[ADDED] ?: false }.distinctUntilChanged()

    /** Stores [added] on the process-wide scope, so a tile service destroyed right after its callback still saves it. */
    fun record(added: Boolean): Job = scope.launch { store.edit { it[ADDED] = added } }

    /**
     * Asks the system to add the tile: its dialog, over the app, which must be in the foreground. An added tile is
     * recorded before [onResult].
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun request(context: Context, onResult: (QuickTileResult) -> Unit) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, ToggleTileService::class.java),
            context.getString(R.string.app_name),
            Icon.createWithResource(context, R.drawable.ic_duck),
            context.mainExecutor,
        ) { code ->
            val result = quickTileResult(code)
            if (result == QuickTileResult.Added) record(true)
            onResult(result)
        }
    }

    companion object {
        private val ADDED = booleanPreferencesKey("added")

        /** Call once per process: DataStore allows one instance per file. */
        fun create(context: Context): QuickTile {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            return QuickTile(store(scope) { context.preferencesDataStoreFile("quick_tile") }, scope)
        }

        /** The store [create] builds; a damaged file reads as not added, which only offers the row again. */
        internal fun store(scope: CoroutineScope, file: () -> File): DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = file,
        )
    }
}
