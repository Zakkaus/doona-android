package dev.doona.android.data.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The app's GitHub repository: its page, its releases page, its privacy notice (`PRIVACY.md` at the repository's root,
 * on the default branch), and the latest release's tag, which 檢查更新 asks for.
 */
object UpdateCheck {
    private const val REPOSITORY = "https://github.com/Zakkaus/doona-android"
    internal const val PRIVACY = "$REPOSITORY/blob/HEAD/PRIVACY.md"
    internal const val LATEST = "https://api.github.com/repos/Zakkaus/doona-android/releases/latest"
    private const val TIMEOUT_MS = 15_000

    /** Hands off to another app; returns false if nothing handles the link. */
    fun openReleases(activity: Activity): Boolean = open(activity, "$REPOSITORY/releases")

    /** The repository's page, for 關於's GitHub 專案; false if nothing handles the link. */
    fun openRepository(activity: Activity): Boolean = open(activity, REPOSITORY)

    /** The privacy notice, for 關於's 隱私 (owner, 2026-11-26 07:13); false if nothing handles the link. */
    fun openPrivacy(activity: Activity): Boolean = open(activity, PRIVACY)

    /**
     * The tag of the latest published release (pre-releases and drafts are not "latest" on GitHub), or null when the
     * repository has published none (GitHub answers 404). A failure when GitHub gave no usable answer. Sent only when the
     * user asks, with the app's name as its User-Agent and nothing else about the phone.
     */
    suspend fun latestRelease(url: String = LATEST): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "doona-android")
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> Result.success(latestTag(connection.inputStream.bufferedReader().use { it.readText() }))
                    HttpURLConnection.HTTP_NOT_FOUND -> Result.success(null)
                    else -> Result.failure(IOException("HTTP $code"))
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** `tag_name` of a release as GitHub's API returns it; a failure for a body that is not such a release. */
    internal fun latestTag(body: String): String = try {
        requireNotNull(Json.parseToJsonElement(body).jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull)
    } catch (e: IllegalArgumentException) {
        // SerializationException is one too; both mean GitHub's answer is not a release.
        throw IOException("not a release", e)
    }

    private fun open(activity: Activity, link: String): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
