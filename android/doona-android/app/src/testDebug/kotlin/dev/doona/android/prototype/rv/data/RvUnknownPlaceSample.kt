package dev.doona.android.prototype.rv.data

import dev.doona.android.designsystem.rv.RvPlace

/** The node [from] the sample, as a subscription lists it with a name that gives no country. */
private fun unknown(from: String, id: String, name: String) =
    RvData.nodes.first { it.id == from }.copy(id = id, name = name, short = name, place = RvPlace.Other, city = null)

/**
 * One Taiwan node and three whose names give no country. Premium 01 answers faster than the Taiwan node, so it would
 * lead any list ordered by latency; the list still ends with it.
 */
internal fun rvUnknownPlaceNodes(): List<RvNode> = listOf(
    RvData.nodes.first { it.id == "tw-01" },
    unknown("tw-04", "other-01", "Premium 01"),
    unknown("jp-01", "other-02", "Premium 02"),
    unknown("kr-01", "other-03", "Backup 01"),
)
