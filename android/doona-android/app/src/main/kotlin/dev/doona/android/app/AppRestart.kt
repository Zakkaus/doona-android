package dev.doona.android.app

import android.content.Context
import android.content.Intent

/**
 * 實驗室's 重開 App: starts the app's launcher activity as a new task and ends this process, so the core starts again
 * and reads what it reads only at its start (效能模式, owner 2026-11-26 02:35). The tunnel runs in this process, so a
 * running VPN goes down with it.
 */
internal fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}
