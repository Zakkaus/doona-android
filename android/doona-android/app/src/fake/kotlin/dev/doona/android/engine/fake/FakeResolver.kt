package dev.doona.android.engine.fake

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException

/** Mirrors the core's literal-only syntax without looking up a name. */
internal fun fakeResolver(value: String): String? {
    val bracketed = value.startsWith('[')
    val authority = if (!bracketed && value.count { it == ':' } > 1) "[$value]" else value
    val uri = try {
        URI("dns://$authority").parseServerAuthority()
    } catch (_: URISyntaxException) {
        return null
    }
    val host = uri.host ?: return null
    if (uri.rawAuthority != authority || uri.rawUserInfo != null || '%' in host) return null
    if (!host.startsWith('[') && !ipv4(host)) return null
    val port = if (authority == host && !bracketed) 53 else uri.port
    if (port !in 1..65535) return null
    return endpoint(host, port)
}

private fun endpoint(host: String, port: Int): String? {
    // URI has validated a bracketed IPv6 literal or ipv4() has validated a dotted quad. No DNS lookup.
    val address = InetAddress.getByName(host)
    if (address.isAnyLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress) return null
    if (address is Inet4Address) return address.hostAddress.takeUnless { it == "255.255.255.255" }?.let { "$it:$port" }
    return "[${canonicalIpv6(address.address)}]:$port"
}

private fun ipv4(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 && octets.all { it.toUByteOrNull()?.toString() == it }
}

private val zeroRun = Regex("(?<![0-9a-f])0(?::0)+(?![0-9a-f])")

/** RFC 5952 compresses the first longest run of at least two zero words. */
private fun canonicalIpv6(bytes: ByteArray): String {
    val words = (0 until 16 step 2).joinToString(":") { i ->
        (((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255)).toString(16)
    }
    val run = zeroRun.findAll(words).maxByOrNull { it.value.length } ?: return words
    return words.take(run.range.first).removeSuffix(":") + "::" + words.drop(run.range.last + 1).removePrefix(":")
}
