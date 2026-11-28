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
import dev.doona.android.honk.HonkTraceInput
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.NativeRouting
import dev.doona.android.honk.Transport
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
 * the target group uses from the catalog. [carries] tells whether the tun carries a host, null when it is no address.
 */
internal class RouteTraceViewModel(
    private val api: NativeApi,
    private val carries: (String) -> Boolean?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
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
        val input = traceInput(query, carries)
        val trace = routing.trace(input)
        val evaluation = trace.evaluations.first()
        val uncertain = evaluation.rules.firstOrNull { it.result == INDETERMINATE }
        val target = evaluation.outbound
        val catalog = NativeCatalog(api)
        val group = target?.let { catalog.summaries().firstOrNull { it.name == target } }
        val selected = group?.let { catalog.group(it.id).runtime.selection }?.let { if (input.network == Transport.Udp) it.udp else it.tcp }
        val node = selected?.let { pick -> catalog.nodes().firstOrNull { it.id == (pick.resolvedLeafNodeId ?: pick.memberId) }?.name }
        if (uncertain != null || evaluation.decision == INDETERMINATE) {
            val at = uncertain?.let { rule ->
                routing.rules().takeIf { it.generationId == trace.generationId }?.rules?.firstOrNull { it.id == rule.ruleId }?.source
            }
            return RvTraceResult(
                "",
                0,
                "",
                target,
                node,
                captured = input.dstIp?.let(carries),
                skipped = at?.let { it.file to it.line },
                missingInputs = evaluation.missingInputs,
            )
        }
        val matched = evaluation.rules.firstOrNull { it.result == MATCHED } ?: throw EngineException(ErrorCode.Internal)
        val rules = routing.rules()
        if (rules.generationId != trace.generationId) throw EngineException(ErrorCode.Busy)
        val winner = rules.rules.firstOrNull { it.id == matched.ruleId } ?: throw EngineException(ErrorCode.Internal)
        val at = winner.source
        val source = at?.let {
            val config = NativeConfig(api).config()
            if (config.generationId != trace.generationId) throw EngineException(ErrorCode.Busy)
            config.sources.firstOrNull { file -> file.id == it.sourceId }
        }
        val line = at?.line ?: 0
        return RvTraceResult(
            source?.path.orEmpty(),
            line,
            source?.content?.lineSequence()?.elementAtOrNull(line - 1)?.trim() ?: winner.expression,
            target,
            node,
            winner.isFallback,
            input.dstIp?.let(carries),
        )
    }

    companion object {
        fun factory(api: NativeApi, carries: (String) -> Boolean?): ViewModelProvider.Factory = viewModelFactory {
            initializer { RouteTraceViewModel(api, carries) }
        }
    }
}

/** An address typed as the host goes as `dst_ip` (IPv6 with or without brackets), anything else as `domain`. */
internal fun traceInput(query: RvTraceQuery, carries: (String) -> Boolean?): HonkTraceInput {
    val host = query.host.trim().removePrefix("[").removeSuffix("]")
    val network = if (query.proto == RvTraceProto.Udp) Transport.Udp else Transport.Tcp
    val port = query.port.toInt()
    return if (carries(host) != null) HonkTraceInput(network, port, dstIp = host) else HonkTraceInput(network, port, domain = host)
}

private const val MATCHED = "matched"
private const val INDETERMINATE = "indeterminate"
private const val DEFAULT_PORT = "443"
