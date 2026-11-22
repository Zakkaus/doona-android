package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The import in flight and how the last one ended. [imported] counts the ones that succeeded, so a screen that watches
 * it sees a success it did not start; [lastId] names the latest import started, which a screen compares with the one it
 * started itself; [error] stays until dismissed or until the next import starts.
 */
@Immutable
data class ImportState(val busy: Boolean = false, val error: ErrorCode? = null, val imported: Int = 0, val lastId: Long = 0)

/**
 * The one subscription import in flight, kept above the screens that add one: the core's fetch cannot be cancelled, so
 * a page that is left during a slow import must neither lose it nor let the page it returns to start a second one that
 * also commits. The import runs in [scope], which outlives every screen; the form's text stays with its screen.
 */
class SubscriptionImports(private val engine: Engine, private val scope: CoroutineScope) {
    private val current = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = current.asStateFlow()

    /**
     * Starts importing [url] and returns true, or does nothing and returns false while an import is running. [claimed]
     * gets the import's id before the import can end, so the caller knows which success is its own.
     */
    fun start(url: String, name: String?, claimed: (Long) -> Unit = {}): Boolean {
        val id = claim() ?: return false
        claimed(id)
        scope.launch {
            val failure = engine.addSubscription(url, name).exceptionOrNull()
            current.update { it.copy(busy = false, error = failure?.errorCode(), imported = it.imported + if (failure == null) 1 else 0) }
        }
        return true
    }

    fun dismissError() = current.update { it.copy(error = null) }

    private fun claim(): Long? {
        while (true) {
            val seen = current.value
            if (seen.busy) return null
            val id = seen.lastId + 1
            if (current.compareAndSet(seen, seen.copy(busy = true, error = null, lastId = id))) return id
        }
    }
}

/** An import holder for [engine] that lives as long as the composition: for a host that has no app-wide one. */
@Composable
fun rememberSubscriptionImports(engine: Engine): SubscriptionImports {
    val scope = rememberCoroutineScope()
    return remember(engine, scope) { SubscriptionImports(engine, scope) }
}
