plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.api)
    implementation(localGroovy())
}

gradlePlugin {
    plugins {
        register("geoAssets") {
            id = "dev.doona.android.geo-assets"
            implementationClass = "dev.doona.android.buildlogic.GeoAssetsPlugin"
        }
    }
}
