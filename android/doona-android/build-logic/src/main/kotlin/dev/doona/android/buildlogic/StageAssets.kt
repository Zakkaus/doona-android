package dev.doona.android.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import javax.inject.Inject

@DisableCachingByDefault(because = "Stages local assets for Android packaging or host tests")
abstract class StageAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val mergedAssets: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val geoAssets: DirectoryProperty

    @get:Input
    abstract val requireGeo: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun stage() {
        val geo = geoAssets.orNull
        if (requireGeo.get() && geo == null) {
            throw GradleException("Release builds require Geo assets; remove -Pdoona.skipGeo=true")
        }
        fileSystem.sync {
            from(mergedAssets) { if (geo != null) exclude("geo/**") }
            into(outputDir)
        }
        if (geo != null) {
            // Both directories are under the app build directory. Link the shared, read-only DATs.
            val destination = Files.createDirectories(outputDir.get().dir("geo").asFile.toPath())
            Files.list(geo.dir("geo").asFile.toPath()).use { files ->
                files.forEach { Files.createLink(destination.resolve(it.fileName), it) }
            }
        }
    }
}
