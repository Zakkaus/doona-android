package dev.doona.android.feature.nodes

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.model.RvCity
import dev.doona.android.vpn.ContractFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NodePlacesTest {
    private val places = CoreNodePlaces

    @Test
    fun theCoreFixturesMembersMapAsTheCoreMapsThem() {
        val rows = Json.parseToJsonElement(ContractFixtures.read("catalog/member-locations.json")).jsonArray.map { it.jsonObject }
        // The core places leaf members only; `Auto` and `JP` are groups in the fixture's catalog (contract.rs), never passed here.
        val leaves = rows.filter { it.name() !in setOf("Auto", "JP") }
        for (row in leaves) {
            val placed = places.of(row.name())
            val region = row["region"].takeUnless { it is JsonNull }?.jsonPrimitive?.content
            val city = (row["city"] as? JsonObject)?.get("id")?.jsonPrimitive?.content
            assertEquals(row.name(), region ?: RvPlace.Other.name, placed.place.name)
            assertEquals(row.name(), city, placed.city?.id)
        }
        assertEquals(4, leaves.size)
    }

    @Test
    fun anUnknownNameIsOtherWithItsWholeNameAsTheShortOne() {
        for (name in listOf("anonymous", "my node", "高速节点", "", "🇦🇶 01", "Premium PM 8am to 5pm")) {
            assertEquals(name, NodePlace(RvPlace.Other, null, name), places.of(name))
            assertEquals(RvPlace.Other, places.placeOf(name))
        }
    }

    @Test
    fun everyPlaceMapsFromItsCodeAndEveryCityFromItsName() {
        for (place in RvPlace.entries.filter { it.mapped }) {
            assertEquals(place, places.of("${place.name} 01").place)
            assertEquals("${place.name} 01", places.of("${place.name}-01").short)
        }
        for (city in RvCity.entries) {
            val placed = places.of("${city.en} 03 IPLC")
            assertEquals(city.en, city, placed.city)
            assertEquals(city.place, placed.place)
            assertEquals("${city.place.name} 03", placed.short)
        }
    }

    @Test
    fun theCoresOwnExamplesMapToTheSamePlace() {
        // regions/tests.rs, web_region_and_country_flag_examples: each name with the region the core gives it.
        // A region the app has no place for (Antarctica's flag) is 其他.
        val examples = coreExamples()
        val misses = examples.mapNotNull { (iso, name) ->
            val want = RvPlace.entries.firstOrNull { it.name == iso && it.mapped } ?: RvPlace.Other
            val got = places.placeOf(name)
            "$name: $iso, got ${got.name}".takeIf { got != want }
        }
        assertEquals(emptyList<String>(), misses)
        assertEquals(true, examples.size > EXAMPLES)
    }

    @Test
    fun cityCodesNeedTheirContextAndAFlagThatComesFirstWins() {
        assertEquals(RvCity.Tokyo, places.of("Japan NRT").city)
        assertEquals(RvCity.Osaka, places.of("🇯🇵 osaka tokyo").city)
        assertEquals(RvCity.Melbourne, places.of("🇦🇺 MEL").city)
        assertEquals(RvCity.Dallas, places.of("US 🇩🇪 Dallas").city)
        assertEquals(RvPlace.US, places.of("🇺🇸 Japan").place)
        assertEquals(RvPlace.HK, places.of("🇭🇰🇺🇸").place)
        assertEquals(null, places.of("CN via Tokyo").city)
        assertEquals(RvPlace.CN, places.of("CN via Tokyo").place)
    }

    private fun JsonObject.name() = getValue("name").jsonPrimitive.content

    private fun coreExamples(): List<Pair<String, String>> {
        val source = ContractFixtures.dir.resolve("../../crates/doona-core/src/regions/tests.rs").readText()
        val block = source.substringAfter("fn web_region_and_country_flag_examples").substringBefore("] {")
        return Regex("""\(\s*"([A-Z]*)",\s*"((?:[^"\\]|\\.)*)",?\s*\)""").findAll(block).flatMap { m ->
            val iso = m.groupValues[1]
            unescape(m.groupValues[2]).split('|').map { iso to it }
        }.toList()
    }

    /** The Rust string escapes the examples use: `\u{…}`. */
    private fun unescape(text: String) = Regex("""\\u\{([0-9a-fA-F]+)\}""").replace(text) { String(Character.toChars(it.groupValues[1].toInt(HEX))) }

    private companion object {
        const val HEX = 16

        /** The block holds about 200 names; fewer means the parse broke. */
        const val EXAMPLES = 150
    }
}
