package dev.doona.android.app

import androidx.work.Configuration

/**
 * The app as JVM tests run it (robolectric.properties makes it the default). WorkManager keeps one instance per sandbox,
 * and its in-process scheduler tracks a work's battery constraint by registering a receiver on a background thread. That
 * can land after Robolectric has torn the test down, where registering throws, and the app's crash handler then ends the
 * test JVM with exit 10. Naming another process as WorkManager's default keeps that scheduler out; enqueued work is still
 * stored in WorkManager's database, where GeoRefreshScheduleTest reads its WorkInfo.
 *
 * Every test that sets its own application with `@Config(application = ...)` must extend this class, unless it uses a
 * plain [android.app.Application] that never starts the app's work, as SubscriptionRefreshScheduleTest does.
 */
open class DoonaTestApplication : DoonaApplication() {
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setDefaultProcessName("$packageName:no-in-process-work").build()
}
