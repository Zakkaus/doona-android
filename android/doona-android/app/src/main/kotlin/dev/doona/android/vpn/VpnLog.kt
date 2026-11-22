package dev.doona.android.vpn

import android.util.Log

/**
 * The VPN lane's log. Messages carry command names, error codes, counts and durations only: never URLs, node addresses,
 * credentials or DNS names, and never an exception message, which may quote any of them (`plans/v1/core-api.md`).
 */
internal object VpnLog {
    private const val TAG = "doona.vpn"

    fun info(message: String) {
        Log.i(TAG, message)
    }

    fun warn(message: String) {
        Log.w(TAG, message)
    }
}
