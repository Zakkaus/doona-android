plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(localGroovy())
    testImplementation(libs.junit)
    testImplementation(localGroovy())
}

gradlePlugin {
    plugins {
        register("geoAssets") {
            id = "dev.doona.android.geo-assets"
            implementationClass = "dev.doona.android.buildlogic.GeoAssetsPlugin"
        }
    }
}
