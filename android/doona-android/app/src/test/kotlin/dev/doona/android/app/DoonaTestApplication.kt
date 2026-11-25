package dev.doona.android.app

import androidx.work.Configuration

/**
 * The app as JVM tests run it (robolectric.properties makes it the default). WorkManager keeps one instance per sandbox,
 * and its in-process scheduler tracks a work's battery constraint by registering a receiver on a background thread. That
 * can land after Robolectric has torn the test down, where registering throws, and the app's crash handler then ends the
 * test JVM with exit 10. Naming another process as WorkManager's default keeps that scheduler out: work is still stored
 * and scheduled with the system, and the schedule tests read it from there.
 */
open class DoonaTestApplication : DoonaApplication() {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setDefaultProcessName("$packageName:no-in-process-work").build()
}
