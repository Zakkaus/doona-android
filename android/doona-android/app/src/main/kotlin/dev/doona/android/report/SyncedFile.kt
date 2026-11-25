package dev.doona.android.report

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Publishes a complete replacement; callers serialize writes to the same target. */
internal fun File.replaceSynced(bytes: ByteArray) {
    val part = File(parentFile, name + ".part")
    try {
        FileOutputStream(part).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!part.renameTo(this)) throw IOException("not renamed")
    } catch (e: IOException) {
        part.delete()
        throw e
    }
}
