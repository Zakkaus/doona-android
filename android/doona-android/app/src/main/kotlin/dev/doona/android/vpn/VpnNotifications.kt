package dev.doona.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import dev.doona.android.R
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** doona's launcher activity; the app lane owns its class. */
internal fun launchIntent(context: Context): Intent =
    checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))

/** The VPN notification: status and live rates while a session lasts, and the reason when one fails. */
internal class VpnNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** What the ongoing notification says; an update that would not change it is skipped. */
    data class Text(val title: String, val body: String?)

    fun createChannel() {
        val name = context.getString(R.string.vpn_channel_name)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_LOW))
    }

    /** Null once the session has ended. Rates show while running, if [traffic] is given. */
    fun text(status: Status, traffic: Traffic?): Text? {
        val title = when (status) {
            Status.Starting -> R.string.vpn_status_starting
            is Status.Running -> R.string.vpn_status_running
            Status.Stopping -> R.string.vpn_status_stopping
            Status.Stopped, is Status.Failed -> return null
        }
        val rates = traffic?.takeIf { status is Status.Running }?.let {
            context.getString(R.string.vpn_rates, Formatter.formatShortFileSize(context, it.upRate), Formatter.formatShortFileSize(context, it.downRate))
        }
        return Text(context.getString(title), rates)
    }

    /** The ongoing notification for an active [status], before any rates are known. */
    fun ongoing(status: Status): Notification = ongoing(checkNotNull(text(status, traffic = null)))

    fun ongoing(text: Text): Notification {
        val stop = PendingIntent.getService(context, 0, DoonaVpnService.stopIntent(context), PendingIntent.FLAG_IMMUTABLE)
        return builder()
            .setContentTitle(text.title)
            .setContentText(text.body)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.vpn_action_disconnect), stop).build())
            .build()
    }

    fun failed(reason: FailureReason): Notification = builder()
        .setContentTitle(context.getString(R.string.vpn_failed_title))
        .setContentText(context.getString(reasonText(reason)))
        .setAutoCancel(true)
        .build()

    fun show(notification: Notification) = manager.notify(ID, notification)

    fun cancel() = manager.cancel(ID)

    /** Whether the ongoing notification can be read now; see [isNotificationSeen]. */
    fun seen(): Flow<Boolean> = notificationSeen(context, CHANNEL)

    /** Keeps the ongoing notification current, at most every 2 s and only when its text changes. */
    suspend fun keepCurrent(status: StateFlow<Status>, traffic: Flow<Traffic?>) {
        combine(status, traffic, ::text)
            .distinctUntilChanged()
            .conflate()
            .collect { text ->
                // Checked on the main thread, where the service ends a session: a late update must not repost it.
                if (text != null && !status.value.ended) {
                    show(ongoing(text))
                    delay(MIN_UPDATE_MS)
                }
            }
    }

    private fun builder(): Notification.Builder = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_duck)
        .setContentIntent(PendingIntent.getActivity(context, 0, launchIntent(context), PendingIntent.FLAG_IMMUTABLE))
        .setOnlyAlertOnce(true)
        .setShowWhen(false)

    private fun reasonText(reason: FailureReason) = when (reason) {
        FailureReason.NoAllowedApps -> R.string.vpn_failure_no_allowed_apps
        FailureReason.StartFailed -> R.string.vpn_failure_start_failed
        FailureReason.CoreStopped -> R.string.vpn_failure_core_stopped
        FailureReason.IncompatibleCore -> R.string.vpn_failure_incompatible_core
    }

    companion object {
        const val ID = 1
        private const val CHANNEL = "vpn"
        private const val MIN_UPDATE_MS = 2_000L
    }
}
