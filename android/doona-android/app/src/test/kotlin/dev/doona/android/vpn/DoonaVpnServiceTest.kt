package dev.doona.android.vpn

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.RouteInfo
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.app.DoonaApplication
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DoonaVpnServiceTest {
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
    @Config(application = LifecycleApplication::class)
    fun aSystemStartWithNoAppExtrasOwnsTheSessionAndCanBeRedelivered() {
        val lifecycle = Robolectric.buildService(DoonaVpnService::class.java).create()
        val service = lifecycle.get()
        val engine = (service.application as LifecycleApplication).container.engine
        try {
            val info = service.packageManager.getServiceInfo(ComponentName(service, DoonaVpnService::class.java), PackageManager.GET_META_DATA)
            assertTrue(info.metaData?.getBoolean(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON, true) ?: true)

            val systemIntent = Intent(VpnService.SERVICE_INTERFACE).setPackage(service.packageName)
            assertEquals(Service.START_REDELIVER_INTENT, service.onStartCommand(systemIntent, 0, 1))
            assertEquals(Status.Starting, engine.status.value)
            assertEquals(VpnNotifications.ID, shadowOf(service).lastForegroundNotificationId)

            engine.disconnect()
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            lifecycle.destroy()
        }
    }

    class LifecycleApplication : DoonaApplication() {
        override fun createEngine(): ServiceEngine {
            val io = Dispatchers.IO
            val scope = CoroutineScope(SupervisorJob().apply { cancel() } + io)
            val core = FakeCore()
            val opener = CoreOpener(core, { noBackupFilesDir.path }, io)
            val controller = TunnelController(core, opener, { PerApp() }, scope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
            // Leave the controller's runner off: this test exercises Android start delivery, not VPN fd creation.
            return ServiceEngine(core, opener, controller, PerAppSettings.create(this), { true }, io, scope, { null })
        }
    }
}
