package dev.doona.android.vpn

import android.app.ForegroundServiceStartNotAllowedException
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
import dev.doona.android.data.network.activeBootstrapDns
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
    private lateinit var session: ServiceSession
    private lateinit var connectivity: ConnectivityManager

    @Volatile
    private var foreground = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications = VpnNotifications(this)
        notifications.createChannel()
        session = ServiceSession(engine.controller)
        connectivity = getSystemService(ConnectivityManager::class.java)
        scope.launch { session.follow(::end) }
        scope.launch { notifications.keepCurrent(engine.status, ratesWhileSeen()) }
        scope.launch { followLockdown() }
    }

    private suspend fun followLockdown() {
        engine.status.collectLatest { status ->
            if (status.ended) engine.updateLockdown(false)
            if (status !is Status.Running) return@collectLatest
            while (true) {
                engine.updateLockdown(isLockdownEnabled)
                delay(LOCKDOWN_REFRESH_MS)
            }
        }
    }

    /** Rates for the notification, collected only while someone can read it, so a hidden one requests no samples. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ratesWhileSeen(): Flow<Traffic?> =
        notifications.seen().flatMapLatest { seen -> if (seen) engine.traffic else flowOf(null) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        var restart = START_NOT_STICKY
        when (intent?.action) {
            ACTION_START -> {
                val block = goForeground()
                session.start(this, intent.getLongExtra(EXTRA_GENERATION, 0), block)
                if (session.open) notifications.cancelDrop()
            }

            SERVICE_INTERFACE -> {
                val block = if (session.open) null else goForeground()
                if (session.startSystem(this, block) { stopSelf(startId) }) {
                    notifications.cancelDrop()
                    // Android redelivers the system start after process death; an explicit stop cancels it.
                    restart = START_REDELIVER_INTENT
                }
            }

            ACTION_STOP -> engine.disconnect()
        }
        // Nothing of ours to follow: a stop with no session, or a start that a disconnect replaced on its way here.
        if (!session.open) stopSelfResult(startId)
        return restart
    }

    /** Must run before onStartCommand returns, even for a repeated start: it came from startForegroundService. */
    private fun goForeground(): VpnStartBlock? {
        val notification = notifications.ongoing(engine.status.value.takeIf { it.active } ?: Status.Starting)
        foreground = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(VpnNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(VpnNotifications.ID, notification)
            }
            true
        } catch (e: ForegroundServiceStartNotAllowedException) {
            foreground = false
            return VpnStartBlock.ForegroundStartNotAllowed
        } catch (e: IllegalStateException) {
            // Without VPN consent the systemExempted type is refused; establish then fails the start.
            VpnLog.warn("startForeground threw ${e.javaClass.simpleName}")
            false
        } catch (e: SecurityException) {
            VpnLog.warn("startForeground threw ${e.javaClass.simpleName}")
            false
        }
        return null
    }

    private fun end(ended: Session) {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Also posted when startForeground was refused, in which case stopForeground leaves it.
        notifications.cancel()
        val startId = lastStartId
        scope.launch {
            try {
                notifications.showDrop(ended, AppSettings.get(this@DoonaVpnService)) {
                    engine.controller.session.value == ended
                }
            } finally {
                // A start that arrived meanwhile has a newer id, which keeps the service alive.
                stopSelfResult(startId)
            }
        }
    }

    override fun onRevoke() {
        // The default implementation stops the service at once; the controller's status ends it instead.
        engine.controller.revoke(this)
    }

    override fun onDestroy() {
        scope.cancel()
        engine.updateLockdown(false)
        // Only the session this instance took: a start requested after this instance stopped itself is not its own.
        engine.controller.release(this)
        super.onDestroy()
    }

    override val selfPackage: String get() = packageName

    override val mtu: Int get() = tunMtu()

    override fun installed(packages: Set<String>): Set<String> = packages.filterTo(mutableSetOf()) { name ->
        try {
            @Suppress("DEPRECATION") // The flags overload needs API 33.
            packageManager.getPackageInfo(name, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun bootstrapDns(): List<String> = connectivity.activeBootstrapDns()

    override fun establish(routing: AppRouting, mtu: Int): Tun? {
        if (!foreground) return null
        return tunBuilder(routing, mtu).establish()?.let(::ParcelTun)
    }

    /** The tun's configuration, apart from [establish] so that a test can read what the builder receives. */
    internal fun tunBuilder(routing: AppRouting, mtu: Int): Builder {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(mtu)
            .addAddress(ipv4(StartConfigDto.IPV4), StartConfigDto.IPV4_PREFIX)
            .addDnsServer(ipv4(StartConfigDto.DNS))
            // Since API 29 a VPN counts as metered unless told otherwise; this makes it follow the underlying network.
            .setMetered(false)
        // LAN, link-local, loopback and multicast stay outside the tunnel (Android 17 local-network permission).
        TunRoutes.IPV4.forEach { builder.addRoute(it.inetAddress, it.length) }
        when (routing) {
            is AppRouting.Exclude -> routing.packages.forEach { builder.addDisallowedApplication(it) }
            is AppRouting.Allow -> routing.packages.forEach { builder.addAllowedApplication(it) }
        }
        return builder
    }

    private class ParcelTun(private val pfd: ParcelFileDescriptor) : Tun {
        override val fd: Int get() = pfd.fd

        override fun close() = pfd.close()
    }

    companion object {
        private const val LOCKDOWN_REFRESH_MS = 5_000L
        private const val ACTION_START = "dev.doona.android.vpn.START"
        private const val ACTION_STOP = "dev.doona.android.vpn.STOP"
        private const val EXTRA_GENERATION = "generation"

        /**
         * Takes the start [TunnelController.requestStart] returned [generation] for; false when the system finds no service
         * to start. The caller holds VPN consent.
         */
        fun start(context: Context, generation: Long): Boolean {
            val intent = Intent(context, DoonaVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_GENERATION, generation)
            return context.startForegroundService(intent) != null
        }

        internal fun stopIntent(context: Context): Intent = Intent(context, DoonaVpnService::class.java).setAction(ACTION_STOP)
    }
}
