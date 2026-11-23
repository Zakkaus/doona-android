package dev.doona.android.report

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The crash files the core and the app share, in the core's `dataDir/crash` (core-api.md, `diagnostics`). A file is
 * `<epochMs>.txt`, the number 13 digits long ([MIN_NUMBER], [MAX_NUMBER]) and above every number a crash file had:
 * above the files present and above the mark the core saves in `.core.mark` when a clear removes them. A file holds at
 * most [MAX_BYTES], and the directory keeps the newest [MAX_FILES]. The core writes its own (`crash.rs`); the app writes
 * one from its uncaught-exception handler ([CrashCapture]), with [clock] for the name. Either may write while the other
 * does, so both take a name by the same rule: claim it by creating it, which fails when anyone holds it, rename the
 * finished part onto the claim, then count the files again and remove all but the newest five.
 */
class CrashFiles(locate: () -> File, private val clock: () -> Long = System::currentTimeMillis) {
    constructor(dir: File, clock: () -> Long = System::currentTimeMillis) : this({ dir }, clock)

    /** The directory, found on first use: the app's directories are made when first asked for, which is disk work. */
    val dir: File by lazy(locate)

    /**
     * The crash files present, oldest first. An empty one is not a crash yet: a name another writer claimed and has not
     * renamed its part onto, or one a write the system ended left behind ([sweep]).
     */
    fun list(): List<File> = dir.listFiles().orEmpty()
        .mapNotNull { file -> number(file.name)?.takeIf { file.isFile && file.length() > 0 }?.let { it to file } }
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
     * is left. It is written in full under a name of its own that no listing reads, then renamed onto its claimed name,
     * so a reader never sees part of it, and two crashes at once never write into one file.
     */
    fun write(text: String): String? {
        var part: File? = null
        return try {
            dir.mkdirs()
            part = File.createTempFile(PART_PREFIX, PART_SUFFIX, dir)
            FileOutputStream(part).use {
                it.write(capped(text))
                it.fd.sync()
            }
            publish(part)
        } catch (_: IOException) {
            null
        } finally {
            part?.delete()
        }
    }

    private fun publish(part: File): String? {
        val mark = maxOf(savedMark(), newest())
        var at = maxOf(clock(), mark + 1, MIN_NUMBER)
        while (at <= MAX_NUMBER) {
            val claim = File(dir, "$at.txt")
            if (claim.createNewFile()) {
                if (!part.renameTo(claim)) {
                    claim.delete()
                    return null
                }
                trim()
                return claim.name
            }
            at++
        }
        return null
    }

    /** Removes all but the newest [MAX_FILES]; counted after a publish, so a file the core published meanwhile counts. */
    private fun trim() {
        val present = list()
        present.take((present.size - MAX_FILES).coerceAtLeast(0)).forEach { it.delete() }
    }

    /**
     * Removes what writes that never finished left, as when the system ended a process during one: the app's and the
     * core's parts, and names claimed but never filled. Only those older than [olderThanMs], so a write in progress,
     * in this process or the core's, keeps its own.
     */
    fun sweep(olderThanMs: Long = PART_AGE_MS) {
        val before = clock() - olderThanMs
        dir.listFiles { file -> file.isPart() || (number(file.name) != null && file.length() == 0L) }
            .orEmpty()
            .filter { it.lastModified() < before }
            .forEach { it.delete() }
    }

    private fun File.isPart() = name.endsWith(PART_SUFFIX) && (name.startsWith(PART_PREFIX) || name.startsWith(CORE_PART_PREFIX))

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

        /** The smallest 13-digit number, for a clock set before September 2001. */
        const val MIN_NUMBER = 1_000_000_000_000L

        /** The app's files in progress, `.app-<random>.tmp`; the core's are `.core-<pid>.tmp`. */
        private const val PART_PREFIX = ".app-"
        private const val CORE_PART_PREFIX = ".core-"
        private const val PART_SUFFIX = ".tmp"

        /** A part this old belongs to no write still running: a write takes milliseconds. */
        private const val PART_AGE_MS = 60_000L
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
