package dev.doona.android.vpn

import android.util.Log
import dev.doona.android.report.AppEventLevel
import dev.doona.android.report.AppEvents

/**
 * The VPN lane's log. Messages carry command names, error codes, counts and durations only: never URLs, node addresses,
 * credentials or DNS names, and never an exception message, which may quote any of them (`plans/v1/core-api.md`).
 * Each message is also an app event of the diagnostic report ([AppEvents]), which relies on that.
 */
internal object VpnLog {
    private const val TAG = "doona.vpn"

    fun info(message: String) {
        Log.i(TAG, message)
        AppEvents.record(AppEventLevel.Info, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
        AppEvents.record(AppEventLevel.Warn, message)
    }

    fun action(command: String, phase: String, outcome: String, code: String, elapsedMs: Long? = null) {
        val event = AppEvents.action(command, phase, outcome, code, elapsedMs)
        when (event.level) {
            AppEventLevel.Info -> Log.i(TAG, event.text)
            AppEventLevel.Warn -> Log.w(TAG, event.text)
        }
    }
}
