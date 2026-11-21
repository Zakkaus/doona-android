buildscript {
    dependencies {
        // AGP's built-in Kotlin compiles with this Kotlin Gradle plugin instead of the older one
        // it depends on (https://developer.android.com/build/releases/agp-9-0-0-release-notes).
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.spotless)
}

// Now in Android's Kotlin formatting (build-logic Spotless.kt) for the one module. Its kts and xml
// rules only require a final newline, which every file here has.
spotless {
    kotlin {
        target("app/src/**/*.kt")
        targetExclude("app/src/main/jni/**")
        ktlint(libs.versions.ktlint.get())
        endWithNewline()
    }
}

// Spotless passes when its target matches no file; a wrong glob must fail instead.
tasks.withType<com.diffplug.gradle.spotless.SpotlessTask>().configureEach {
    doFirst { check(!target.isEmpty) { "$name: the target matches no file" } }
}
