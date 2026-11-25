package dev.doona.android.feature.model

/** A protocol as the pages name it (VLESS, Shadowsocks), from the core's lower-case id; an id not listed is shown as sent. */
internal fun protocolName(id: String): String = PROTOCOLS[id] ?: id

private val PROTOCOLS = mapOf(
    "vless" to "VLESS",
    "vmess" to "VMess",
    "trojan" to "Trojan",
    "ss" to "Shadowsocks",
    "shadowsocks" to "Shadowsocks",
    "hysteria2" to "Hysteria2",
    "tuic" to "TUIC",
)
