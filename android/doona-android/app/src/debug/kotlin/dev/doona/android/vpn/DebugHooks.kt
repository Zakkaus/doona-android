package dev.doona.android.vpn

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import dev.doona.android.app.DoonaApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Debug builds only: what adb may do to doona for a measurement or an acceptance check. The receiver requires `DUMP`,
 * which only the shell and the system hold. Release builds have neither the receiver nor this file
 * (`src/release/.../TunMtu.kt`).
 *
 * - `adb shell am broadcast -n dev.doona.android/.vpn.DebugHooks -a dev.doona.android.debug.MTU --ei mtu 1400` sets the
 *   MTU of the tun and of the core's start config from the next start on; `--ei mtu 0` returns to the default. Each
 *   start logs `start N: mtu M` under the `doona.vpn` tag.
 * - `adb shell am broadcast -n dev.doona.android/.vpn.DebugHooks -a dev.doona.android.debug.FAULT` ends the running
 *   core's data path as a crash would; the health check must then show the tunnel failed within 10 s.
 * - `adb shell am broadcast -n dev.doona.android/.vpn.DebugHooks -a dev.doona.android.debug.EXPORT` writes the diagnostic
 *   report and opens its share sheet, as a report page will ([dev.doona.android.report.ReportExporter]); a pick there
 *   clears the crash files the zip holds. Android starts no activity for an app in the background, so open doona first.
 *   Each run logs `debug export: …` under the `doona.vpn` tag. Its zip goes at the next start.
 */
internal class DebugHooks : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_MTU -> setMtu(context, intent.getIntExtra(EXTRA_MTU, 0))
            ACTION_FAULT -> fault(goAsync())
            ACTION_EXPORT -> export(context.applicationContext as DoonaApplication, goAsync())
        }
    }

    private fun setMtu(context: Context, mtu: Int) {
        when (mtu) {
            0 -> context.debugPrefs().edit { remove(KEY_MTU) }
            in MIN_MTU..MAX_MTU -> context.debugPrefs().edit { putInt(KEY_MTU, mtu) }
            else -> return VpnLog.warn("debug mtu $mtu refused: outside $MIN_MTU..$MAX_MTU")
        }
        VpnLog.info("debug mtu ${context.tunMtu()} from the next start")
    }

    /** Off the main thread, as every call into the core. */
    private fun fault(pending: BroadcastReceiver.PendingResult) {
        thread(name = "doona-debug-fault") {
            try {
                VpnLog.warn("debug fault: ${NativeCore.call("debugFault", "{}")}")
            } finally {
                pending.finish()
            }
        }
    }

    /** Exports on the main thread, as a page would; the export reads the disk on its own dispatcher. */
    private fun export(app: DoonaApplication, pending: BroadcastReceiver.PendingResult?) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val share = app.container.reportExporter.export() ?: return@launch VpnLog.warn("debug export: no report")
                try {
                    app.startActivity(share.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    VpnLog.info("debug export: share sheet started")
                } catch (_: ActivityNotFoundException) {
                    share.notOpened()
                    VpnLog.warn("debug export: no share sheet")
                }
            } finally {
                // Null when called outside a broadcast, as in a test.
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_MTU = "dev.doona.android.debug.MTU"
        const val ACTION_FAULT = "dev.doona.android.debug.FAULT"
        const val ACTION_EXPORT = "dev.doona.android.debug.EXPORT"
        const val EXTRA_MTU = "mtu"

        /** The core refuses a smaller MTU; its start config holds 16 bits. */
        private const val MIN_MTU = 1280
        private const val MAX_MTU = 65_535
    }
}

/** The tun's MTU: the one adb set through [DebugHooks], else [StartConfigDto.MTU]. */
internal fun Context.tunMtu(): Int = debugPrefs().getInt(KEY_MTU, StartConfigDto.MTU)

private fun Context.debugPrefs() = getSharedPreferences("doona.debug", Context.MODE_PRIVATE)

private const val KEY_MTU = "mtu"
