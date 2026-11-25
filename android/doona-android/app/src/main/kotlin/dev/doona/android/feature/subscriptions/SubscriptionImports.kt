package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What an import was asked to fetch: the link, and the name and User-Agent typed with it (null for the defaults). */
@Immutable
class ImportRequest(val url: String, val name: String?, val userAgent: String?) {
    // The link and the User-Agent stay out of logs (D21).
    override fun toString() = "ImportRequest([redacted])"
}

/**
 * The import in flight and how the last one ended. [lastId] names the latest import started, which a page compares
 * with the one it follows; [request] is what that import fetches; [error] is how it failed and [added] what it added,
 * once it ended.
 */
@Immutable
data class ImportState(
    val busy: Boolean = false,
    val error: ErrorCode? = null,
    val lastId: Long = 0,
    val request: ImportRequest? = null,
    val added: Subscription? = null,
)

/**
 * The one subscription import in flight, kept above the pages that add one: the core's fetch cannot be cancelled, so
 * a page that is left during a slow import must neither lose it nor let the page it returns to start a second one that
 * also commits. The import runs in [scope], which outlives every page.
 */
class SubscriptionImports(private val engine: Engine, private val scope: CoroutineScope) {
    private val current = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = current.asStateFlow()

    /** Starts importing [request] and returns its id, or does nothing and returns null while an import is running. */
    fun start(request: ImportRequest): Long? {
        val id = claim(request) ?: return null
        scope.launch {
            val result = engine.addSubscription(request.url, request.name, request.userAgent)
            current.update { it.copy(busy = false, error = result.exceptionOrNull()?.errorCode(), added = result.getOrNull()) }
        }
        return id
    }

    private fun claim(request: ImportRequest): Long? {
        while (true) {
            val seen = current.value
            if (seen.busy) return null
            val id = seen.lastId + 1
            if (current.compareAndSet(seen, ImportState(busy = true, lastId = id, request = request))) return id
        }
    }
}

/** An import holder for [engine] that lives as long as the composition: for a host that has no app-wide one. */
@Composable
fun rememberSubscriptionImports(engine: Engine): SubscriptionImports {
    val scope = rememberCoroutineScope()
    return remember(engine, scope) { SubscriptionImports(engine, scope) }
}
