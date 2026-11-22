package dev.doona.android.engine

/**
 * The apps the user can pick for per-app routing: those with a launcher entry (ARCH D16). The Android implementation
 * lives in `data/apps`; `engine/fake/FakeInstalledApps` serves previews and tests.
 */
interface InstalledApps {
    /** Every launchable app except doona itself, sorted by [InstalledApp.label]. Reads the device, so it may take a moment. */
    suspend fun launchable(): List<InstalledApp>
}

/** [label] is what the launcher shows; [packageName] is what the routing stores. */
data class InstalledApp(val packageName: String, val label: String)
