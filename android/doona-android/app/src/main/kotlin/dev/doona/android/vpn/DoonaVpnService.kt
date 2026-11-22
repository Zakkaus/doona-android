package dev.doona.android.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.doona.android.R
import dev.doona.android.app.DoonaApplication
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * The foreground VPN service. It holds no tunnel logic: start, stop and revoke go to the [TunnelController], and the
 * service follows the controller's status, staying in the foreground while a session lasts and stopping itself after.
 */
internal class DoonaVpnService :
    VpnService(),
    TunHost {
    // In the app the container's engine is a ServiceEngine; FakeEngine serves only previews and screenshot tests.
    private val engine get() = (application as DoonaApplication).container.engine as ServiceEngine
    private val scope = MainScope()
    private lateinit var notifications: VpnNotifications

    @Volatile
    private var foreground = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications = VpnNotifications(this)
        notifications.createChannel()
        scope.launch { followStatus() }
        scope.launch { notifications.keepCurrent(engine.status, ratesWhileScreenOn()) }
    }

    /** Collected on the main thread, after the fact: a status a newer start has replaced is skipped. */
    private suspend fun followStatus() {
        engine.status.dropWhile { it.ended }.collect { status ->
            if (status.ended && engine.status.value == status) end(status)
        }
    }

    /** Rates for the notification, collected only with the screen on, so sampling pauses with the screen off. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ratesWhileScreenOn(): Flow<Traffic?> =
        screenOn(this).flatMapLatest { on -> if (on) engine.traffic else flowOf(null) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                engine.controller.start(this)
            }

            ACTION_STOP -> engine.controller.stop()
        }
        if (engine.status.value.ended) stopSelfResult(startId)
        return START_NOT_STICKY
    }

    /** Must run before onStartCommand returns, even for a repeated start: it came from startForegroundService. */
    private fun goForeground() {
        val notification = notifications.ongoing(engine.status.value.takeIf { it.active } ?: Status.Starting)
        foreground = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(VpnNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(VpnNotifications.ID, notification)
            }
            true
        } catch (e: IllegalStateException) {
            // Without VPN consent the systemExempted type is refused; establish then fails the start.
            VpnLog.warn("startForeground threw ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            VpnLog.warn("startForeground threw ${e.javaClass.simpleName}")
            false
        }
    }

    /** The session ended: a failure stays visible as a notification, a stop removes it. */
    private fun end(status: Status) {
        foreground = false
        if (status is Status.Failed) {
            stopForeground(STOP_FOREGROUND_DETACH)
            notifications.show(notifications.failed(status.reason))
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Also posted when startForeground was refused, in which case stopForeground leaves it.
            notifications.cancel()
        }
        // A start that arrived meanwhile has a newer id, which keeps the service alive.
        stopSelfResult(lastStartId)
    }

    override fun onRevoke() {
        // The default implementation stops the service at once; the controller's status ends it instead.
        engine.controller.stop()
    }

    override fun onDestroy() {
        scope.cancel()
        engine.controller.stop()
        super.onDestroy()
    }

    override val selfPackage: String get() = packageName

    override fun installed(packages: Set<String>): Set<String> = packages.filterTo(mutableSetOf()) { name ->
        try {
            @Suppress("DEPRECATION") // The flags overload needs API 33.
            packageManager.getPackageInfo(name, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun bootstrapDns(): List<String> {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val servers = connectivity.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
        // IPv6 is off in v0.1, and a link-local resolver would need its scope.
        return servers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }

    override fun establish(routing: AppRouting): Tun? {
        if (!foreground) return null
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(StartConfigDto.MTU)
            .addAddress(StartConfigDto.IPV4, StartConfigDto.IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(StartConfigDto.DNS)
            // Since API 29 a VPN counts as metered unless told otherwise; this makes it follow the underlying network.
            .setMetered(false)
        when (routing) {
            is AppRouting.Exclude -> routing.packages.forEach { builder.addDisallowedApplication(it) }
            is AppRouting.Allow -> routing.packages.forEach { builder.addAllowedApplication(it) }
        }
        return builder.establish()?.let(::ParcelTun)
    }

    private class ParcelTun(private val pfd: ParcelFileDescriptor) : Tun {
        override val fd: Int get() = pfd.fd

        override fun close() = pfd.close()
    }

    companion object {
        private const val ACTION_START = "dev.doona.android.vpn.START"
        private const val ACTION_STOP = "dev.doona.android.vpn.STOP"

        /** The caller holds VPN consent and may start a foreground service. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, DoonaVpnService::class.java).setAction(ACTION_START))
        }

        internal fun stopIntent(context: Context): Intent = Intent(context, DoonaVpnService::class.java).setAction(ACTION_STOP)
    }
}
