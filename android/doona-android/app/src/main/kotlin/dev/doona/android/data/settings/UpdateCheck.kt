package dev.doona.android.data.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

object UpdateCheck {
    /** Hands off to another app; returns false if nothing handles the link. */
    fun openReleases(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Zakkaus/doona-android/releases")))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
