package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvListEdit
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.feature.policies.dnsRulesNaming
import dev.doona.android.feature.policies.honkUpstreams
import dev.doona.android.feature.policies.upstreamAdded
import dev.doona.android.feature.policies.upstreamRemoved
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the last change to the DNS servers did not land: rules still name the server, or honk refused it. */
internal sealed interface DnsServersFailure {
    data class Refused(val name: String, val rules: Int) : DnsServersFailure

    data class Remove(val name: String, val code: ErrorCode) : DnsServersFailure

    data class Create(val server: RvDnsServer, val code: ErrorCode) : DnsServersFailure
}

/** What DNS 伺服器 shows: the servers honk's files declare (null until read), the new server's sheet, the last [failure]. */
internal data class DnsServersUiState(
    val servers: List<RvDnsServer>? = null,
    val adding: RvDnsServer? = null,
    val failure: DnsServersFailure? = null,
    val readFailure: ErrorCode? = null,
)

/**
 * DNS 伺服器: the `dns.upstream` servers of honk's config, read on [io] at start, after each change and on 重試. 新增
 * adds a line to the file that declares the servers; a removal deletes the server's line, unless a running DNS rule
 * still names it. honk checks each file before it takes it.
 */
internal class DnsServersViewModel(private val api: NativeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val state = MutableStateFlow(DnsServersUiState())

    val uiState: StateFlow<DnsServersUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun retry() {
        viewModelScope.launch { load() }
    }

    fun adding(draft: RvDnsServer?) = state.update { it.copy(adding = draft) }

    fun create(server: RvDnsServer) = change(DnsServersFailure.Create(server, ErrorCode.Internal)) {
        val (source, text) = upstreamAdded(NativeConfig(api).config(), server.name, server.address) ?: throw EngineException(ErrorCode.InvalidArgument)
        NativeConfig(api).replace(source, text)
    }

    fun remove(name: String) = change(DnsServersFailure.Remove(name, ErrorCode.Internal)) {
        val config = NativeConfig(api).config()
        val upstream = honkUpstreams(config).firstOrNull { it.name == name } ?: return@change
        val rules = dnsRulesNaming(NativeRouting(api).dnsRules(), name)
        if (rules > 0) {
            state.update { it.copy(failure = DnsServersFailure.Refused(name, rules)) }
            return@change
        }
        NativeConfig(api).replace(upstream.source, upstreamRemoved(upstream))
    }

    private fun change(failed: DnsServersFailure, write: suspend () -> Unit) {
        state.update { it.copy(failure = null) }
        viewModelScope.launch {
            try {
                withContext(io) { write() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val code = error.errorCode()
                state.update {
                    it.copy(
                        failure = when (failed) {
                            is DnsServersFailure.Create -> failed.copy(code = code)
                            is DnsServersFailure.Remove -> failed.copy(code = code)
                            is DnsServersFailure.Refused -> failed
                        },
                    )
                }
            }
            load()
        }
    }

    private suspend fun load() {
        try {
            val servers = withContext(io) { honkUpstreams(NativeConfig(api).config()).map { RvDnsServer(it.name, it.address) } }
            state.update { it.copy(servers = servers, readFailure = null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            state.update { it.copy(readFailure = error.errorCode()) }
        }
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { DnsServersViewModel(api) }
        }
    }
}

/** DNS 伺服器 in the app, always editable as its approved samples are; 查看規則 on a refusal opens [onRules]. */
@Composable
internal fun DnsServersRoute(api: NativeApi, onBack: () -> Unit, onRules: () -> Unit) {
    val viewModel: DnsServersViewModel = viewModel(factory = DnsServersViewModel.factory(api))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val servers = state.servers ?: return RvSettingsLoading()
    RvDnsServersPage(
        servers,
        onBack,
        edit = RvListEdit({ viewModel.adding(RvDnsServer("", "")) }, viewModel::remove),
        banner = state.readFailure?.let { readBanner(it, viewModel::retry) } ?: state.failure?.let { failureBanner(it, viewModel, onRules) },
        adding = state.adding,
        onAdding = viewModel::adding,
        onCreate = viewModel::create,
    )
}

@Composable
private fun failureBanner(failure: DnsServersFailure, viewModel: DnsServersViewModel, onRules: () -> Unit): RvNotice = when (failure) {
    is DnsServersFailure.Refused -> rvDeleteRefusedBanner(failure.name, failure.rules, onRules)
    is DnsServersFailure.Remove -> changeBanner(stringResource(R.string.rv_list_delete_failed_title, failure.name), failure.code) { viewModel.remove(failure.name) }
    is DnsServersFailure.Create -> changeBanner(stringResource(R.string.rv_list_create_failed_title, failure.server.name), failure.code) { viewModel.create(failure.server) }
}

/** A change honk did not take, so nothing changed: its title, why, and 重試, which makes it again. */
@Composable
private fun changeBanner(title: String, code: ErrorCode, onRetry: () -> Unit) =
    RvNotice(RvNoticeKind.Error, title, stringResource(code.messageRes()), stringResource(R.string.rv_retry), onRetry, titleKeepWords = false)

@Composable
private fun readBanner(code: ErrorCode, onRetry: () -> Unit) =
    RvNotice(RvNoticeKind.Error, stringResource(code.messageRes()), null, stringResource(R.string.rv_retry), onRetry)
