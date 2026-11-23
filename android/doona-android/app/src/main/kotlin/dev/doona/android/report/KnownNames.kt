package dev.doona.android.report

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** What a crash file writes for a name it may not hold. */
internal const val REDACTED = "<redacted>"

/**
 * The names an app crash file may hold (ARCH D21): a class that [loader], the app's, resolves without initialising it, and
 * a method that class declares. Any code can give an exception frames of its own (`setStackTrace`) with any names, and a
 * name of plain identifiers can still be a customer's host or a password: only names of code the app holds pass. It
 * loads and reflects on classes, so a crash runs it on its own thread, within the handler's wait ([CrashHandler]).
 */
internal class KnownNames(private val loader: ClassLoader? = APP_LOADER) {
    /** The methods each class asked for declares, or null when the app does not hold the class. */
    private val methods = HashMap<String, Set<String>?>()

    /** The exception class [name], or [REDACTED]. */
    fun type(name: String): String = if (resolve(name) != null) name else REDACTED

    /** `class.method:line`, when the class declares the method and the text has a frame's shape ([FRAME_TEXT]); else [REDACTED]. */
    fun frame(className: String, method: String, line: Int): String {
        val text = "$className.$method:$line"
        return if (declared(className)?.contains(method) == true && FRAME_TEXT.matches(text)) text else REDACTED
    }

    /** A frame as a crash file wrote it, `class.method:line`, checked again; [REDACTED] when it is not of that shape. */
    fun frame(text: String): String {
        val (className, method, line) = FRAME_TEXT.matchEntire(text)?.destructured ?: return REDACTED
        return frame(className, method, line.toIntOrNull() ?: return REDACTED)
    }

    private fun declared(className: String): Set<String>? {
        if (className in methods) return methods[className]
        val names = resolve(className)?.let { type ->
            try {
                val constructors = if (type.declaredConstructors.isEmpty()) emptyList() else listOf(CONSTRUCTOR)
                val initialiser = if (type.declaredFields.any(::isInitialised)) listOf(INITIALISER) else emptyList()
                type.declaredMethods.map { it.name }.toHashSet() + constructors + initialiser
            } catch (_: LinkageError) {
                null
            } catch (_: SecurityException) {
                null
            }
        }
        methods[className] = names
        return names
    }

    /** The class [name], when every part of it is a plain identifier and [loader] resolves it; not initialised. */
    private fun resolve(name: String): Class<*>? {
        if (!CLASS_NAME.matches(name)) return null
        return try {
            Class.forName(name, false, loader)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private companion object {
        val APP_LOADER: ClassLoader? = KnownNames::class.java.classLoader

        /** The names the JVM gives a constructor and a static initialiser, which no declared method has. */
        const val CONSTRUCTOR = "<init>"
        const val INITIALISER = "<clinit>"

        /**
         * Whether [field] is one only a static initialiser sets. Reflection does not show the initialiser itself, so a class
         * has one when it declares a static field a constant cannot be: not final, or of a type other than a primitive or
         * a String. An object's instance, a companion and a field set from code all are; a class that holds constants only,
         * or sets a constant from code, is taken to have none, and its `<clinit>` frames are redacted.
         */
        fun isInitialised(field: Field): Boolean {
            val modifiers = field.modifiers
            val constant = Modifier.isFinal(modifiers) && (field.type.isPrimitive || field.type == String::class.java)
            return Modifier.isStatic(modifiers) && !constant
        }
    }
}

/**
 * A crash file as the export sends it, or null when it goes out not at all. A core file goes as it is: the core writes
 * only its own fixed texts. An app file goes only in the current format ([APP_HEADER]), and of it only the lines of that
 * format, the class and the frames checked again by [names]: a file of an earlier format was written with names never
 * checked, which a line break in one could spread over lines of any shape. Any other file is no crash file of a known
 * format and stays out.
 */
internal fun exportedCrash(bytes: ByteArray, names: KnownNames): ByteArray? {
    val text = bytes.decodeToString()
    return when {
        text.startsWith(CORE_SOURCE) -> bytes
        text.startsWith(APP_HEADER) -> exportedApp(text.split('\n'), names).joinToString("\n").toByteArray()
        else -> null
    }
}

/**
 * The [lines] of an app crash file the export sends. A line that states a value ([STATED]) goes only at its place in the
 * header, before [BACKTRACE]: once each, and never among the frames.
 */
private fun exportedApp(lines: List<String>, names: KnownNames): List<String> {
    val backtrace = lines.indexOf(BACKTRACE).takeIf { it >= 0 } ?: lines.size
    return lines.mapIndexedNotNull { at, line ->
        if (at < backtrace && STATED[at]?.matches(line) == true) line else appLine(line, names)
    }
}

/** [line] of an app crash file ([crashText]) as the export sends it, or null when no line of the format has its shape. */
private fun appLine(line: String, names: KnownNames): String? = when {
    line in FIXED_LINES -> line

    line == LOCATION + UNKNOWN -> line

    line.startsWith(LOCATION) -> checkedFrame(line.removePrefix(LOCATION), names)?.let { LOCATION + it }

    line.startsWith(FRAME) -> checkedFrame(line.removePrefix(FRAME), names)?.let { FRAME + it }

    line.startsWith(EXCEPTION) -> line.removePrefix(EXCEPTION).let { type ->
        when {
            type == REDACTED -> line
            CLASS_NAME.matches(type) -> EXCEPTION + names.type(type)
            else -> null
        }
    }

    else -> null
}

private fun checkedFrame(text: String, names: KnownNames): String? = when {
    text == REDACTED -> text
    FRAME_TEXT.matches(text) -> names.frame(text)
    else -> null
}

/** The lines of an app crash file ([crashText]) that name code, and the first ones, which tell its format. */
internal const val APP_SOURCE = "source: app\n"

/** The format of the app's crash files that this version writes and exports; files without it are of earlier versions. */
internal const val APP_FORMAT = "format: 1\n"
internal const val APP_HEADER = APP_SOURCE + APP_FORMAT
private const val CORE_SOURCE = "source: core\n"
internal const val LOCATION = "location: "
internal const val EXCEPTION = "exception: "
internal const val FRAME = "  at "
internal const val BACKTRACE = "backtrace:"
internal const val UNKNOWN = "unknown"

/** A class name: plain identifiers, `[A-Za-z_$][A-Za-z0-9_$]*`, joined by dots (`[Ljava.lang.String;` would resolve to an array). */
private const val CLASS = """[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*"""
private val CLASS_NAME = Regex(CLASS)

/**
 * A frame's text, `class.method:line`: the method one the JVM allows (no `.`, `;`, `[`, `/`, `<`, `>`), with no white
 * space or colon either, or `<init>`, `<clinit>`.
 */
private val FRAME_TEXT = Regex("""($CLASS)\.([^\s.;\[/<>:]+|<init>|<clinit>):(-?\d{1,10})""")

/** The lines of an app crash file that hold no value of the crash's: the header, the fixed message, the blank line. */
private val FIXED_LINES = setOf(
    APP_SOURCE.trimEnd(),
    APP_FORMAT.trimEnd(),
    "thread: main",
    "thread: other",
    "message: $CRASH_MESSAGE",
    "",
    BACKTRACE,
)

/** A version name as the build sets it (`versionName`, `0.1`): up to four numbers joined by dots. */
internal val VERSION_NAME = Regex("""\d{1,9}(?:\.\d{1,9}){0,3}""")

/** The build types the app is built as. */
internal val BUILD_TYPES = setOf("debug", "release")

/** The ABIs Android names in `Build.SUPPORTED_ABIS`. */
internal val ABIS = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "riscv64")

/**
 * The app's version line as [AppInfo.crashVersion] writes it, and the only one the export keeps: `<versionName> <build
 * type> <ABI>-android`, each part [UNKNOWN] when it is none of the above. Any other text, a DNS name among them, goes.
 */
private val CRASH_VERSION = run {
    // Letters, digits, `-` and `_` only: no part needs escaping.
    fun oneOf(parts: Collection<String>) = (parts + UNKNOWN).joinToString("|")
    Regex("version: (?:${VERSION_NAME.pattern}|$UNKNOWN) (?:${oneOf(BUILD_TYPES)}) (?:${oneOf(ABIS)})-android")
}

/** The lines that state a value, by their place in an app crash file ([crashText]): the time, then the app's version. */
private val STATED = APP_HEADER.count { it == '\n' }.let { header ->
    mapOf(header to Regex("""atEpochMs: \d{1,19}"""), header + 1 to CRASH_VERSION)
}
