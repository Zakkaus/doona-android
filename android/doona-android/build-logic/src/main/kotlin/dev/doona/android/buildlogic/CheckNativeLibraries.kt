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

// Inspect the APK so the check sees the native library that will actually ship.
@DisableCachingByDefault(because = "The verification report is local to the APK build")
abstract class CheckNativeLibraries : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val readelf: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val libraries = mutableListOf<String>()
        val library = temporaryDir.resolve("library.so")
        for (apk in apks.get().asFile.walk().filter { it.extension == "apk" }) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }) {
                    val name = "${apk.name}!${entry.name}"
                    zip.getInputStream(entry).use { input -> library.outputStream().use { input.copyTo(it) } }
                    val process = ProcessBuilder(readelf.get().asFile.path, "-lW", library.path).redirectErrorStream(true).start()
                    val headers = process.inputStream.bufferedReader().use { it.readText() }
                    if (process.waitFor() != 0) throw GradleException("llvm-readelf failed on $name:\n$headers")
                    val minAlignment = headers.lineSequence().filter { it.trimStart().startsWith("LOAD ") }
                        .map { it.substringAfterLast(' ').removePrefix("0x").toLong(16) }.minOrNull()
                    if (minAlignment == null || minAlignment < 0x4000) throw GradleException("$name has no LOAD segments or a LOAD alignment below 16 KiB")
                    if (!entry.name.endsWith("/libdoonacore.so")) continue
                    // The name the hook answers to; the release library has no string of it.
                    if ("debugFault" in library.readText(Charsets.ISO_8859_1)) {
                        throw GradleException("$name is the debug core: it carries the fault hook")
                    }
                    libraries.add(name)
                }
            }
        }
        if (libraries.isEmpty()) throw GradleException("no libdoonacore.so in the APKs under ${apks.get().asFile}")
        report.get().asFile.writeText(libraries.joinToString("\n", postfix = "\n") { "$it: no fault hook" })
    }
}
