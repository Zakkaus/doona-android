package dev.doona.android.prototype.rv.data

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpot

/**
 * The cities the sample's nodes are in, as doona-core's region table has them (`regions/cities.rs`): the core's id, the
 * country or region, the map anchor in degrees and the English name the node's name starts with. The core gives a node
 * its city when its name has one (Tokyo vs Osaka, Los Angeles vs New York); the map then marks the city, not the
 * country's centroid.
 */
internal enum class RvCity(val id: String, val place: RvPlace, val lat: Float, val lon: Float, val en: String) {
    HongKong("hong-kong", RvPlace.HK, 22.32f, 114.17f, "Hong Kong"),
    Taipei("taipei", RvPlace.TW, 25.03f, 121.57f, "Taipei"),
    Taichung("taichung", RvPlace.TW, 24.15f, 120.67f, "Taichung"),
    Kaohsiung("kaohsiung", RvPlace.TW, 22.63f, 120.3f, "Kaohsiung"),
    Tokyo("tokyo", RvPlace.JP, 35.68f, 139.69f, "Tokyo"),
    Osaka("osaka", RvPlace.JP, 34.69f, 135.5f, "Osaka"),
    Seoul("seoul", RvPlace.KR, 37.57f, 126.98f, "Seoul"),
    Chuncheon("chuncheon", RvPlace.KR, 37.88f, 127.73f, "Chuncheon"),
    Singapore("singapore", RvPlace.SG, 1.29f, 103.85f, "Singapore"),
    LosAngeles("los-angeles", RvPlace.US, 34.05f, -118.24f, "Los Angeles"),
    SanJose("san-jose", RvPlace.US, 37.34f, -121.89f, "San Jose"),
    Seattle("seattle", RvPlace.US, 47.61f, -122.33f, "Seattle"),
    NewYork("new-york", RvPlace.US, 40.71f, -74.01f, "New York"),
    Dallas("dallas", RvPlace.US, 32.78f, -96.8f, "Dallas"),
    Ashburn("ashburn", RvPlace.US, 39.04f, -77.49f, "Ashburn"),
    Vancouver("vancouver", RvPlace.CA, 49.28f, -123.12f, "Vancouver"),
    Toronto("toronto", RvPlace.CA, 43.65f, -79.38f, "Toronto"),
    London("london", RvPlace.GB, 51.51f, -0.13f, "London"),
    Manchester("manchester", RvPlace.GB, 53.48f, -2.24f, "Manchester"),
    Frankfurt("frankfurt", RvPlace.DE, 50.11f, 8.68f, "Frankfurt"),
    Berlin("berlin", RvPlace.DE, 52.52f, 13.4f, "Berlin"),
    Amsterdam("amsterdam", RvPlace.NL, 52.37f, 4.9f, "Amsterdam"),
    Rotterdam("rotterdam", RvPlace.NL, 51.92f, 4.48f, "Rotterdam"),
    Paris("paris", RvPlace.FR, 48.86f, 2.35f, "Paris"),
    Marseille("marseille", RvPlace.FR, 43.3f, 5.37f, "Marseille"),
    Sydney("sydney", RvPlace.AU, -33.87f, 151.21f, "Sydney"),
    Melbourne("melbourne", RvPlace.AU, -37.81f, 144.96f, "Melbourne"),
    Dubai("dubai", RvPlace.AE, 25.2f, 55.27f, "Dubai"),
    Mumbai("mumbai", RvPlace.IN, 19.08f, 72.88f, "Mumbai"),
    Istanbul("istanbul", RvPlace.TR, 41.01f, 28.98f, "Istanbul"),
    SaoPaulo("sao-paulo", RvPlace.BR, -23.55f, -46.63f, "São Paulo"),
    Zurich("zurich", RvPlace.CH, 47.38f, 8.54f, "Zurich"),
    Warsaw("warsaw", RvPlace.PL, 52.23f, 21.01f, "Warsaw"),
    Johannesburg("johannesburg", RvPlace.ZA, -26.2f, 28.05f, "Johannesburg"),
    Auckland("auckland", RvPlace.NZ, -36.85f, 174.76f, "Auckland"),
    ;

    val spot: RvSpot get() = RvSpot(lat, lon)
}
