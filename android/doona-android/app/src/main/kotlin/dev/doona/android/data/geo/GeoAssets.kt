package dev.doona.android.data.geo

import android.content.Context
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.file.Files

internal class GeoAssets(private val noBackupDir: () -> File, private val open: (String) -> InputStream) {
    constructor(context: Context) : this({ context.noBackupFilesDir }, { context.assets.open(it) })

    /** The controller serializes starts, so a stage already present is from an earlier attempt. */
    suspend fun <T> withBundle(activeRelease: () -> String?, start: (String?) -> T): T = withContext(Dispatchers.IO) {
        val stage = noBackupDir().resolve("geo-apk-stage")
        deleteStage(stage)
        val pins = try {
            open("geo/pins.json").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            // Offline builds can omit the generated assets with -Pdoona.skipGeo=true.
            return@withContext start(null)
        }
        val apk = json.decodeFromString<Pins>(pins.decodeToString()).releaseDate
        val active = activeRelease()
        if (active != null && apk <= active) return@withContext start(null)

        Files.createDirectory(stage.toPath())
        try {
            stage.resolve("pins.json").writeBytes(pins)
            copy("geoip.dat", stage)
            copy("geosite.dat", stage)
            start(stage.path)
        } finally {
            deleteStage(stage)
        }
    }

    private fun copy(name: String, stage: File) {
        open("geo/$name").use { source -> stage.resolve(name).outputStream().use { source.copyTo(it) } }
    }

    private fun deleteStage(stage: File) {
        if (!stage.deleteRecursively()) VpnLog.warn("geo-assets: could not delete APK staging directory")
    }

    @Serializable
    private data class Pins(@SerialName("release_date") val releaseDate: String)

    private val json = Json { ignoreUnknownKeys = true }
}
