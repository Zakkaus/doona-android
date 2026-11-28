package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.localnetwork.LocalNetworkAccess
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress

/** What an import was asked to fetch: the link, and the name and User-Agent typed with it (null for the defaults). */
@Immutable
class ImportRequest(val url: String, val name: String?, val userAgent: String?) {
    // The link and the User-Agent stay out of logs (D21).
    override fun toString() = "ImportRequest([redacted])"
}

/**
 * The import in flight and how the last one ended. [lastId] names the latest import started, which a page compares
 * with the one it follows; [request] is what that import fetches; [error] is how it failed and [added] the
 * subscription it added, once it ended.
 */
@Immutable
internal data class ImportState(
    val busy: Boolean = false,
    val error: ErrorCode? = null,
    val lastId: Long = 0,
    val request: ImportRequest? = null,
    val added: RvSubscription? = null,
)

/**
 * The one subscription import in flight, kept above the pages that add one: honk's add cannot be cancelled, so a page
 * that is left during a slow import must neither lose it nor let the page it returns to start a second one that also
 * commits. The import adds a provider to honk on [io] and runs in [scope], which outlives every page. honk requires a
 * name, so a request with none is named by its link's host; a name already in use fails with
 * [ErrorCode.InvalidArgument]. honk tells a fetch the local network refused from no other failed fetch, so a link on a
 * local address ([onLocalNetwork]) first asks [localNetwork] for access (ARCH D26) and, refused, fails with
 * [ErrorCode.LocalNetwork] without calling honk.
 */
internal class SubscriptionImports(
    private val api: NativeApi,
    private val localNetwork: LocalNetworkAccess,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val current = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = current.asStateFlow()

    /** Starts importing [request] and returns its id, or does nothing and returns null while an import is running. */
    fun start(request: ImportRequest): Long? {
        val id = claim(request) ?: return null
        scope.launch {
            val result = try {
                Result.success(add(request))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            current.update { state ->
                state.copy(busy = false, error = result.exceptionOrNull()?.errorCode(), added = result.getOrNull()?.let { rvSubscription(it, request.userAgent) })
            }
        }
        return id
    }

    private suspend fun add(request: ImportRequest): Provider {
        if (onLocalNetwork(request.url) && !localNetwork.allowed()) throw EngineException(ErrorCode.LocalNetwork)
        return withContext(io) { NativeCatalog(api).addProvider(request.name ?: subscriptionHost(request.url), request.url, request.userAgent) }
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

/**
 * An import holder on [api] that lives as long as the composition: for a host that has no app-wide one, and so no
 * local network prompt; it adds a link on the local network as if access were allowed.
 */
@Composable
internal fun rememberSubscriptionImports(api: NativeApi): SubscriptionImports {
    val scope = rememberCoroutineScope()
    return remember(api, scope) { SubscriptionImports(api, LocalNetworkAccess { true }, scope) }
}

/**
 * Whether [url]'s host is an address Android counts as on the local network, as the core judged the addresses it
 * dialled: private, link-local, shared (100.64.0.0/10), multicast or broadcast IPv4; unique local, link-local or
 * multicast IPv6. Only an address literal is judged; a name is honk's to look up.
 */
internal fun onLocalNetwork(url: String): Boolean {
    val host = subscriptionHost(url).removeSurrounding("[", "]")
    if (host.isEmpty() || !(':' in host || host.all { it.isDigit() || it == '.' })) return false
    // A literal: no lookup is made.
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    val bytes = address.address.map { it.toInt() and 0xff }
    if (address.isLinkLocalAddress || address.isMulticastAddress) return true
    return if (address is Inet4Address) {
        address.isSiteLocalAddress || (bytes[0] == 100 && (bytes[1] and 0xc0) == 64) || bytes.all { it == 0xff }
    } else {
        (bytes[0] and 0xfe) == 0xfc
    }
}
