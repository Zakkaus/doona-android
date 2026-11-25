package dev.doona.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * A preferences store in memory for [AppSettings] in tests: no file and no background thread, so a read or a write
 * finishes on the caller's thread. While [failing], every read and write throws an [IOException], as a full or broken
 * disk does.
 */
class MemoryPreferences : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())

    @Volatile
    var failing = false

    override val data: Flow<Preferences> = flow {
        if (failing) throw IOException("settings unreadable")
        emitAll(state)
    }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        if (failing) throw IOException("settings unwritable")
        return transform(state.value).also { state.value = it }
    }
}
