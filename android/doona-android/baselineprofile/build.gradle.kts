plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.spotless)
}

// Records the app's baseline profile on a phone: the journeys of BaselineProfileGenerator run on the profile build's
// non-minified twin (nonMinifiedProfile, made by the baselineprofile plugin), which carries the RV prototype and its
// demo data; release has no data to drive yet. The profile lands in app/src/main/generated/baselineProfiles, which
// release and profile both ship. How to run it: README.md.
android {
    namespace = "dev.doona.android.baselineprofile"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The app's profile build type; the baselineprofile plugin makes its non-minified twin here as in the app.
    buildTypes {
        create("profile") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

androidComponents {
    // Only the profile build has the demo data the journeys drive.
    beforeVariants { it.enable = it.buildType == "nonMinifiedProfile" }
    // The tested app's id, as installed (the profile build's suffix, or one a local build forces), for the generator.
    onVariants { variant ->
        val loader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put("targetAppId", variant.testedApks.map { loader.load(it)?.applicationId.orEmpty() })
    }
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
