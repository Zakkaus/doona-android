package dev.doona.android.data.geo

import android.content.Context
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.time.LocalDate
import kotlin.coroutines.coroutineContext

internal class GeoAssets(private val noBackupDir: () -> File, private val open: (String) -> InputStream) {
    constructor(context: Context) : this({ context.noBackupFilesDir }, { context.assets.open(it) })

    /** Called on the controller's IO lane, which serializes access to the staging directory. */
    suspend fun prepare(activeRelease: () -> String?): File? = try {
        stageBundle(activeRelease)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        VpnLog.warn("geo-assets: APK offer failed (${e.javaClass.simpleName})")
        null
    } catch (e: LinkageError) {
        VpnLog.warn("geo-assets: APK offer failed (${e.javaClass.simpleName})")
        null
    }

    private suspend fun stageBundle(activeRelease: () -> String?): File? {
        val stage = noBackupDir().resolve("geo-apk-stage")
        if (!stage.deleteRecursively()) throw IOException("cannot clear geo stage")
        val pins = try {
            open("geo/pins.json").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            // Offline builds can omit the generated assets with -Pdoona.skipGeo=true.
            return null
        }
        val apk = LocalDate.parse(json.decodeFromString<Pins>(pins.decodeToString()).releaseDate)
        val active = activeRelease()?.let(LocalDate::parse)
        if (active != null && apk <= active) return null

        coroutineContext.ensureActive()
        Files.createDirectory(stage.toPath())
        try {
            stage.resolve("pins.json").writeBytes(pins)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            copy("geoip.dat", stage, buffer)
            copy("geosite.dat", stage, buffer)
            return stage
        } catch (e: Exception) {
            stage.deleteRecursively()
            throw e
        }
    }

    private suspend fun copy(name: String, stage: File, buffer: ByteArray) {
        val context = coroutineContext
        context.ensureActive()
        open("geo/$name").use { source ->
            stage.resolve(name).outputStream().use { target ->
                while (true) {
                    context.ensureActive()
                    val count = source.read(buffer)
                    context.ensureActive()
                    if (count < 0) break
                    target.write(buffer, 0, count)
                }
            }
        }
    }

    fun discard(stage: File) {
        if (!stage.deleteRecursively()) VpnLog.warn("geo-assets: could not delete APK staging directory")
    }

    @Serializable
    private data class Pins(@SerialName("release_date") val releaseDate: String)

    private val json = Json { ignoreUnknownKeys = true }
}
