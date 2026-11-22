package dev.doona.android.vpn

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether someone can read the ongoing notification: the screen is on, and notifications are on for the app and for
 * its channel ([channelImportance] is null when the channel does not exist).
 */
internal fun isNotificationSeen(screenOn: Boolean, appNotificationsEnabled: Boolean, channelImportance: Int?): Boolean =
    screenOn && appNotificationsEnabled && channelImportance != null && channelImportance != NotificationManager.IMPORTANCE_NONE

/** [isNotificationSeen] for [channel], read again on each screen or block-state change. Registered only while collected. */
internal fun notificationSeen(context: Context, channel: String): Flow<Boolean> = callbackFlow {
    val power = context.getSystemService(PowerManager::class.java)
    val manager = context.getSystemService(NotificationManager::class.java)
    val read = {
        trySend(isNotificationSeen(power.isInteractive, manager.areNotificationsEnabled(), manager.getNotificationChannel(channel)?.importance))
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            read()
        }
    }
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
        addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
    }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    // Read after registering, so a change in between is not lost.
    read()
    awaitClose { context.unregisterReceiver(receiver) }
}.distinctUntilChanged()
