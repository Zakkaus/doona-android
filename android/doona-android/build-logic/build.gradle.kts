plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(localGroovy())
}

gradlePlugin {
    plugins {
        register("geoAssets") {
            id = "dev.doona.android.geo-assets"
            implementationClass = "dev.doona.android.buildlogic.GeoAssetsPlugin"
        }
    }
}
