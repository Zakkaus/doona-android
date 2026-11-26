package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvDnsUpstream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The DNS page: the core's [servers] (`ip:port`, IPv6 as `[addr]:port`), and the 自訂 sheet while [customOpen], with
 * the [customText] typed in it and, once a save failed, why ([customError]).
 */
internal data class DnsUiState(
    val servers: List<String>,
    val customOpen: Boolean = false,
    val customText: String = "",
    val customError: ErrorCode? = null,
)

/**
 * DNS: reads the servers from the core's effective config and saves them with `setDnsUpstreams`, a preset's one
 * address or the 1 to 4 typed in 自訂. The core checks what is typed, so its refusal is what the sheet shows.
 */
internal class DnsViewModel(private val engine: Engine) : ViewModel() {
    private val state = MutableStateFlow<DnsUiState?>(null)

    /** Null until the core has answered; a core that cannot answer leaves no server chosen. */
    val uiState: StateFlow<DnsUiState?> = state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun select(preset: RvDnsUpstream) {
        val servers = listOf(preset.server)
        state.update { it?.copy(servers = servers) }
        viewModelScope.launch { if (engine.setDnsUpstreams(servers).isFailure) reload() }
    }

    /** Opens 自訂 on the servers the core asks now, as typed: without the default port or IPv6's brackets. */
    fun openCustom() = state.update { current ->
        current?.copy(customOpen = true, customText = current.servers.joinToString(", ", transform = ::typed), customError = null)
    }

    fun editCustom(text: String) = state.update { it?.copy(customText = text, customError = null) }

    fun dismissCustom() = state.update { it?.copy(customOpen = false) }

    fun saveCustom() {
        val typed = state.value?.customText ?: return
        val servers = typed.split(SEPARATORS).filter(String::isNotEmpty)
        viewModelScope.launch {
            engine.setDnsUpstreams(servers)
                .onSuccess {
                    state.update { it?.copy(customOpen = false) }
                    reload()
                }
                .onFailure { e -> state.update { it?.copy(customError = e.errorCode()) } }
        }
    }

    private suspend fun reload() {
        val servers = engine.effectiveConfig().map { it.dnsUpstreams }.getOrDefault(emptyList())
        state.update { it?.copy(servers = servers) ?: DnsUiState(servers) }
    }

    companion object {
        /** Addresses in 自訂 are separated by commas, spaces or both. */
        private val SEPARATORS = Regex("""[,\s]+""")

        /** A server as the user types it: the default port and IPv6's brackets dropped. */
        private fun typed(server: String) = server.removeSuffix(":$DNS_PORT").removeSurrounding("[", "]")

        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { DnsViewModel(engine) }
        }
    }
}
