package dev.doona.android.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipFile

// Inspect the APK so the check sees the native library that will actually ship.
@DisableCachingByDefault(because = "The verification report is local to the APK build")
abstract class CheckNoFaultHook : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apks: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val libraries = mutableListOf<String>()
        for (apk in apks.get().asFile.walk().filter { it.extension == "apk" }) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries().asSequence().filter { it.name.endsWith("/libdoonacore.so") }) {
                    val name = "${apk.name}!${entry.name}"
                    // The name the hook answers to; the release library has no string of it.
                    val binary = zip.getInputStream(entry).use { it.readBytes() }
                    if ("debugFault" in String(binary, Charsets.ISO_8859_1)) {
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
