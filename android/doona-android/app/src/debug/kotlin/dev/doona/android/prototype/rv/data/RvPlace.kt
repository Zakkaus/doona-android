package dev.doona.android.prototype.rv.data

import androidx.annotation.StringRes
import dev.doona.android.R

/** The parts of the world the node list groups its rows by. */
internal enum class RvRegion(@StringRes val label: Int) {
    Asia(R.string.rv_nodes_region_asia),
    Oceania(R.string.rv_nodes_region_oceania),
    Americas(R.string.rv_nodes_region_americas),
    Europe(R.string.rv_nodes_region_europe),
}

/**
 * The one table of the countries and regions the sample's nodes are in, by ISO code (the enum's name, which also picks
 * the flag): the name every page shows, the part of the world, and the centroid in degrees for the maps.
 */
internal enum class RvPlace(@StringRes val label: Int, val region: RvRegion, val lat: Float, val lon: Float) {
    HK(R.string.rv_nodes_place_hk, RvRegion.Asia, 22.35f, 114.15f),
    TW(R.string.rv_nodes_place_tw, RvRegion.Asia, 23.7f, 120.96f),
    JP(R.string.rv_nodes_place_jp, RvRegion.Asia, 36.2f, 138.25f),
    KR(R.string.rv_nodes_place_kr, RvRegion.Asia, 35.9f, 127.8f),
    SG(R.string.rv_nodes_place_sg, RvRegion.Asia, 1.35f, 103.82f),
    US(R.string.rv_nodes_place_us, RvRegion.Americas, 39.83f, -98.58f),
    CA(R.string.rv_nodes_place_ca, RvRegion.Americas, 56.13f, -106.35f),
    GB(R.string.rv_nodes_place_gb, RvRegion.Europe, 54f, -2f),
    DE(R.string.rv_nodes_place_de, RvRegion.Europe, 51.17f, 10.45f),
    NL(R.string.rv_nodes_place_nl, RvRegion.Europe, 52.13f, 5.29f),
    FR(R.string.rv_nodes_place_fr, RvRegion.Europe, 46.23f, 2.21f),
    AU(R.string.rv_nodes_place_au, RvRegion.Oceania, -25.27f, 133.78f),
}
