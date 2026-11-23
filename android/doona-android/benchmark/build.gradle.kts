plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.spotless)
}

// Macrobenchmarks of the RV prototype's gestures on a phone, against the app's profile build (R8, not debuggable,
// profileable, the RV prototype included): frame timing and Perfetto's App Deadline Missed count, as the perf rounds
// measured by hand. Run with
//   ./gradlew -Pdoona.skipCore=true :benchmark:connectedProfileAndroidTest
// See RvGestureBenchmark for one gesture at a time and where the results land.
android {
    namespace = "dev.doona.android.benchmark"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Matches the app's profile build type, which this module installs and measures.
    buildTypes {
        create("profile") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    // Only the profile build is measured: a debug app's frames are no decision.
    beforeVariants { it.enable = it.buildType == "profile" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
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
