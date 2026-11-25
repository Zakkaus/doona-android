package dev.doona.android.vpn

import dev.doona.android.engine.Mode
import dev.doona.android.engine.RuleOptions
import kotlinx.serialization.Serializable

@Serializable
internal data class GroupArgs(val group: String?)

/** `addSubscription`: a null [userAgent] is the core's default. */
@Serializable
internal data class AddSubscriptionArgs(val url: String, val name: String?, val userAgent: String?)

@Serializable
internal data class RenameSubscriptionArgs(val id: String, val name: String)

/** `setSubscriptionUserAgent`: the key is required, and null returns the subscription to the default. */
@Serializable
internal data class SubscriptionUserAgentArgs(val id: String, val userAgent: String?)

@Serializable
internal data class IdArgs(val id: String)

@Serializable
internal data class ModeArgs(val mode: Mode)

@Serializable
internal data class BootstrapDnsArgs(val servers: List<String>) {
    companion object {
        const val COMMAND = "setBootstrapDns"
    }
}

@Serializable
internal data class DnsUpstreamsArgs(val servers: List<String>)

/** `rules`' page: [limit] is 1 to 500. */
@Serializable
internal data class RulesArgs(val offset: Int, val limit: Int)

/** `setRuleSource`: schema 2 knows [kind] `template` only. */
@Serializable
internal data class RuleSourceArgs(val kind: String, val template: String, val options: RuleOptionsDto) {
    companion object {
        fun template(template: String, options: RuleOptions) = RuleSourceArgs("template", template, RuleOptionsDto.of(options))
    }
}

/** `trafficSeries`' window, 1 to 3600 seconds. */
@Serializable
internal data class SeriesArgs(val seconds: Long)
