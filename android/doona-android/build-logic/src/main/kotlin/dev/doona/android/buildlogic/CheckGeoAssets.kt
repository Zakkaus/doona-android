package dev.doona.android.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipFile

@DisableCachingByDefault(because = "The verification report is local to the APK build")
abstract class CheckGeoAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinsFile: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val pins = GeoPins.read(pinsFile.get().asFile)
        val apkDir = apks.get().asFile
        val files = apkDir.walk().filter { it.isFile && it.extension == "apk" }.toList()
        if (files.isEmpty()) throw GradleException("No APKs to check for Geo assets in $apkDir")
        for (apk in files) {
            ZipFile(apk).use { zip ->
                for (pin in pins.files) checkSize(zip, pin.name, pin.size)
                val entry = checkSize(zip, "pins.json", pins.bytes.size.toLong())
                if (!zip.getInputStream(entry).use { it.readBytes() }.contentEquals(pins.bytes)) {
                    throw GradleException("${zip.name}: assets/geo/pins.json differs from the Geo pin file")
                }
            }
        }
        report.get().asFile.writeText(files.joinToString("\n", postfix = "\n") { "${it.name}: Geo assets verified" })
    }

    private fun checkSize(zip: ZipFile, file: String, expected: Long): java.util.zip.ZipEntry {
        val path = "assets/geo/$file"
        val entry = zip.getEntry(path) ?: throw GradleException("${zip.name}: missing $path")
        if (entry.size != expected) {
            throw GradleException("${zip.name}: $path size mismatch: expected $expected bytes, got ${entry.size}")
        }
        return entry
    }
}
