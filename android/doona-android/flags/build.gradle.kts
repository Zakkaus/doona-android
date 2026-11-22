plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.spotless)
}

// Every circle-flags flag as a vector drawable (flag_*) and flagResource(code), the generated lookup from a country or
// region code to its drawable, both from tools/gen_flags.py. A flag is chosen by code at run time, so a release build
// keeps all of them once the table is reachable; the app's size report says what that costs.
android {
    namespace = "dev.doona.android.flags"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 31
    }

    resourcePrefix = "flag_"
}

dependencies {
    testImplementation(libs.junit)
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
}
