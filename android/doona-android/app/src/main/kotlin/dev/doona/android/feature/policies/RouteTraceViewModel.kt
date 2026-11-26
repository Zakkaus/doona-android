package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.vpn.Ipv4Prefix
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.TunRoutes
import dev.doona.android.vpn.honk.Group
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkRules
import dev.doona.android.vpn.honk.HonkTrace
import dev.doona.android.vpn.honk.HonkTraceInput
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeRouting
import dev.doona.android.vpn.honk.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What 路由測試 shows: the destination as typed, honk's answer for it, and why the last trace failed ([failure]). */
@Immutable
internal data class RouteTraceUiState(
    val query: RvTraceQuery = RvTraceQuery("", DEFAULT_PORT),
    val result: RvTraceResult? = null,
    val failure: ErrorCode? = null,
)

/**
 * 路由測試 on honk's native API: 測試 asks `POST /routing/trace` how the running generation routes the destination, then
 * names the deciding rule by its file and line from `GET /rules` and `GET /config` of that same generation, and the node
 * the target group uses from the catalog.
 */
internal class RouteTraceViewModel(private val api: NativeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val state = MutableStateFlow(RouteTraceUiState())
    private var running: Job? = null

    val uiState: StateFlow<RouteTraceUiState> = state.asStateFlow()

    /** A changed destination drops the answer, which belonged to the one before. */
    fun query(query: RvTraceQuery) = state.update { if (it.query == query) it else RouteTraceUiState(query) }

    fun trace() {
        val query = state.value.query
        running?.cancel()
        running = viewModelScope.launch {
            try {
                val result = withContext(io) { read(query) }
                state.update { if (it.query == query) it.copy(result = result, failure = null) else it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { if (it.query == query) it.copy(result = null, failure = error.errorCode()) else it }
            }
        }
    }

    private fun read(query: RvTraceQuery): RvTraceResult {
        val routing = NativeRouting(api)
        val input = traceInput(query)
        val trace = routing.trace(input)
        // Rule ids hold within one generation: a reload between the two reads would name another rule.
        val rules = routing.rules().takeIf { it.generationId == trace.generationId } ?: throw EngineException(ErrorCode.Busy)
        val catalog = NativeCatalog(api)
        return rvTraceResult(trace, rules, NativeConfig(api).config(), catalog.groups(), catalog.nodes().associate { it.id to it.name }, input)
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { RouteTraceViewModel(api) }
        }
    }
}

/** An address typed as the host goes as `dst_ip` (IPv6 with or without brackets), anything else as `domain`. */
internal fun traceInput(query: RvTraceQuery): HonkTraceInput {
    val host = query.host.trim().removePrefix("[").removeSuffix("]")
    val network = if (query.proto == RvTraceProto.Udp) Transport.Udp else Transport.Tcp
    val port = query.port.toInt()
    val isAddress = ':' in host || runCatching { Ipv4Prefix.parseAddress(host) }.isSuccess
    return if (isAddress) HonkTraceInput(network, port, dstIp = host) else HonkTraceInput(network, port, domain = host)
}

/**
 * The page's answer from honk's [trace] of [input]: the first rule that matched, at its source line ([config] holds the
 * text); a rule honk could not decide before it (it needs an input the trace lacks, such as the app) as the skipped
 * warning; the target, and the node a target group uses for [input]'s transport. Only IPv4 destinations the tun routes
 * pass through the VPN; a domain is taken to.
 */
internal fun rvTraceResult(
    trace: HonkTrace,
    rules: HonkRules,
    config: HonkConfig,
    groups: List<Group>,
    nodeNames: Map<String, String>,
    input: HonkTraceInput,
): RvTraceResult {
    val evaluation = trace.evaluations.first()
    val byId = rules.rules.associateBy { it.id }
    val winnerAt = evaluation.rules.indexOfFirst { it.result == MATCHED }
    val winner = evaluation.rules.getOrNull(winnerAt)?.let { byId[it.ruleId] } ?: throw EngineException(ErrorCode.Internal)
    val skipped = evaluation.rules.take(winnerAt).firstOrNull { it.result == INDETERMINATE }?.let { byId[it.ruleId]?.source }
    val sources = config.sources.associateBy { it.id }
    val at = winner.source
    val path = at?.let { sources[it.sourceId]?.path ?: it.file }.orEmpty()
    val text = at?.let { sources[it.sourceId]?.content?.lines()?.getOrNull(it.line - 1)?.trim() } ?: winner.expression
    val target = evaluation.outbound ?: winner.outbound
    val group = groups.firstOrNull { it.name == target }
    val selection = group?.runtime?.selection?.let { if (input.network == Transport.Udp) it.udp else it.tcp }
    return RvTraceResult(
        source = path,
        line = at?.line ?: 0,
        text = text,
        target = target,
        node = selection?.let { nodeNames[it.resolvedLeafNodeId ?: it.memberId] },
        fallback = winner.isFallback,
        captured = input.dstIp?.let(::tunCarries) ?: true,
        skipped = skipped?.let { (sources[it.sourceId]?.path ?: it.file) to it.line },
    )
}

/** Whether the tun routes [address]: IPv4 outside the LAN prefixes it leaves out; it routes no IPv6. */
private fun tunCarries(address: String): Boolean {
    if (':' in address) return false
    val ip = Ipv4Prefix.parseAddress(address)
    return TunRoutes.IPV4.any { ip in it }
}

private const val MATCHED = "matched"
private const val INDETERMINATE = "indeterminate"
private const val DEFAULT_PORT = "443"
