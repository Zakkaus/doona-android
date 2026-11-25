package dev.doona.android.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

class GeoAssetsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.withPlugin("com.android.application") { project.configureGeoAssets() }
    }
}

private fun Project.configureGeoAssets() {
    val skipGeo = providers.gradleProperty("doona.skipGeo").orNull.toBoolean()
    val pins = rootProject.layout.projectDirectory.file("../../crates/doona-core/tools/geo-pins.json")
    val cache = gradle.gradleUserHomeDir.resolve("caches/doona-geo")
    val buildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
        description = "Generates BuildInfo from the pinned honk revision and the Geo release date."
        cargoToml.set(rootProject.layout.projectDirectory.file("../../crates/doona-core/Cargo.toml"))
        pinsFile.set(pins)
        outputDir.set(layout.buildDirectory.dir("generated/build-info"))
    }
    val generated = if (skipGeo) null else tasks.register<GenerateGeoAssets>("generateGeoAssets") {
        description = "Verifies the pinned GeoIP and GeoSite DATs and generates APK assets."
        pinsFile.set(pins)
        cacheDir.fileValue(cache)
        outputDir.set(layout.buildDirectory.dir("generated/geo-assets"))
    }
    extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(buildInfo, GenerateBuildInfo::outputDir)
        val release = variant.buildType == "release"
        if (!release && generated == null) return@onVariants
        val packageAssets = tasks.register<StageAssets>(variant.computeTaskName("stage", "GeoAssets")) {
            if (generated != null) geoAssets.set(generated.flatMap { it.outputDir })
            requireGeo.set(release)
        }
        variant.artifacts.use(packageAssets)
            .wiredWithDirectories(StageAssets::mergedAssets, StageAssets::outputDir)
            .toTransform(SingleArtifact.ASSETS)

        // Robolectric needs the merged app assets, not the Geo overlay used for packaging.
        val mergeTaskName = variant.computeTaskName("merge", "Assets")
        variant.hostTests.values.filter { it.androidResourcesIncluded }.forEach { test ->
            val plainAssets = tasks.register<StageAssets>(test.computeTaskName("stage", "PlainAssets")) {
                val merged = tasks.named(mergeTaskName).flatMap { it.outputs.files.elements }
                mergedAssets.set(layout.dir(merged.map { it.single().asFile }))
                requireGeo.set(false)
            }
            test.artifacts.use(plainAssets).wiredWith(StageAssets::outputDir).toCreate(SingleArtifact.ASSETS)
        }

        if (release) {
            val check = tasks.register<CheckGeoAssets>(variant.computeTaskName("check", "GeoAssets")) {
                description = "Checks that the release APK contains the pinned Geo assets."
                pinsFile.set(pins)
                report.set(layout.buildDirectory.file("intermediates/doona-geo/${variant.name}-verified.txt"))
            }
            variant.artifacts.use(check).wiredWith(CheckGeoAssets::apks).toListenTo(SingleArtifact.APK)
        }
    }
}
