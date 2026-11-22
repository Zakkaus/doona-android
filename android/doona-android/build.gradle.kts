buildscript {
    dependencies {
        // AGP's built-in Kotlin compiles with this Kotlin Gradle plugin instead of the older one it depends on.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless) apply false
}
