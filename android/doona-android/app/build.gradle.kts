import com.android.build.api.artifact.SingleArtifact
import dev.doona.android.buildlogic.CheckNoFaultHook
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.spotless)
    id("dev.doona.android.geo-assets")
}

// libdoonacore.so (Rust, crates/doona-core) is built by cargo-ndk into a generated jniLibs directory of each
// variant, beside the NDK's libc++_shared.so when the library lists it as NEEDED. -Pdoona.skipCore=true leaves both
// out, for builds that never load the core (JVM tests, UI work).
abstract class CargoNdk : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

// The RV sample's launcher shortcuts name the package they open. Shortcut XML takes no manifest placeholder, and a
// string reference there resolves against the launcher's resources, not the app's; debug and profile differ in package
// (applicationIdSuffix), so each variant writes xml/rv_shortcuts from one template with its own application id.
abstract class RvShortcuts : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val template: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val xml = outputDir.get().dir("xml").asFile.resolve("rv_shortcuts.xml")
        xml.parentFile.mkdirs()
        xml.writeText(template.get().asFile.readText().replace("\${applicationId}", applicationId.get()))
    }
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
// Cargo's target directory as the environment sets it; each checkout builds the core in a directory of its own below
// it, and each feature set in its own below that. Cargo hashes a path package relative to its workspace, so two
// checkouts sharing one target directory build the same unit: one whose sources are older than the other's last build
// takes that build as fresh, and cargo-ndk packages the other checkout's library. The checkout's directory is named by
// the first 64 bits of the SHA-256 of its canonical path in lowercase hex: 16 characters however long the path, so two
// checkouts whose paths differ only in case stay apart on a target file system that ignores case.
val coreTarget = providers.environmentVariable("CARGO_TARGET_DIR").orNull?.let(::File) ?: coreDir.resolve("target")
val coreCheckout = MessageDigest.getInstance("SHA-256")
    .digest(coreDir.canonicalFile.parentFile.parentFile.path.toByteArray())
    .take(8)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
val skipCore = providers.gradleProperty("doona.skipCore").orNull.toBoolean()
val coreProfile = providers.gradleProperty("doona.coreProfile").getOrElse("release")

androidComponents {
    onVariants { variant ->
        if (skipCore) return@onVariants
        // The debug variant's core also takes `call("debugFault")`, the fault the core-failure check uses; release has none.
        val features = if (variant.buildType == "debug") listOf("--features", "fault-hook") else emptyList()
        // One target directory per feature set: in a shared one, a build of the other set would overwrite the library
        // that cargo-ndk copies, and each switch between debug and release would rebuild the core.
        val featureSet = if (features.isEmpty()) "core" else "core-fault-hook"
        val targetDir = coreTarget.resolve("android/$coreCheckout/$featureSet")
        val buildCore = tasks.register<CargoNdk>("buildDoonaCore" + variant.name.replaceFirstChar { it.uppercase() }) {
            description = "Builds libdoonacore.so for arm64-v8a with cargo-ndk and checks its JNI exports."
            workingDir = coreDir // rustup reads rust-toolchain.toml from the working directory
            executable = cargo
            argumentProviders.add {
                listOf(
                    "ndk", "-t", "arm64-v8a", "--platform", "31",
                    "-o", outputDir.get().asFile.absolutePath,
                    // Cargo applies `lto` only when every crate type of the unit supports it; the manifest's `rlib`
                    // (for the host tests) would turn fat LTO off, so build the cdylib alone.
                    "rustc", "--lib", "--crate-type", "cdylib", "--profile", coreProfile, "--locked",
                ) + features
            }
            environment("PATH", cargoBin + File.pathSeparator + System.getenv("PATH"))
            environment("CARGO_TARGET_DIR", targetDir.path)
            // boring-sys runs bindgen, which needs the target's API level.
            environment("BINDGEN_EXTRA_CLANG_ARGS_aarch64-linux-android", "--target=aarch64-linux-android31")
            // Everything that changes the library, so an unchanged crate is not rebuilt. The release profile and
            // the dependency pins live in Cargo.toml and Cargo.lock; the git dependencies are pinned by revision.
            inputs.dir(coreDir.resolve("src")).withPropertyName("sources").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(
                listOf("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", ".cargo/config.toml").map(coreDir::resolve),
            ).withPropertyName("manifests").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.property("ndk", coreNdk)
            inputs.property("features", features)
            inputs.property("profile", coreProfile)
            inputs.property("target", "arm64-v8a android-31")
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
        if (variant.buildType != "debug") {
            val name = variant.name.replaceFirstChar { it.uppercase() }
            val check = tasks.register<CheckNoFaultHook>("checkNoFaultHook$name") {
                description = "Checks that the APK's libdoonacore.so has no fault hook."
                apks.set(variant.artifacts.get(SingleArtifact.APK))
                report.set(layout.buildDirectory.file("intermediates/doona-core/${variant.name}-no-fault-hook.txt"))
            }
            tasks.named { it == "assemble$name" }.configureEach { dependsOn(check) }
        }
    }
    // The profile build carries the debug source set: the RV prototype, its assets and its launcher entry, and a
    // baseline profile. Without it the prototype ran interpreted on the phone (composables called a few dozen times a
    // gesture never got hot enough for the JIT) and the JIT compiled for about 1.1 s of each 5 s trace (rv-perf3). It
    // compiles every method of the app and of Compose, androidx.collection and coroutines ahead: a stand-in for a
    // profile recorded on the phone with the Macrobenchmark generator, which this build does not have yet.
    onVariants(selector().withBuildType("profile")) { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("src/debug/kotlin")
        variant.sources.kotlin?.addStaticSourceDirectory("src/fake/kotlin") // the samples' fake engine
        variant.sources.assets?.addStaticSourceDirectory("src/debug/assets")
        variant.sources.manifests.addStaticManifestFile("src/debug/AndroidManifest.xml")
        variant.sources.baselineProfiles?.addStaticSourceDirectory("src/debug/baselineProfiles")
    }
    // The debug source set's launcher shortcuts, written for each variant that carries the RV sample (RvShortcuts).
    onVariants { variant ->
        if (variant.buildType != "debug" && variant.buildType != "profile") return@onVariants
        val shortcuts = tasks.register<RvShortcuts>("rvShortcuts" + variant.name.replaceFirstChar { it.uppercase() }) {
            template.set(layout.projectDirectory.file("src/debug/shortcuts/rv_shortcuts.xml"))
            applicationId.set(variant.applicationId)
        }
        variant.sources.res?.addGeneratedSourceDirectory(shortcuts, RvShortcuts::outputDir)
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
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        // The core's subscription download bound, from the file its fetch reads, for 新增訂閱's 太大 notice.
        val maxBody = providers.fileContents(rootProject.layout.projectDirectory.file("../../crates/doona-core/src/subscription/max-body-bytes"))
        buildConfigField("long", "SUBSCRIPTION_MAX_BODY_BYTES", maxBody.asText.get().trim() + "L")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    // Ship strings only in the app's own locales (res/xml/locales_config.xml). The libraries carry about 80 more, which
    // would only put a translated system string next to an English screen.
    androidResources {
        localeFilters += listOf("en", "zh-rCN", "zh-rTW")
    }
    // The fake engine and its sample data (src/fake) serve the previews, the debug samples and the unit tests (debug
    // only), so no release build compiles them. The profile build gets them with the debug sources (androidComponents).
    sourceSets {
        getByName("debug").kotlin.directories += "src/fake/kotlin"
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
        // numbers are not decisions. The source set is added below, under androidComponents. It installs as a package of
        // its own beside doona, with its own launcher name (src/profile): sharing dev.doona.android, an install or the
        // benchmark's uninstall after connectedProfileAndroidTest wiped doona's data on the phone (2026-11-23).
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profile"
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
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Whether a newer version exists depends on the date, not the change: Dependabot opens those bumps.
    lint {
        informational += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                System.getProperty("doona.shots")?.let { dir -> it.systemProperty("doona.shots", dir) }
                System.getProperty("doona.board")?.let { dir -> it.systemProperty("doona.board", dir) }
                System.getProperty("doona.rowrules")?.let { mode -> it.systemProperty("doona.rowrules", mode) }
            }
        }
    }
}

dependencies {
    implementation(project(":icons"))
    implementation(project(":flags"))
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
    implementation(libs.work.runtime)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit)
    testImplementation(libs.snakeyaml)
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
