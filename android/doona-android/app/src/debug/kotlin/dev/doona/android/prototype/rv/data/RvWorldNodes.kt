package dev.doona.android.prototype.rv.data

/**
 * Nine nodes in cities the 48 leave out, every part of the world among them (Africa only here): Dubai,
 * Mumbai and Istanbul in Asia, Ashburn (the US east coast) and São Paulo in the Americas, Zurich and Warsaw in Europe,
 * Johannesburg, and Auckland. Each takes a sample node's subscription, protocol and tests, and is numbered after its
 * country's nodes in the 48, so Ashburn is us-07. No page of the sample lists them; the map's pans and the node list's
 * parts of the world show them.
 */
internal fun rvWorldNodes(from: List<RvNode>): List<RvNode> = listOf(
    RvCity.Dubai to "sg-01",
    RvCity.Mumbai to "sg-02",
    RvCity.Istanbul to "de-02",
    RvCity.Ashburn to "us-01",
    RvCity.SaoPaulo to "us-06",
    RvCity.Zurich to "de-01",
    RvCity.Warsaw to "nl-01",
    RvCity.Johannesburg to "gb-01",
    RvCity.Auckland to "au-01",
).map { (city, like) ->
    val code = city.place.name
    val n = from.count { it.place == city.place } + 1
    val number = n.toString().padStart(2, '0')
    from.first { it.id == like }.copy(
        id = "${code.lowercase()}-$number",
        name = "${city.en} $number",
        short = "$code $number",
        place = city.place,
        city = city,
        host = "${code.lowercase()}$n.example.net",
    )
}
