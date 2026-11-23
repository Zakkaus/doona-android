package dev.doona.android.work

import android.app.Activity
import android.app.Application
import android.os.Bundle

internal class ForegroundRefresh(private val refresh: () -> Unit) : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) refresh()
    }

    override fun onActivityStopped(activity: Activity) {
        started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
