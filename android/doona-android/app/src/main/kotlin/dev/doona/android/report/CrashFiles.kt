package dev.doona.android.report

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The crash files the core and the app share, in the core's `dataDir/crash` (core-api.md, `diagnostics`). A file is
 * `<epochMs>.txt`, the number at most [MAX_NUMBER] and above every number a crash file had: above the files present and
 * above the mark the core saves in `.core.mark` when a clear removes them. A file holds at most [MAX_BYTES], and the
 * directory keeps the newest [MAX_FILES]. The core writes its own; the app writes one from its uncaught-exception
 * handler ([CrashCapture]), with [clock] for the name.
 */
class CrashFiles(val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    /** The crash files present, oldest first. */
    fun list(): List<File> = dir.listFiles().orEmpty()
        .mapNotNull { file -> number(file.name)?.takeIf { file.isFile }?.let { it to file } }
        .sortedBy { it.first }
        .map { it.second }

    /** The highest number a crash file present has; 0 with none. */
    fun newest(): Long = list().lastOrNull()?.let { number(it.name) } ?: 0

    /** The crash file [name] as it is now, at most [MAX_BYTES] of it; null when it is no crash file's name or is gone. */
    fun read(name: String): ByteArray? {
        if (number(name) == null) return null
        return try {
            File(dir, name).inputStream().use { input ->
                val buffer = ByteArray(MAX_BYTES)
                var filled = 0
                while (filled < MAX_BYTES) {
                    val read = input.read(buffer, filled, MAX_BYTES - filled)
                    if (read < 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Saves [text] as a new crash file and returns its name, or null when it could not: the disk refused, or no number
     * is left. It is written in full under a name no listing reads and then renamed, so a reader never sees part of it.
     */
    fun write(text: String): String? {
        val part = File(dir, PART)
        return try {
            dir.mkdirs()
            FileOutputStream(part).use {
                it.write(capped(text))
                it.fd.sync()
            }
            publish(part)
        } catch (_: IOException) {
            null
        } finally {
            part.delete()
        }
    }

    private fun publish(part: File): String? {
        val present = list()
        val mark = maxOf(savedMark(), present.lastOrNull()?.let { number(it.name) } ?: 0)
        var at = maxOf(clock(), mark + 1)
        while (at <= MAX_NUMBER && File(dir, "$at.txt").exists()) at++
        if (at > MAX_NUMBER) return null
        val name = "$at.txt"
        if (!part.renameTo(File(dir, name))) return null
        present.take((present.size + 1 - MAX_FILES).coerceAtLeast(0)).forEach { it.delete() }
        return name
    }

    /** The mark the core saved with its last clear; 0 when there is none or it cannot be read. */
    private fun savedMark(): Long = try {
        File(dir, MARK).readText().trim().toLongOrNull()?.takeIf { it in 0..MAX_NUMBER } ?: 0
    } catch (_: IOException) {
        0
    }

    companion object {
        const val MAX_FILES = 5
        const val MAX_BYTES = 64 * 1024

        /** A 13-digit millisecond time, until the year 2286; the core takes no larger number. */
        const val MAX_NUMBER = 9_999_999_999_999L

        /** The app's file in progress; the core's is `.core.tmp`. */
        private const val PART = ".app.tmp"
        private const val MARK = ".core.mark"
        private const val DIGITS = 13

        /** The number a crash file's name holds, or null when [name] is no crash file's: `<digits>.txt`, at most [MAX_NUMBER]. */
        fun number(name: String): Long? {
            val digits = name.removeSuffix(".txt")
            if (digits == name || digits.isEmpty() || digits.length > DIGITS || !digits.all { it in '0'..'9' }) return null
            return digits.toLong().takeIf { it <= MAX_NUMBER }
        }

        /** [text] as UTF-8, cut to [MAX_BYTES] at the start of a character. */
        internal fun capped(text: String): ByteArray {
            val bytes = text.toByteArray()
            if (bytes.size <= MAX_BYTES) return bytes
            var end = MAX_BYTES
            // A byte 10xxxxxx continues a character; cut before the byte that starts it.
            while (end > 0 && bytes[end].toInt() and CONTINUATION_MASK == CONTINUATION) end--
            return bytes.copyOf(end)
        }

        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
    }
}
