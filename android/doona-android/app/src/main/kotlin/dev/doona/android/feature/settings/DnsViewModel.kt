package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.policies.HonkUpstream
import dev.doona.android.feature.policies.dnsShown
import dev.doona.android.feature.policies.fallbackUpstream
import dev.doona.android.feature.policies.honkUpstreams
import dev.doona.android.feature.policies.upstreamReaddressed
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
import java.net.InetAddress

/**
 * The DNS page: the server honk asks most queries ([servers]: its `host[:port]`, IPv6 as `[addr]:port`; empty when the
 * request fallback names none), and the 自訂 sheet while [customOpen], with the [customText] typed in it and, once a
 * save failed, why ([customError]).
 */
internal data class DnsUiState(
    val servers: List<String>,
    val customOpen: Boolean = false,
    val customText: String = "",
    val customError: ErrorCode? = null,
)

/**
 * DNS: the server honk's DNS request fallback names, read from honk's config and DNS rules on [io]. A preset or the
 * address typed in 自訂 becomes that server's address in the file that declares it; honk checks the file before it
 * takes it.
 */
internal class DnsViewModel(private val api: NativeApi, private val io: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val state = MutableStateFlow<DnsUiState?>(null)
    private var fallback: HonkUpstream? = null

    /** Null until honk has answered; honk that cannot answer leaves no server chosen. */
    val uiState: StateFlow<DnsUiState?> = state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    fun select(preset: RvDnsUpstream) {
        state.update { it?.copy(servers = listOf(preset.address)) }
        viewModelScope.launch {
            runCatching { save(PRESETS.getValue(preset)) }
            reload()
        }
    }

    /** Opens 自訂 on the server honk asks now, as typed: a plain one without the default port or IPv6's brackets. */
    fun openCustom() = state.update { current ->
        current?.copy(customOpen = true, customText = fallback?.let { typed(it.address) }.orEmpty(), customError = null)
    }

    fun editCustom(text: String) = state.update { it?.copy(customText = text, customError = null) }

    fun dismissCustom() = state.update { it?.copy(customOpen = false) }

    fun saveCustom() {
        val typed = state.value?.customText ?: return
        viewModelScope.launch {
            runCatching { save(honkAddress(typed.trim()) ?: throw EngineException(ErrorCode.InvalidArgument)) }
                .onSuccess {
                    state.update { it?.copy(customOpen = false) }
                    reload()
                }
                .onFailure { e -> state.update { it?.copy(customError = e.errorCode()) } }
        }
    }

    private suspend fun save(address: String) {
        val upstream = fallback ?: throw EngineException(ErrorCode.InvalidArgument)
        withContext(io) { NativeConfig(api).replace(upstream.source, upstreamReaddressed(upstream, address)) }
    }

    private suspend fun reload() {
        fallback = try {
            withContext(io) { fallbackUpstream(honkUpstreams(NativeConfig(api).config()), NativeRouting(api).dnsRules()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val servers = listOfNotNull(fallback?.let { dnsShown(it.address) })
        state.update { it?.copy(servers = servers) ?: DnsUiState(servers) }
    }

    companion object {
        /** The DNS-over-HTTPS address each preset writes, the form doona's templates use. */
        private val PRESETS = mapOf(
            RvDnsUpstream.Cloudflare to "https://1.1.1.1/dns-query?tls_server_name=cloudflare-dns.com",
            RvDnsUpstream.Google to "https://8.8.8.8/dns-query?tls_server_name=dns.google",
            RvDnsUpstream.Quad9 to "https://9.9.9.9/dns-query?tls_server_name=dns.quad9.net",
            RvDnsUpstream.AliDns to "https://223.5.5.5/dns-query?tls_server_name=dns.alidns.com",
        )

        private val BRACKETED = Regex("""\[(.+)](?::(\d+))?""")
        private val IPV4 = Regex("""(\d{1,3}(?:\.\d{1,3}){3})(?::(\d+))?""")
        private val IPV6 = Regex("""[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*""")

        /**
         * What 自訂 writes for [typed]: an address in one of honk's DNS schemes as it is, else one IP address with an
         * optional port (`1.1.1.1`, `1.1.1.1:5353`, `2606:4700:4700::1111`, `[2606:4700:4700::1111]:53`) as plain DNS
         * on port 53 unless given; null for anything else, a link-local address among them.
         */
        internal fun honkAddress(typed: String): String? {
            if ("://" in typed) return typed.takeIf(::rvDnsAddressValid)
            val v4 = IPV4.matchEntire(typed)
            val v6 = BRACKETED.matchEntire(typed)
            val (host, port) = when {
                v4 != null -> v4.groupValues[1].takeIf { ip -> ip.split('.').all { it.toInt() <= 255 } } to v4.groupValues[2]
                v6 != null -> v6.groupValues[1].takeIf(IPV6::matches) to v6.groupValues[2]
                IPV6.matches(typed) -> typed to ""
                else -> null to ""
            }
            if (host == null) return null
            val number = port.ifEmpty { "$DNS_PORT" }.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            // A literal: getByName parses it and asks no resolver.
            val ip = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
            if (ip.isLinkLocalAddress) return null
            return if (':' in host) "udp://[$host]:$number" else "udp://$host:$number"
        }

        /** An address as the user types it: a plain DNS one without its scheme, default port or IPv6's brackets. */
        private fun typed(address: String): String {
            if (!address.startsWith("udp://")) return address
            return address.removePrefix("udp://").removeSuffix(":$DNS_PORT").removeSurrounding("[", "]")
        }

        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { DnsViewModel(api) }
        }
    }
}
