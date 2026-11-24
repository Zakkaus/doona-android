package dev.doona.android.vpn

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.RouteInfo
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.app.DoonaApplication
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowVpnService
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(application = DoonaVpnServiceTest.LifecycleApplication::class, shadows = [DoonaVpnServiceTest.VpnPolicy::class, DoonaVpnServiceTest.VpnBuilder::class])
class DoonaVpnServiceTest {
    @After
    fun stopEngine() {
        val app = ApplicationProvider.getApplicationContext<LifecycleApplication>()
        runBlocking { app.scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test
    fun theTunRoutesThePublicSpaceOnly() {
        val service = Robolectric.buildService(DoonaVpnService::class.java).get()
        // No package: the builder checks each one with a package manager Robolectric leaves out of VpnService.
        val builder = service.tunBuilder(AppRouting.Allow(emptySet()), StartConfigDto.MTU)
        // The builder keeps its routes in a private field; the public API only hands them to the system.
        val field = builder.javaClass.getDeclaredField("mRoutes").apply { isAccessible = true }
        val routes = (field.get(builder) as List<*>).map { (it as RouteInfo).destination.toString() }
        assertEquals(TunRoutes.IPV4.map(Ipv4Prefix::toString), routes)
    }

    @Test
    fun aSystemStartWithNoAppExtrasOwnsTheSessionAndCanBeRedelivered() = withService { service, _, engine ->
        val info = service.packageManager.getServiceInfo(ComponentName(service, DoonaVpnService::class.java), PackageManager.GET_META_DATA)
        assertTrue(info.metaData?.getBoolean(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON, true) ?: true)

        val systemIntent = Intent(VpnService.SERVICE_INTERFACE).setPackage(service.packageName)
        assertEquals(Service.START_REDELIVER_INTENT, service.onStartCommand(systemIntent, 0, 1))
        engine.awaitSession { it.status is Status.Running }
        assertEquals(VpnNotifications.ID, shadowOf(service).lastForegroundNotificationId)

        engine.disconnect()
        engine.awaitSession { it.status == Status.Stopped }
        assertEquals(1, shadowOf(service).stopSelfId)
    }

    @Test
    fun anAppStartIsNotRedeliveredAfterProcessDeath() = withService { service, app, engine ->
        engine.connect()
        val intent = shadowOf(app).nextStartedService
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        engine.awaitSession { it.status is Status.Running }
    }

    @Test
    fun lockdownSettingFollowsAndroidWhileTheServiceRuns() {
        lateinit var engine: ServiceEngine
        withService { service, _, runningEngine ->
            engine = runningEngine
            val policy = Shadow.extract<VpnPolicy>(service)
            assertFalse(engine.settings.value.lockdownEnabled)
            policy.lockdown = true
            service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
            engine.awaitSession { it.status is Status.Running }
            val main = shadowOf(Looper.getMainLooper())
            main.idle()
            assertTrue(engine.settings.value.lockdownEnabled)

            policy.lockdown = false
            main.idleFor(Duration.ofSeconds(5))
            assertFalse(engine.settings.value.lockdownEnabled)
            policy.lockdown = true
            main.idleFor(Duration.ofSeconds(5))
            assertTrue(engine.settings.value.lockdownEnabled)
        }
        assertFalse(engine.settings.value.lockdownEnabled)
    }

    @Test
    fun destroyingARunningServiceClearsLockdown() {
        lateinit var engine: ServiceEngine
        withService { service, _, runningEngine ->
            engine = runningEngine
            Shadow.extract<VpnPolicy>(service).lockdown = true
            service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
            engine.awaitSession { it.status is Status.Running }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(engine.settings.value.lockdownEnabled)
        }
        assertFalse("Destroy must clear lockdown without a prior disconnect", engine.settings.value.lockdownEnabled)
    }

    @Test
    fun aBackgroundRestartDeniedForegroundPostsAnExplanationWithDropAlertsOff() = withService { service, app, engine ->
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { AppSettings.get(app).setNotifyOnDrop(false) }
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background start refused"))
        service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), Service.START_FLAG_REDELIVERY, 1)
        val failure = engine.awaitSession { it.status is Status.Failed }
        assertEquals(VpnStartBlock.ForegroundStartNotAllowed, failure.startBlock)

        runBlocking { withContext(app.lane) {} }
        shadowOf(Looper.getMainLooper()).idle()
        val posted = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals(app.getString(R.string.vpn_start_blocked_title), posted.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(app.getString(R.string.vpn_failure_foreground_refused), posted.notification.extras.getString(Notification.EXTRA_BIG_TEXT))
    }

    @Test
    fun startWithAnActiveVpnUsesTheUnderlyingResolvers() = withService { service, app, engine ->
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val manager = shadowOf(connectivity)
        val wifi = ShadowNetwork.newInstance(100)

        @Suppress("DEPRECATION") // Robolectric selects the active network by legacy type.
        val vpnInfo = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, true)
        manager.setActiveNetworkInfo(vpnInfo)
        val vpn = checkNotNull(connectivity.activeNetwork)
        val virtual = internet(NetworkCapabilities.TRANSPORT_VPN)
        manager.setNetworkCapabilities(vpn, virtual)
        manager.setLinkProperties(vpn, dns("172.19.0.2"))

        manager.networkCallbacks.forEach { it.onLinkPropertiesChanged(wifi, dns("192.0.2.53")) }
        service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
        engine.awaitSession { it.status is Status.Running }
        assertTrue(app.core.configs.single().contains("\"bootstrapDns\":[\"192.0.2.53\"]"))
    }

    @Test
    fun handoffReplacesBootstrapDnsOnceWithoutRebuildingTheTunnel() {
        val app = ApplicationProvider.getApplicationContext<LifecycleApplication>()
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val manager = shadowOf(connectivity)
        val cellular = checkNotNull(connectivity.activeNetwork)
        val cellCaps = internet(NetworkCapabilities.TRANSPORT_CELLULAR)
        val initial = dns("192.0.2.53")
        manager.setNetworkCapabilities(cellular, cellCaps)
        manager.setLinkProperties(cellular, initial)

        withService { service, _, engine ->
            var starts = 0
            app.core.onStart = { starts++ }
            service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
            val session = engine.awaitSession { it.status is Status.Running }
            manager.networkCallbacks.forEach { it.onLinkPropertiesChanged(cellular, initial) }

            val wifi = ShadowNetwork.newInstance(100)
            val ipv6 = InetAddress.getByName("fe80::1")
            val next = LinkProperties().apply {
                interfaceName = "wlan0"
                setDnsServers(listOf(Inet6Address.getByAddress(null, ipv6.address, 17), InetAddress.getByName("198.51.100.53")))
            }
            val nextWifi = ShadowNetwork.newInstance(101)
            manager.networkCallbacks.forEach {
                it.onLinkPropertiesChanged(wifi, next)
                it.onLinkPropertiesChanged(wifi, next)
                it.onLinkPropertiesChanged(wifi, LinkProperties())
                it.onLinkPropertiesChanged(nextWifi, next)
            }

            runBlocking {
                withContext(app.lane) {
                    val args = """{"servers":["${ipv6.hostAddress}%wlan0","198.51.100.53"]}"""
                    assertEquals(listOf("setBootstrapDns" to args), app.core.calls)
                    assertEquals(1, starts)
                    assertEquals(session, engine.controller.session.value)
                }
            }
            engine.disconnect()
            engine.awaitSession { it.status == Status.Stopped }
        }
        manager.networkCallbacks.forEach { it.onLinkPropertiesChanged(cellular, initial) }
        runBlocking {
            withContext(app.lane) {
                assertEquals(2, app.core.calls.size)
                assertEquals("setBootstrapDns" to """{"servers":["192.0.2.53"]}""", app.core.calls.last())
            }
        }
    }

    private fun internet(transport: Int): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also { caps ->
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addTransportType(transport)
    }

    private fun dns(address: String) = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName(address))) }

    private fun withService(test: (DoonaVpnService, LifecycleApplication, ServiceEngine) -> Unit) {
        val lifecycle = Robolectric.buildService(DoonaVpnService::class.java).create()
        val service = lifecycle.get()
        val app = service.application as LifecycleApplication
        try {
            test(service, app, app.container.engine as ServiceEngine)
        } finally {
            lifecycle.destroy()
        }
    }

    private fun ServiceEngine.awaitSession(ready: (Session) -> Boolean): Session = runBlocking {
        withTimeout(5_000) { controller.session.first(ready) }
    }

    @Implements(VpnService::class)
    class VpnPolicy : ShadowVpnService() {
        var lockdown = false

        @Implementation
        fun isLockdownEnabled() = lockdown
    }

    @Implements(VpnService.Builder::class)
    class VpnBuilder {
        @RealObject
        private lateinit var builder: VpnService.Builder

        @Implementation
        fun addDisallowedApplication(packageName: String): VpnService.Builder = builder

        @Implementation
        fun establish(): ParcelFileDescriptor {
            val (input, output) = ParcelFileDescriptor.createPipe()
            output.close()
            return input
        }
    }

    class LifecycleApplication : DoonaApplication() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        internal val core = FakeCore()
        val lane = Dispatchers.IO.limitedParallelism(1)

        override fun createEngine(): ServiceEngine {
            val io = Dispatchers.IO
            val opener = CoreOpener(core, { noBackupFilesDir.path }, io)
            val controller = TunnelController(core, opener, { PerApp() }, scope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
            return ServiceEngine(core, opener, controller, PerAppSettings.create(this), { DoonaVpnService.start(this, it) }, io, scope, { null })
                .also { it.launch(lane, getSystemService(ConnectivityManager::class.java)) }
        }
    }
}
