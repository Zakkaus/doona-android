package dev.doona.android.report

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether to offer the report after a crash: once per new crash, from the first [check] of a process. A crash is new when
 * its file's number is above the number kept in [seen], which the offer raises as it is made, so a crash is offered once
 * even when the user leaves without answering. The core's crash files and the app's count alike.
 */
class CrashOffer(private val files: CrashFiles, private val seen: File) {
    private val shown = MutableStateFlow(false)

    /** True while the offer is up: from a [check] that found a new crash until [dismiss]. */
    val offered: StateFlow<Boolean> = shown.asStateFlow()

    private val checked = AtomicBoolean(false)

    /** Reads the crash files, on the first call in a process only; it reads the disk, so call it off the main thread. */
    fun check() {
        if (!checked.compareAndSet(false, true)) return
        val newest = files.newest()
        if (newest <= seenNumber()) return
        try {
            seen.writeText(newest.toString())
        } catch (_: IOException) {
            // Unsaved, the crash is offered again next time, which beats never offering it.
        }
        shown.value = true
    }

    fun dismiss() {
        shown.value = false
    }

    private fun seenNumber(): Long = try {
        seen.readText().trim().toLongOrNull() ?: 0
    } catch (_: IOException) {
        0
    }
}
