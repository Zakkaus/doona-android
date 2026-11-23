package dev.doona.android.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Whether to offer the report after a crash: once per new crash, from the first [check] of a process. A crash is new when
 * its file's number is above the number kept in [seen]. That number is raised only once the offer reached the screen
 * ([shown]): a process that ends first, or a page that never draws the offer, leaves the crash to be offered at the next
 * launch, and an offer the user saw is not made again even when they left without answering. The core's crash files
 * and the app's count alike.
 */
class CrashOffer(private val files: CrashFiles, locate: () -> File) {
    constructor(files: CrashFiles, seen: File) : this(files, { seen })

    /** The file, found on first use, as [CrashFiles.dir] is. */
    private val seen: File by lazy(locate)

    private val up = MutableStateFlow(false)

    /** True while the offer is up: from a [check] that found a new crash until [dismiss]. */
    val offered: StateFlow<Boolean> = up.asStateFlow()

    private val checked = AtomicBoolean(false)

    /** The number [shown] saves; 0 once saved, or with nothing offered. */
    private val pending = AtomicLong(0)

    /** Reads the crash files, on the first call in a process only; it reads the disk, so call it off the main thread. */
    fun check() {
        if (!checked.compareAndSet(false, true)) return
        val newest = files.newest()
        if (newest <= seenNumber()) return
        pending.set(newest)
        up.value = true
    }

    /**
     * The offer reached the screen: saves its crash as seen, once however often it is called. The number replaces the
     * file whole, so a process that ends meanwhile leaves the old number or the new one. It writes the disk, so call it
     * off the main thread.
     */
    fun shown() {
        val number = pending.getAndSet(0)
        if (number == 0L) return
        val part = File(seen.path + PART)
        try {
            part.writeText(number.toString())
            if (!part.renameTo(seen)) part.delete()
        } catch (_: IOException) {
            // Unsaved, the crash is offered again next time, which beats never offering it.
        }
    }

    fun dismiss() {
        up.value = false
    }

    private fun seenNumber(): Long = try {
        seen.readText().trim().toLongOrNull() ?: 0
    } catch (_: IOException) {
        0
    }

    private companion object {
        const val PART = ".part"
    }
}
