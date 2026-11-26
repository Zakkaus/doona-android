package dev.doona.android.app

import android.content.Context
import android.content.Intent
import dev.doona.android.vpn.active

/**
 * 實驗室's 重開 App: starts the app again as a new task and ends this process, so the core starts again and reads what
 * it reads only at its start (效能模式, owner 2026-11-26 02:35). The tunnel runs in this process and goes down with
 * it, so a restart while connected opens the app through its connect entry, which connects again; the core keeps the
 * node selection in its own state file, so the new tunnel uses the same nodes.
 */
internal fun restartApp(context: Context) {
    val connected = (context.applicationContext as DoonaApplication).container.engine.status.value.active
    val intent = restartIntent(context, connected) ?: return
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}

/** What [restartApp] starts: the connect entry when the tunnel was up or coming up, else the launcher activity. */
internal fun restartIntent(context: Context, connected: Boolean): Intent? {
    val intent = if (connected) {
        MainActivity.connectIntent(context)
    } else {
        context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
    }
    return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
