package dev.doona.android.report

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

    /** `class.method:line`, or [REDACTED]. */
    fun frame(className: String, method: String, line: Int): String =
        if (declared(className)?.contains(method) == true) "$className.$method:$line" else REDACTED

    /** A frame as a crash file wrote it, `class.method:line`, checked again; [REDACTED] when it is not of that shape. */
    fun frame(text: String): String {
        val line = text.substringAfterLast(':', "").toIntOrNull() ?: return REDACTED
        val call = text.substringBeforeLast(':')
        val dot = call.lastIndexOf('.')
        return if (dot > 0) frame(call.substring(0, dot), call.substring(dot + 1), line) else REDACTED
    }

    private fun declared(className: String): Set<String>? {
        if (className in methods) return methods[className]
        val names = resolve(className)?.let { type ->
            try {
                val constructors = if (type.declaredConstructors.isEmpty()) emptyList() else listOf(CONSTRUCTOR)
                type.declaredMethods.map { it.name }.toHashSet() + constructors + INITIALISER
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
        if (!name.split('.').all(::isIdentifier)) return null
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

        /** `[A-Za-z_$][A-Za-z0-9_$]*`: a name like `[Ljava.lang.String;` would resolve to an array. */
        fun isIdentifier(name: String): Boolean {
            fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '$'
            return name.isNotEmpty() && letter(name[0]) && name.all { letter(it) || it in '0'..'9' }
        }
    }
}

/**
 * An app crash file as the export sends it: the class and the frames checked again by [names], as a file written before
 * the check holds them as the crash gave them. Any other file, the core's, goes as it is.
 */
internal fun exportedCrash(bytes: ByteArray, names: KnownNames): ByteArray {
    val text = bytes.decodeToString()
    if (!text.startsWith(APP_SOURCE)) return bytes
    return text.split('\n').joinToString("\n") { line ->
        when {
            line == LOCATION + UNKNOWN -> line
            line.startsWith(LOCATION) -> LOCATION + names.frame(line.removePrefix(LOCATION))
            line.startsWith(EXCEPTION) -> EXCEPTION + names.type(line.removePrefix(EXCEPTION))
            line.startsWith(FRAME) -> FRAME + names.frame(line.removePrefix(FRAME))
            else -> line
        }
    }.toByteArray()
}

/** The lines of an app crash file ([crashText]) that name code. */
internal const val APP_SOURCE = "source: app\n"
internal const val LOCATION = "location: "
internal const val EXCEPTION = "exception: "
internal const val FRAME = "  at "
internal const val UNKNOWN = "unknown"
