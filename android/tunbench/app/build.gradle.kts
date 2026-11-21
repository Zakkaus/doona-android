plugins {
    alias(libs.plugins.android.application)
}

// libhonkengine.so (Rust, crates/honk-android) is built by cargo-ndk and registered as a generated
// jniLibs directory of each variant, so only tasks that package native libraries run cargo.
abstract class CargoNdk : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val honkEngineDir = rootProject.file("../../crates/honk-android")
val cargoBin = (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"
// rustup's default location when present, so a Gradle daemon started without cargo on PATH works.
val cargo = File(cargoBin, "cargo").takeIf { it.canExecute() }?.path ?: "cargo"
val ndkDir = androidComponents.sdkComponents.ndkDirectory

androidComponents {
    onVariants { variant ->
        val buildHonkEngine = tasks.register<CargoNdk>(
            "buildHonkEngine" + variant.name.replaceFirstChar { it.uppercase() },
        ) {
            description = "Builds libhonkengine.so (with the bench runner) for arm64-v8a with cargo-ndk."
            workingDir = honkEngineDir // rustup reads rust-toolchain.toml from the working directory
            executable = cargo
            argumentProviders.add {
                listOf(
                    "ndk", "-t", "arm64-v8a", "--platform", "29",
                    "-o", outputDir.get().asFile.absolutePath,
                    // Cargo applies `lto` only when every crate type of the unit supports it; the
                    // manifest's `rlib` (for the host tests) would turn fat LTO off, so build the
                    // cdylib alone.
                    "rustc", "--lib", "--crate-type", "cdylib", "--release", "--locked", "--features", "bench",
                )
            }
            environment("PATH", cargoBin + File.pathSeparator + System.getenv("PATH"))
            doFirst {
                // cargo-ndk 4.1.2 keeps a copy in -o that is newer than Cargo's artifact and never
                // removes other files, so either would be packaged: start from an empty directory.
                outputDir.get().asFile.listFiles()?.forEach { it.deleteRecursively() }
                environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath)
            }
            // Always runs: Cargo decides what to rebuild (it tracks the crate sources and Cargo.lock).
            outputs.upToDateWhen { false }
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildHonkEngine, CargoNdk::outputDir)
    }
}

android {
    namespace = "dev.tunbench"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "dev.tunbench"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            // The bench measures arm64 phones only.
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            ndkBuild {
                // Build only the JNI library, not hev's standalone executable.
                targets += "hev-socks5-tunnel"
                // Optimized native code even in the debug APK: this is a benchmark.
                arguments += listOf(
                    "NDK_DEBUG=0",
                    "NDK_APPLICATION_MK=" + file("src/main/jni/Application.mk").absolutePath,
                )
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No baseline file: a warning is fixed, or suppressed where it occurs with the reason.
    lint {
        warningsAsErrors = true
    }

    // The manifest test reads the merged manifest named in test_config.properties.
    testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
}
