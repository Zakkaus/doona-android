plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.spotless)
}

// libdoonacore.so (Rust, crates/doona-core) is built by cargo-ndk into a generated jniLibs directory of each
// variant, beside the NDK's libc++_shared.so when the library lists it as NEEDED. -Pdoona.skipCore=true leaves both
// out, for builds that never load the core (JVM tests, UI work).
abstract class CargoNdk : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val coreDir = rootProject.file("../../crates/doona-core")
val coreNdk = "30.0.16248370"
val coreSymbols = listOf("init", "start", "stop", "snapshot", "call")
    .map { "Java_dev_doona_android_vpn_NativeCore_$it" }
    .toSet()
val cargoBin = (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"
// rustup's default location when present, so a Gradle daemon started without cargo on PATH works.
val cargo = File(cargoBin, "cargo").takeIf { it.canExecute() }?.path ?: "cargo"
val ndkDir = androidComponents.sdkComponents.ndkDirectory
val skipCore = providers.gradleProperty("doona.skipCore").orNull.toBoolean()

androidComponents {
    onVariants { variant ->
        if (skipCore) return@onVariants
        val buildCore = tasks.register<CargoNdk>("buildDoonaCore" + variant.name.replaceFirstChar { it.uppercase() }) {
            description = "Builds libdoonacore.so for arm64-v8a with cargo-ndk and checks its JNI exports."
            workingDir = coreDir // rustup reads rust-toolchain.toml from the working directory
            executable = cargo
            argumentProviders.add {
                listOf(
                    "ndk", "-t", "arm64-v8a", "--platform", "29",
                    "-o", outputDir.get().asFile.absolutePath,
                    // Cargo applies `lto` only when every crate type of the unit supports it; the manifest's `rlib`
                    // (for the host tests) would turn fat LTO off, so build the cdylib alone.
                    "rustc", "--lib", "--crate-type", "cdylib", "--release", "--locked",
                )
            }
            environment("PATH", cargoBin + File.pathSeparator + System.getenv("PATH"))
            // boring-sys runs bindgen, which needs the target's API level.
            environment("BINDGEN_EXTRA_CLANG_ARGS_aarch64-linux-android", "--target=aarch64-linux-android29")
            // Everything that changes the library, so an unchanged crate is not rebuilt. The release profile and
            // the dependency pins live in Cargo.toml and Cargo.lock; the git dependencies are pinned by revision.
            inputs.dir(coreDir.resolve("src")).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(
                listOf("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/config.toml").map(coreDir::resolve),
            ).withPropertyName("manifests").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.property("ndk", coreNdk)
            inputs.property("target", "arm64-v8a android-29")
            doFirst {
                // cargo-ndk 4.1.2 keeps a copy in -o that is newer than Cargo's artifact and never removes other
                // files, so either would be packaged: start from an empty directory.
                outputDir.get().asFile.listFiles()?.forEach { it.deleteRecursively() }
                environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            }
            doLast {
                val abiDir = outputDir.get().dir("arm64-v8a").asFile
                val tools = ndkDir.get().dir("toolchains/llvm/prebuilt").asFile.listFiles().orEmpty().single()
                val library = File(abiDir, "libdoonacore.so").path
                fun tool(name: String, vararg args: String): String {
                    val process = ProcessBuilder(File(tools, "bin/$name").path, *args, library)
                        .redirectErrorStream(true)
                        .start()
                    val output = process.inputStream.bufferedReader().readText()
                    if (process.waitFor() != 0) throw GradleException("$name failed on libdoonacore.so:\n$output")
                    return output
                }
                // The C++ runtime ships only when the library links it, and then it must ship: the loader would
                // fail on a phone.
                val needed = Regex("""\(NEEDED\)\s+Shared library: \[([^\]]+)]""")
                    .findAll(tool("llvm-readelf", "-d"))
                    .map { it.groupValues[1] }
                    .toSet()
                if ("libc++_shared.so" in needed) {
                    val runtime = File(tools, "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
                    if (!runtime.isFile) throw GradleException("libdoonacore.so needs libc++_shared.so; not in the NDK")
                    runtime.copyTo(File(abiDir, "libc++_shared.so"), overwrite = true)
                }
                // A missing export only fails when Kotlin first calls it, on a phone: check here.
                val exported = Regex("""\bJava_dev_doona_android_vpn_NativeCore_\w+""")
                    .findAll(tool("llvm-nm", "-D", "--defined-only"))
                    .map { it.value }
                    .toSet()
                if (exported != coreSymbols) {
                    throw GradleException("libdoonacore.so exports $exported; NativeCore needs exactly $coreSymbols")
                }
            }
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildCore, CargoNdk::outputDir)
    }
    // The profile build carries the debug source set: the RV prototype, its resources and its launcher entry.
    onVariants(selector().withBuildType("profile")) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("src/debug/kotlin")
        variant.sources.res?.addStaticSourceDirectory("src/debug/res")
        variant.sources.assets?.addStaticSourceDirectory("src/debug/assets")
        variant.sources.manifests.addStaticManifestFile("src/debug/AndroidManifest.xml")
    }
}

android {
    namespace = "dev.doona.android"
    // material3 1.5.0 needs compileSdk 37.1, as in ../ui-preview.
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    // The NDK libdoonacore.so is built with, as in ../tunbench.
    ndkVersion = coreNdk

    defaultConfig {
        applicationId = "dev.doona.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The debug key lets a release APK install locally; a store release is signed elsewhere.
            signingConfig = signingConfigs.getByName("debug")
        }
        // A release build (R8, not debuggable) that also carries the debug source set's prototype, so frame times
        // measured on a phone mean what a user would see; profileable, so Perfetto can trace it. Debug Compose
        // numbers are not decisions. The source set is added below, under androidComponents.
        create("profile") {
            initWith(getByName("release"))
            isProfileable = true
            matchingFallbacks += "release"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                System.getProperty("doona.shots")?.let { dir -> it.systemProperty("doona.shots", dir) }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.material.components)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.roborazzi.accessibility)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.compose.ui.tooling)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(mapOf("android" to "true"))
        endWithNewline()
    }
    format("kts") {
        target("*.kts")
        endWithNewline()
    }
    format("xml") {
        target("src/**/*.xml")
        endWithNewline()
    }
}
