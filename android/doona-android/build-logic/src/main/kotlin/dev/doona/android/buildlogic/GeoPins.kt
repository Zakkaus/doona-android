package dev.doona.android.buildlogic

import groovy.json.JsonException
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.net.URI
import java.net.URISyntaxException

internal val sha256Pattern = Regex("[0-9a-f]{64}")

internal data class GeoPin(val name: String, val source: URI, val size: Long, val sha256: String)

internal class GeoPins(val bytes: ByteArray, val files: List<GeoPin>) {
    companion object {
        fun read(file: File): GeoPins {
            val bytes = file.readBytes()
            fun invalid(reason: String): Nothing = throw GradleException("Invalid Geo pins in $file: $reason")
            if (bytes.isEmpty()) invalid("expected a JSON object")
            val manifest = try {
                JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
            } catch (e: JsonException) {
                throw GradleException("Invalid Geo pins in $file: malformed JSON", e)
            } ?: invalid("expected a JSON object")
            if (manifest["version"] != 1) invalid("expected schema version 1")
            val files = manifest["files"] as? Map<*, *> ?: invalid("files must be an object")
            if (files.size != 2) invalid("files must contain two DAT pins")
            return GeoPins(bytes, files.map { (name, value) ->
                if (name !is String || !name.matches(Regex("[a-z0-9_-]+\\.dat"))) invalid("invalid DAT name: $name")
                val pin = value as? Map<*, *> ?: invalid("$name must be an object")
                val sha = pin["sha256"] as? String
                if (sha == null || !sha.matches(sha256Pattern)) invalid("$name.sha256 must be 64 lowercase hex digits")
                val size = when (val raw = pin["size_bytes"]) {
                    is Int -> raw.toLong()
                    is Long -> raw
                    else -> invalid("$name.size_bytes must be a positive integer")
                }
                if (size <= 0) invalid("$name.size_bytes must be a positive integer")
                val address = pin["source"] as? String ?: invalid("$name.source must be an HTTP(S) URL")
                val source = try {
                    URI(address)
                } catch (_: URISyntaxException) {
                    invalid("$name.source must be an HTTP(S) URL")
                }
                if (source.scheme !in listOf("http", "https") || source.host == null) {
                    invalid("$name.source must be an HTTP(S) URL")
                }
                GeoPin(name, source, size, sha)
            })
        }
    }
}
