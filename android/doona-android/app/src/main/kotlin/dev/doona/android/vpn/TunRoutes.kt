package dev.doona.android.vpn

import java.net.InetAddress

/** An IPv4 prefix; [base] is its first address as an unsigned 32-bit value, host bits clear. */
internal data class Ipv4Prefix(val base: Long, val length: Int) {
    init {
        require(length in 0..32 && base and mask(length).inv() and ALL == 0L) { "not a prefix: $base/$length" }
    }

    val address: String get() = (3 downTo 0).joinToString(".") { ((base shr (8 * it)) and 0xFF).toString() }

    val inetAddress: InetAddress get() = ipv4(base)

    operator fun contains(address: Long): Boolean = address and mask(length) == base

    operator fun contains(other: Ipv4Prefix): Boolean = other.length >= length && other.base in this

    /** The two prefixes one bit longer; only for [length] below 32. */
    fun halves(): List<Ipv4Prefix> = listOf(Ipv4Prefix(base, length + 1), Ipv4Prefix(base or (1L shl (31 - length)), length + 1))

    override fun toString() = "$address/$length"

    companion object {
        private const val ALL = 0xFFFF_FFFFL

        private fun mask(length: Int): Long = (ALL shl (32 - length)) and ALL

        fun parseAddress(address: String): Long {
            val octets = address.split('.').map(String::toInt)
            require(octets.size == 4 && octets.all { it in 0..255 }) { "not an IPv4 address: $address" }
            return octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        }

        /** The prefix of [length] bits that holds [address]. */
        fun of(address: String, length: Int) = Ipv4Prefix(parseAddress(address) and mask(length), length)

        fun parse(cidr: String): Ipv4Prefix {
            val (address, length) = cidr.split('/')
            return Ipv4Prefix(parseAddress(address), length.toInt())
        }
    }
}

private fun ipv4(address: Long): InetAddress = InetAddress.getByAddress(ByteArray(4) { (address shr (8 * (3 - it))).toByte() })

/**
 * [address] parsed here, never by a resolver. The builder's String overloads parse with a libcore-only method that a
 * JVM test cannot run.
 */
internal fun ipv4(address: String): InetAddress = ipv4(Ipv4Prefix.parseAddress(address))

/**
 * What the tun routes. Android 17 gives an app targeting SDK 37 no local-network access without the runtime
 * permission ACCESS_LOCAL_NETWORK, so LAN traffic re-originated by the core from doona's process would be blocked.
 * These prefixes stay outside the tunnel instead, and each app reaches them under its own permission.
 */
internal object TunRoutes {
    val EXCLUDED: List<Ipv4Prefix> = listOf(
        "0.0.0.0/8", // "this network"
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16", // link-local
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/3", // multicast, reserved and broadcast
    ).map(Ipv4Prefix::parse)

    /** The tun's own /30 lies inside 172.16.0.0/12; routing it keeps the DNS address reachable on any device. */
    val TUN: Ipv4Prefix = Ipv4Prefix.of(StartConfigDto.IPV4, StartConfigDto.IPV4_PREFIX)

    /**
     * The fewest prefixes that cover 0.0.0.0/0 minus [EXCLUDED], plus [TUN]. `excludeRoute` needs API 33 and minSdk is
     * 29, so every level routes the complement with `addRoute`.
     */
    val IPV4: List<Ipv4Prefix> = complement(Ipv4Prefix(0, 0), EXCLUDED).let { routes ->
        if (routes.any { TUN in it }) routes else routes + TUN
    }

    /** Splits [prefix] only where it overlaps [excluded], so every kept prefix is as short as it can be. */
    private fun complement(prefix: Ipv4Prefix, excluded: List<Ipv4Prefix>): List<Ipv4Prefix> = when {
        excluded.any { prefix in it } -> emptyList()
        excluded.none { it in prefix } -> listOf(prefix)
        else -> prefix.halves().flatMap { complement(it, excluded) }
    }
}
