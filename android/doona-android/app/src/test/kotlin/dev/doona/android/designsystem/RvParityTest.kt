package dev.doona.android.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaType
import dev.doona.android.designsystem.tokens.Inter
import dev.doona.android.designsystem.tokens.InterDisplay
import dev.doona.android.prototype.rv.RvColors
import dev.doona.android.prototype.rv.RvInter
import dev.doona.android.prototype.rv.RvInterDisplay
import dev.doona.android.prototype.rv.RvType
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * The design system's roles are the RV sample's: every colour role of [RvColors] and every text style of [RvType] has a
 * production role of the same name in [DoonaColors] and [DoonaType] with exactly its value, for each wallpaper seed,
 * light and dark. The production theme adds roles of its own (the old components'); those are not compared.
 */
class RvParityTest {
    @Test
    fun everyRvColourRoleIsTheDesignSystems() {
        for (seed in Seed.entries) {
            for (dark in listOf(false, true)) {
                val scheme = seedColorScheme(seed.argb, dark)
                val where = "$seed ${if (dark) "dark" else "light"}"
                val rv = roles(RvColors(scheme, dark))
                val doona = roles(DoonaColors(scheme, dark))
                assertTrue("$where: the RV roles are read", rv.size > MIN_ROLES)
                val missing = rv.keys - doona.keys
                assertTrue("$where: no production role for $missing", missing.isEmpty())
                for ((name, value) in rv) assertEquals("$where: $name", value, doona[name])
            }
        }
    }

    @Test
    fun everyRvTextStyleIsTheDesignSystems() {
        val rv = getters(RvType()).mapValues { it.value as TextStyle }
        val doona = getters(DoonaType()).mapValues { it.value as TextStyle }
        val families = mapOf<FontFamily?, FontFamily?>(RvInter to Inter, RvInterDisplay to InterDisplay)
        assertTrue("the RV styles are read", rv.size > MIN_ROLES)
        val missing = rv.keys - doona.keys
        assertTrue("no production style for $missing", missing.isEmpty())
        for ((name, style) in rv) {
            val ours = doona.getValue(name)
            assertEquals("$name's family", families[style.fontFamily], ours.fontFamily)
            assertEquals(name, style.copy(fontFamily = null), ours.copy(fontFamily = null))
        }
    }

    /** An object's roles by name, each value made comparable across the two themes' classes. */
    private fun roles(theme: Any): Map<String, Any?> = getters(theme).mapValues { (name, value) -> comparable(value, name) }

    private fun comparable(value: Any?, name: String): Any? = when {
        // A non-null Color comes back from its getter as the inline class's raw ULong bits.
        value is Long && name in colourGetters -> Color(value.toULong())

        value == null || value is Color || value is Number || value is Boolean || value is String -> value

        value is List<*> -> value.map { comparable(it, "") }

        // A colour group (a glass tint, a chart series, a tone's ink): its own roles.
        value.javaClass.name.startsWith("dev.doona.") -> roles(value)

        else -> value
    }

    private val colourGetters = mutableSetOf<String>()

    /** The values of [target]'s public no-argument getters, keyed by property name (mangling stripped). */
    private fun getters(target: Any): Map<String, Any?> = target.javaClass.methods
        .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.declaringClass == target.javaClass }
        .associate { method -> propertyName(method).also { if (method.isColour()) colourGetters += it } to method.invoke(target) }

    private fun propertyName(method: Method): String = method.name.removePrefix("get").substringBefore('-').substringBefore('$').replaceFirstChar { it.lowercase() }

    // Kotlin mangles a getter returning an inline class with a hash suffix; Color is the only one the themes return.
    private fun Method.isColour() = name.contains('-') && returnType == Long::class.javaPrimitiveType

    private companion object {
        /** Far fewer than either theme has: reflection that finds nothing must not pass. */
        const val MIN_ROLES = 20
    }
}
