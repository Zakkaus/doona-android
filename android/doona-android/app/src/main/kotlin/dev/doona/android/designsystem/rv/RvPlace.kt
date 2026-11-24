package dev.doona.android.designsystem.rv

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/** The parts of the world the node list groups its rows by. */
internal enum class RvRegion(@StringRes val label: Int) {
    Asia(R.string.rv_nodes_region_asia),
    Oceania(R.string.rv_nodes_region_oceania),
    Americas(R.string.rv_nodes_region_americas),
    Europe(R.string.rv_nodes_region_europe),
    Africa(R.string.rv_nodes_region_africa),

    /** Where the nodes whose name gives no country are listed ([RvPlace.Other]); always last. */
    Other(R.string.rv_nodes_region_other),
}

/** A point on the maps, in degrees: a place's centroid, or the city a node is in. */
@Immutable
internal data class RvSpot(val lat: Float, val lon: Float)

/**
 * The one table of the countries and regions nodes are in, by ISO code (the enum's name, which also picks the flag): the
 * name every page shows, the part of the world (the UN's: the Middle East is Asia, Russia is Europe), and the centroid
 * in degrees for the maps. doona-core's 54 regions in its order (`regions/data.rs`: doona web's 40 from
 * `src/dae/regions.ts`, then 14 the core adds), with the core's centroids, except Sweden's, which sits one 2-degree cell
 * west of the core's: that falls on the Baltic in [RvLandMask].
 */
internal enum class RvPlace(@StringRes val label: Int, val region: RvRegion, val lat: Float, val lon: Float) {
    HK(R.string.rv_nodes_place_hk, RvRegion.Asia, 22.4f, 114.11f),
    TW(R.string.rv_nodes_place_tw, RvRegion.Asia, 23.7f, 120.96f),
    JP(R.string.rv_nodes_place_jp, RvRegion.Asia, 36.2f, 138.25f),
    SG(R.string.rv_nodes_place_sg, RvRegion.Asia, 1.35f, 103.82f),
    US(R.string.rv_nodes_place_us, RvRegion.Americas, 37.09f, -95.71f),
    KR(R.string.rv_nodes_place_kr, RvRegion.Asia, 35.91f, 127.77f),
    CA(R.string.rv_nodes_place_ca, RvRegion.Americas, 56.13f, -106.35f),
    GB(R.string.rv_nodes_place_gb, RvRegion.Europe, 55.38f, -3.44f),
    DE(R.string.rv_nodes_place_de, RvRegion.Europe, 51.17f, 10.45f),
    FR(R.string.rv_nodes_place_fr, RvRegion.Europe, 46.23f, 2.21f),
    NL(R.string.rv_nodes_place_nl, RvRegion.Europe, 52.13f, 5.29f),
    RU(R.string.rv_nodes_place_ru, RvRegion.Europe, 61.52f, 105.32f),
    AU(R.string.rv_nodes_place_au, RvRegion.Oceania, -25.27f, 133.78f),
    IN(R.string.rv_nodes_place_in, RvRegion.Asia, 20.59f, 78.96f),
    TR(R.string.rv_nodes_place_tr, RvRegion.Asia, 38.96f, 35.24f),
    BR(R.string.rv_nodes_place_br, RvRegion.Americas, -14.24f, -51.93f),
    AR(R.string.rv_nodes_place_ar, RvRegion.Americas, -38.42f, -63.62f),
    MY(R.string.rv_nodes_place_my, RvRegion.Asia, 4.21f, 101.98f),
    TH(R.string.rv_nodes_place_th, RvRegion.Asia, 15.87f, 100.99f),
    VN(R.string.rv_nodes_place_vn, RvRegion.Asia, 14.06f, 108.28f),
    PH(R.string.rv_nodes_place_ph, RvRegion.Asia, 12.88f, 121.77f),
    ID(R.string.rv_nodes_place_id, RvRegion.Asia, -0.79f, 113.92f),
    AE(R.string.rv_nodes_place_ae, RvRegion.Asia, 23.42f, 53.85f),
    CH(R.string.rv_nodes_place_ch, RvRegion.Europe, 46.82f, 8.23f),
    SE(R.string.rv_nodes_place_se, RvRegion.Europe, 60.13f, 16.64f),
    FI(R.string.rv_nodes_place_fi, RvRegion.Europe, 61.92f, 25.75f),
    IT(R.string.rv_nodes_place_it, RvRegion.Europe, 41.87f, 12.57f),
    ES(R.string.rv_nodes_place_es, RvRegion.Europe, 40.46f, -3.75f),
    PL(R.string.rv_nodes_place_pl, RvRegion.Europe, 51.92f, 19.15f),
    MO(R.string.rv_nodes_place_mo, RvRegion.Asia, 22.2f, 113.54f),
    NZ(R.string.rv_nodes_place_nz, RvRegion.Oceania, -40.9f, 174.89f),
    MX(R.string.rv_nodes_place_mx, RvRegion.Americas, 23.63f, -102.55f),
    NO(R.string.rv_nodes_place_no, RvRegion.Europe, 60.47f, 8.47f),
    DK(R.string.rv_nodes_place_dk, RvRegion.Europe, 56.26f, 9.5f),
    IE(R.string.rv_nodes_place_ie, RvRegion.Europe, 53.41f, -8.24f),
    ZA(R.string.rv_nodes_place_za, RvRegion.Africa, -30.56f, 22.94f),
    IL(R.string.rv_nodes_place_il, RvRegion.Asia, 31.05f, 34.85f),
    UA(R.string.rv_nodes_place_ua, RvRegion.Europe, 48.38f, 31.17f),
    PT(R.string.rv_nodes_place_pt, RvRegion.Europe, 39.4f, -8.22f),
    CN(R.string.rv_nodes_place_cn, RvRegion.Asia, 35.86f, 104.2f),
    KZ(R.string.rv_nodes_place_kz, RvRegion.Asia, 48.02f, 66.92f),
    AT(R.string.rv_nodes_place_at, RvRegion.Europe, 47.52f, 14.55f),
    BE(R.string.rv_nodes_place_be, RvRegion.Europe, 50.5f, 4.47f),
    LU(R.string.rv_nodes_place_lu, RvRegion.Europe, 49.82f, 6.13f),
    CZ(R.string.rv_nodes_place_cz, RvRegion.Europe, 49.82f, 15.47f),
    RO(R.string.rv_nodes_place_ro, RvRegion.Europe, 45.94f, 24.97f),
    IS(R.string.rv_nodes_place_is, RvRegion.Europe, 64.96f, -19.02f),
    CL(R.string.rv_nodes_place_cl, RvRegion.Americas, -35.68f, -71.54f),
    CO(R.string.rv_nodes_place_co, RvRegion.Americas, 4.57f, -74.3f),
    SA(R.string.rv_nodes_place_sa, RvRegion.Asia, 23.89f, 45.08f),
    PK(R.string.rv_nodes_place_pk, RvRegion.Asia, 30.38f, 69.35f),
    NG(R.string.rv_nodes_place_ng, RvRegion.Africa, 9.08f, 8.68f),
    EG(R.string.rv_nodes_place_eg, RvRegion.Africa, 26.82f, 30.8f),
    KH(R.string.rv_nodes_place_kh, RvRegion.Asia, 12.57f, 104.99f),

    /** A node whose name gives no country: listed as 其他, drawn with the fallback disc and never marked on a map. */
    Other(R.string.rv_nodes_place_other, RvRegion.Other, 0f, 0f),
    ;

    /** The flag's country code: none for [Other], whose disc then carries no code. */
    val flag: String? get() = if (this == Other) null else name

    /** Whether the place has a spot on a map; [Other] has none, so no map marks it and no count includes it. */
    val mapped: Boolean get() = this != Other

    /** The place's centroid on the maps; none for [Other]. */
    val spot: RvSpot? get() = if (mapped) RvSpot(lat, lon) else null
}
