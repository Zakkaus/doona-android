package dev.doona.android.vpn

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.RouteInfo
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowVpnService

@RunWith(AndroidJUnit4::class)
@Config(application = DoonaVpnServiceTest.LifecycleApplication::class, shadows = [DoonaVpnServiceTest.VpnPolicy::class, DoonaVpnServiceTest.VpnBuilder::class])
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
    fun lockdownAtBootPostsAnExplanationWithDropAlertsOff() = withService { service, app, engine ->
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { AppSettings.get(app).setNotifyOnDrop(false) }
        Shadow.extract<VpnPolicy>(service).lockdown = true

        service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), 0, 1)
        val failure = engine.awaitSession { it.status is Status.Failed }
        assertEquals(VpnStartBlock.Lockdown, failure.startBlock)

        shadowOf(Looper.getMainLooper()).idle()
        val posted = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals(app.getString(R.string.vpn_failure_lockdown), posted.notification.extras.getString(Notification.EXTRA_BIG_TEXT))
    }

    @Test
    fun aBackgroundRestartDeniedForegroundHasATypedFailure() = withService { service, _, engine ->
        shadowOf(service).setThrowInStartForeground(ForegroundServiceStartNotAllowedException("background start refused"))
        service.onStartCommand(Intent(VpnService.SERVICE_INTERFACE), Service.START_FLAG_REDELIVERY, 1)
        val failure = engine.awaitSession { it.status is Status.Failed }
        assertEquals(VpnStartBlock.ForegroundStartNotAllowed, failure.startBlock)
    }

    private fun withService(test: (DoonaVpnService, LifecycleApplication, ServiceEngine) -> Unit) {
        val lifecycle = Robolectric.buildService(DoonaVpnService::class.java).create()
        val service = lifecycle.get()
        val app = service.application as LifecycleApplication
        try {
            test(service, app, app.container.engine as ServiceEngine)
        } finally {
            lifecycle.destroy()
            app.scope.cancel()
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

        override fun createEngine(): ServiceEngine {
            val io = Dispatchers.IO
            val core = FakeCore()
            val opener = CoreOpener(core, { noBackupFilesDir.path }, io)
            val controller = TunnelController(core, opener, { PerApp() }, scope, killProcess = {}, endDebugCapture = {}, expireDebugCapture = { null })
            return ServiceEngine(core, opener, controller, PerAppSettings.create(this), { DoonaVpnService.start(this, it) }, io, scope, { null })
                .also { it.launch(Dispatchers.IO.limitedParallelism(1)) }
        }
    }
}
