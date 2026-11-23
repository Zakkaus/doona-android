package dev.doona.android.flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated lookup reaches every flag drawable, and says nothing for a code the set lacks. */
class FlagTableTest {
    private val drawables: Map<String, Int> = R.drawable::class.java.fields
        .filter { it.name.startsWith("flag_") }
        .associate { it.name to it.getInt(null) }

    /** A drawable's stem written with each underscore either way: upstream's codes use both `-` and `_`. */
    private fun codesOf(stem: String): List<String> {
        val at = stem.indexOf('_')
        if (at < 0) return listOf(stem)
        return codesOf(stem.substring(at + 1)).flatMap { rest ->
            listOf(stem.substring(0, at) + "-" + rest, stem.substring(0, at) + "_" + rest)
        }
    }

    @Test
    fun theSetIsWholeAndItsIdsAreDistinct() {
        assertTrue("only ${drawables.size} flag drawables", drawables.size > 400)
        assertEquals("flag drawables share an id", drawables.size, drawables.values.toSet().size)
    }

    @Test
    fun noDrawableIsLeftOutOfTheTable() {
        // The United Nations flag is only filed under un, a link into upstream's flags/other.
        val codes = drawables.keys.flatMap { codesOf(it.removePrefix("flag_")) } + "un"
        val reached = codes.mapNotNull(::flagResource).toSet()
        val orphans = drawables.filterValues { it !in reached }.keys
        assertTrue("no code reaches $orphans", orphans.isEmpty())
    }

    @Test
    fun everyTwoLetterCodeThatHasAFlagIsOneOfTheDrawables() {
        val ids = drawables.values.toSet()
        for (a in 'a'..'z') {
            for (b in 'a'..'z') {
                val id = flagResource("$a$b")
                assertTrue("$a$b maps to a drawable that is not a flag", id == null || id in ids)
            }
        }
    }

    @Test
    fun codesAreMatchedInAnyCaseAndLinksFollowTheirTarget() {
        assertEquals(drawables.getValue("flag_us"), flagResource("US"))
        assertEquals(drawables.getValue("flag_us"), flagResource("us"))
        assertEquals(drawables.getValue("flag_gb_eng"), flagResource("GB-ENG"))
        assertEquals(drawables.getValue("flag_gb"), flagResource("uk"))
        assertEquals(drawables.getValue("flag_united_nations"), flagResource("un"))
        assertEquals(drawables.getValue("flag_european_union"), flagResource("eu"))
        assertNotNull(flagResource("TW"))
        assertNotNull(flagResource("HK"))
    }

    @Test
    fun aCodeTheSetLacksHasNoFlag() {
        assertNull(flagResource(""))
        assertNull(flagResource("zz"))
        assertNull(flagResource("usa"))
        assertNull(flagResource("u"))
    }
}
