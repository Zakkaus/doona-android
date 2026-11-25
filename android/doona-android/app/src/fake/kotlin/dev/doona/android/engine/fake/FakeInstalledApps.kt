package dev.doona.android.engine.fake

import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.InstalledApps

/** A fixed list of apps for previews and screenshot tests. */
class FakeInstalledApps(private val apps: List<InstalledApp> = SAMPLE_APPS) : InstalledApps {
    override suspend fun launchable(): List<InstalledApp> = apps

    companion object {
        val SAMPLE_APPS = listOf(
            InstalledApp("com.android.chrome", "Chrome"),
            InstalledApp("com.android.settings", "Settings"),
            InstalledApp("com.example.calc", "計算機"),
            InstalledApp("com.example.maps", "Maps"),
            InstalledApp("com.google.android.youtube", "YouTube"),
            InstalledApp("org.telegram.messenger", "Telegram"),
            InstalledApp("org.mozilla.firefox", "Firefox"),
            InstalledApp("com.example.reader", "Reader"),
            InstalledApp("com.example.notes", "Notes"),
            InstalledApp("com.example.player", "Player"),
            InstalledApp("com.example.camera", "Camera"),
            InstalledApp("com.example.mail", "Mail"),
        ).sortedBy { it.label.lowercase() }
    }
}
