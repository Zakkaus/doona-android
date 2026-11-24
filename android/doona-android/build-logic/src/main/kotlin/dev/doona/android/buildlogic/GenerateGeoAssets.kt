package dev.doona.android.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject

@DisableCachingByDefault(because = "Verified downloads are shared through the Gradle user home")
abstract class GenerateGeoAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pinsFile: RegularFileProperty

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun generate() {
        val pins = GeoPins.read(pinsFile.get().asFile)
        val cache = cacheDir.get().asFile.toPath()
        val output = outputDir.get().asFile
        fileSystem.delete { delete(output) }
        val geo = Files.createDirectories(output.resolve("geo").toPath())
        Files.createDirectories(cache)

        // Hold the lock through the copy so another checkout cannot prune entries we are using.
        FileChannel.open(cache.resolve("download.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                pruneCache(cache, pins.files.mapTo(mutableSetOf(), GeoPin::sha256))
                for (pin in pins.files) {
                    val cached = cache.resolve(pin.sha256)
                    if (Files.exists(cached)) {
                        mismatch(cached, pin)?.let {
                            logger.warn("$it; downloading a fresh copy")
                            Files.delete(cached)
                        }
                    }
                    if (!Files.exists(cached)) download(cached, pin)
                    // Pruning goes by age, so a checkout still using this entry keeps it fresh.
                    Files.setLastModifiedTime(cached, FileTime.from(Instant.now()))
                    Files.copy(cached, geo.resolve(pin.name))
                }
            }
        }
        Files.write(geo.resolve("pins.json"), pins.bytes)
    }

    // Other checkouts sharing the Gradle home may pin other releases, so an entry these pins do
    // not name is removed only once no build has used it for CACHE_TTL. A .tmp file seen here is
    // stale: downloads run under the same lock.
    private fun pruneCache(cache: Path, active: Set<String>) {
        val cutoff = Instant.now().minus(CACHE_TTL)
        Files.list(cache).use { entries ->
            entries.forEach { entry ->
                val name = entry.fileName.toString()
                val unused = name.matches(sha256Pattern) && name !in active &&
                    Files.getLastModifiedTime(entry).toInstant() < cutoff
                if (name.endsWith(".tmp") || unused) {
                    Files.delete(entry)
                }
            }
        }
    }

    private fun download(cached: Path, pin: GeoPin) {
        val temp = Files.createTempFile(cached.parent, "${pin.sha256}-", ".tmp")
        try {
            fetch(pin, temp)
            mismatch(temp, pin)?.let { throw GradleException(it) }
            Files.move(temp, cached, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun fetch(pin: GeoPin, temp: Path) {
        for (attempt in 1..3) {
            val connection = (pin.source.toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            val failure = try {
                val status = connection.responseCode
                if (status == 408 || status == 429 || status in 500..599) throw IOException("HTTP $status")
                if (status != 200) throw GradleException("Could not download Geo ${pin.name} from ${pin.source}: HTTP $status")
                connection.inputStream.use { Files.copy(it, temp, REPLACE_EXISTING) }
                return
            } catch (e: IOException) {
                e
            } finally {
                connection.disconnect()
            }
            if (attempt == 3) throw GradleException("Could not download Geo ${pin.name} from ${pin.source} after $attempt attempts", failure)
            logger.warn("Geo ${pin.name} download attempt $attempt failed: ${failure.message}; retrying")
            Thread.sleep(1000L * attempt)
        }
    }

    private fun mismatch(file: Path, pin: GeoPin): String? {
        val size = Files.size(file)
        if (size != pin.size) return "Geo ${pin.name} size mismatch at $file: expected ${pin.size} bytes, got $size"
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(file), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        val sha = HexFormat.of().formatHex(digest.digest())
        return if (sha == pin.sha256) null else "Geo ${pin.name} SHA-256 mismatch at $file: expected ${pin.sha256}, got $sha"
    }
}

private val CACHE_TTL: Duration = Duration.ofDays(30)
