package dev.doona.android.vpn

import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND
import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_BAD_COMPONENT
import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE
import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER
import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE
import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickTileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun stop() = scope.cancel()

    @Test
    fun anAddedTileHidesTheRowADismissedDialogKeepsItAndAnErrorFails() {
        assertEquals(QuickTileResult.Added, quickTileResult(TILE_ADD_REQUEST_RESULT_TILE_ADDED))
        assertEquals(QuickTileResult.Added, quickTileResult(TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED))
        assertEquals(QuickTileResult.Kept, quickTileResult(TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED))
        // A second tap while the system's dialog is up: the dialog answers for both.
        assertEquals(QuickTileResult.Kept, quickTileResult(TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS))
        listOf(
            TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND,
            TILE_ADD_REQUEST_ERROR_BAD_COMPONENT,
            TILE_ADD_REQUEST_ERROR_MISMATCHED_PACKAGE,
            TILE_ADD_REQUEST_ERROR_NOT_CURRENT_USER,
            TILE_ADD_REQUEST_ERROR_NO_STATUS_BAR_SERVICE,
            UNKNOWN,
        ).forEach { assertEquals("code $it", QuickTileResult.Failed, quickTileResult(it)) }
    }

    /** A store that answers after [delay], as a cold start's first read does. */
    private class SlowStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            delay(SLOW_MS)
            emit(emptyPreferences())
        }

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = emptyPreferences()
    }

    @Test
    fun theStateIsUnknownUntilTheStoreAnswersThenNotAdded() = runBlocking {
        val tile = QuickTile(SlowStore(), scope)
        assertNull("unknown, not a guess", tile.state.value)
        assertEquals(false, tile.state.first { it != null })
    }

    @Test
    fun theFlagStartsUnsetAndFollowsWhatTheTileReports() = runBlocking {
        val tile = QuickTile(QuickTile.store(scope) { folder.root.resolve("quick_tile.preferences_pb") }, scope)
        assertFalse(tile.added.first())
        tile.record(true).join()
        assertTrue(tile.added.first())
        tile.record(false).join()
        assertFalse(tile.added.first())
    }

    private companion object {
        /** A code a later Android might add. */
        const val UNKNOWN = 9_999
        const val SLOW_MS = 300L
    }
}
