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
 * it sees a success it did not start; [error] stays until dismissed or until the next import starts.
 */
@Immutable
data class ImportState(val busy: Boolean = false, val error: ErrorCode? = null, val imported: Int = 0)

/**
 * The one subscription import in flight, kept above the screens that add one: the core's fetch cannot be cancelled, so
 * a page that is left during a slow import must neither lose it nor let the page it returns to start a second one that
 * also commits. The import runs in [scope], which outlives every screen; the form's text stays with its screen.
 */
class SubscriptionImports(private val engine: Engine, private val scope: CoroutineScope) {
    private val current = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = current.asStateFlow()

    /** Starts importing [url], unless an import is running, which makes this a no-op. */
    fun start(url: String, name: String?) {
        if (!claim()) return
        scope.launch {
            val failure = engine.addSubscription(url, name).exceptionOrNull()
            current.update { it.copy(busy = false, error = failure?.errorCode(), imported = it.imported + if (failure == null) 1 else 0) }
        }
    }

    fun dismissError() = current.update { it.copy(error = null) }

    private fun claim(): Boolean {
        while (true) {
            val seen = current.value
            if (seen.busy) return false
            if (current.compareAndSet(seen, seen.copy(busy = true, error = null))) return true
        }
    }
}

/** An import holder for [engine] that lives as long as the composition: for a host that has no app-wide one. */
@Composable
fun rememberSubscriptionImports(engine: Engine): SubscriptionImports {
    val scope = rememberCoroutineScope()
    return remember(engine, scope) { SubscriptionImports(engine, scope) }
}
