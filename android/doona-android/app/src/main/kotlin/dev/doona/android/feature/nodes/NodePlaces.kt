package dev.doona.android.feature.nodes

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.model.RvCity

/**
 * Where a node's name places it: its country or region, its city when the name gives one, and the short name the narrow
 * tiles show (`HK 02`). A name that gives no country is [RvPlace.Other] with the whole name as [short] (M21).
 */
@Immutable
internal data class NodePlace(val place: RvPlace, val city: RvCity?, val short: String)

/** Places a node by its name. 活動 takes [placeOf]; the node and policy pages take [of]. */
internal fun interface NodePlaces {
    fun of(name: String): NodePlace

    fun placeOf(name: String): RvPlace = of(name).place
}

/**
 * The default [NodePlaces]: doona-core's region table (`crates/doona-core/src/regions.rs`, generated into
 * [NODE_PLACE_ALIASES] and its siblings) read in Kotlin, because the engine does not carry the core's `region` and `city`
 * per member yet (a wiring point for P2a). The rules follow the core's: a flag that comes first wins (one of a country
 * the app has no place for gives 其他); otherwise the alias that starts first, the longer one on a tie, then the
 * table's order. A Latin alias stands as a word of its own; a two-letter code the core keeps for capitals must be in
 * capitals; a short city code needs its context (alone, in capitals, or numbered). The city is the first one the name
 * gives in that country. Relay names (`A via B`, entry and exit) are the core's alone and are not read here.
 */
internal object CoreNodePlaces : NodePlaces {
    private val keys: List<Key> = buildList {
        NODE_PLACE_ALIASES.forEach { (place, texts) -> texts.split('|').forEach { add(Key.of(place, null, it, Kind.Place)) } }
        // A city's first key is its English name, which is never a code.
        NODE_CITY_ALIASES.forEach { (city, texts) -> texts.split('|').forEachIndexed { i, it -> add(Key.of(city.place, city, it, Kind.of(i))) } }
        NODE_SITE_ALIASES.forEach { (place, texts) -> texts.split('|').forEachIndexed { i, it -> add(Key.of(place, null, it, Kind.of(i))) } }
    }
    private val cityKeys = keys.filter { it.city != null }

    override fun of(name: String): NodePlace {
        val folded = fold(name)
        val alias = best(name, folded, keys)
        val flag = flag(name)?.takeIf { (at, _) -> alias == null || at < alias.start }
        val place = (if (flag != null) flag.second else alias?.key?.place) ?: RvPlace.Other
        if (place == RvPlace.Other) return NodePlace(RvPlace.Other, null, name)
        val city = alias?.key?.city?.takeIf { flag == null } ?: best(name, folded, cityKeys.filter { it.place == place })?.key?.city
        return NodePlace(place, city, short(place, name))
    }

    /** The match that starts first, the longer on a tie, the earlier key after that. */
    private fun best(name: String, folded: String, keys: List<Key>): Match? {
        var best: Match? = null
        for (key in keys) {
            val match = key.find(name, folded) ?: continue
            if (best == null || match.start < best.start || (match.start == best.start && match.end > best.end)) best = match
        }
        return best
    }

    /**
     * The first pair of regional indicators that names a country, with the index it starts at: the place, or [RvPlace.Other]
     * for a country the app has no place for. A pair that names no country is passed over, both indicators with it.
     */
    private fun flag(name: String): Pair<Int, RvPlace>? {
        var i = 0
        while (i < name.length) {
            val first = indicator(name.codePointAt(i))
            val next = i + Character.charCount(name.codePointAt(i))
            val second = if (first != null && next < name.length) indicator(name.codePointAt(next)) else null
            if (first == null || second == null) {
                i = next
                continue
            }
            val code = "$first$second"
            PLACES[code]?.let { return i to it }
            if (code in NODE_FLAG_ONLY) return i to RvPlace.Other
            i = next + Character.charCount(name.codePointAt(next))
        }
        return null
    }

    private fun indicator(cp: Int): Char? = if (cp in INDICATOR_A..INDICATOR_Z) 'A' + (cp - INDICATOR_A) else null

    /** The place's code and the first number in the name: `Hong Kong 02 IPLC` is `HK 02`; with no number, the code. */
    private fun short(place: RvPlace, name: String): String = NUMBER.find(name)?.let { "${place.name} ${it.value}" } ?: place.name

    private val PLACES = RvPlace.entries.filter { it.mapped }.associateBy { it.name }
    private val NUMBER = Regex("[0-9]+")
    private const val INDICATOR_A = 0x1F1E6
    private const val INDICATOR_Z = 0x1F1FF
}

/**
 * The core's case folding (`regions.rs`, folded): lower case one character for one, so indices stay those of the name,
 * but the dotted capital I stays as it is and the dotless i is not an i.
 */
private fun fold(text: String): String = buildString(text.length) {
    for (c in text) append(
        if (c == DOTTED_I) {
            c
        } else if (c == LONG_S) {
            's'
        } else {
            c.lowercaseChar()
        },
    )
}

private class Match(val key: Key, val start: Int, val end: Int)

/** Whose alias a key is: a place's, a city's English name, or another of a city's. */
private enum class Kind {
    Place,
    CityName,
    City,
    ;

    companion object {
        fun of(index: Int) = if (index == 0) CityName else City
    }
}

/** What an alias needs around it to count. */
private enum class Context { Free, Capitals, Numbered, Risky, Short }

/** One alias: the place (and city) it names, its folded text, and the context it needs. */
private class Key(val place: RvPlace, val city: RvCity?, val text: String, val context: Context, val numberedCode: Boolean) {
    private val han = text.first().isHan()

    fun find(name: String, folded: String): Match? {
        var from = 0
        while (true) {
            val start = folded.indexOf(text, from)
            if (start < 0) return null
            val end = start + text.length
            if (accepts(name, start, end)) return Match(this, start, end)
            from = start + 1
        }
    }

    private fun accepts(name: String, start: Int, end: Int): Boolean {
        val tail = name.substring(end)
        if (text == PORT) return tail.firstOrNull()?.isLetterOrMark() != true
        if (han) return true
        if (name.getOrNull(start - 1)?.isLetterOrMark() == true || tail.firstOrNull()?.isLetterOrMark() == true) return false
        if (numberedCode && tail.firstOrNull()?.isDigit() == true && !numbered(tail)) return false
        return inContext(name, name.substring(start, end), tail)
    }

    /** Whether the [written] alias, followed by [tail] in [name], has the context the key needs. */
    private fun inContext(name: String, written: String, tail: String): Boolean {
        val capitals = written.all { it in 'A'..'Z' }
        val counted = numbered(tail.trimStart('-', '_', ' '))
        return when (context) {
            Context.Free -> true
            Context.Capitals -> capitals
            Context.Numbered -> numbered(tail.trimStart('-', '_'))
            Context.Risky -> (name.trim() == written && capitals) || counted
            Context.Short -> name.trim() == written || capitals || counted
        }
    }

    companion object {
        fun of(place: RvPlace, drawn: RvCity?, raw: String, kind: Kind): Key {
            val marker = raw.first().takeIf { it in MARKERS }
            val text = if (marker == null) raw else raw.drop(1)
            val ascii = text.all { it.code < ASCII }
            val context = when {
                kind == Kind.Place && text == place.name && text in CAPITALS_ONLY -> Context.Capitals
                kind == Kind.Place -> Context.Free
                marker == '+' -> Context.Numbered
                marker == '?' -> Context.Risky
                ascii && text.length <= SHORT_CODE && text.lowercase() !in FREE_CODES -> Context.Short
                else -> Context.Free
            }
            val numberedCode = marker == '^' || (kind == Kind.City && ascii && text.length <= CODE)
            return Key(place, drawn, fold(text), context, numberedCode)
        }
    }
}

/** A tail that starts with digits not followed by a letter: `01`, `7 IPLC`. */
private fun numbered(tail: String): Boolean {
    val rest = tail.trimStart { it.isDigit() }
    return rest.length < tail.length && rest.firstOrNull()?.isLetterOrMark() != true
}

private fun Char.isHan() = this in '㐀'..'鿿'

private fun Char.isLetterOrMark() = isLetter() || Character.getType(this).toByte().let { it == Character.NON_SPACING_MARK || it == Character.COMBINING_SPACING_MARK || it == Character.ENCLOSING_MARK }

private const val MARKERS = "?^+"
private const val PORT = "港"
private const val SHORT_CODE = 3
private const val CODE = 4
private const val ASCII = 128
private const val DOTTED_I = 'İ'
private const val LONG_S = 'ſ'

/** The two-letter codes the core accepts only in capitals (`regions.rs`, CAPITAL_ONLY): they are also common words. */
private val CAPITALS_ONLY = "IN IT MY NO ID KZ AT BE LU CZ RO IS CL CO SA PK NG EG KH".split(' ').toSet()

/** Three-letter city codes the core accepts anywhere (`regions.rs`, code_needs_context). */
private val FREE_CODES = "hkg tpe nrt hnd kix sjc nyc icn yyz lhr fra cdg ams syd bom gru kul bkk dxb".split(' ').toSet()
