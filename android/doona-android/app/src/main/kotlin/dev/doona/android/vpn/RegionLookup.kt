package dev.doona.android.vpn

/**
 * The ISO 3166 code of the country or region each node name points to, as doona-core's region table reads it, in the
 * order of [regions]' names; `""` for a name it places nowhere. honk's nodes carry no region. Blocking: run it on
 * `Dispatchers.IO`.
 */
fun interface RegionLookup {
    fun regions(names: List<String>): List<String>
}
