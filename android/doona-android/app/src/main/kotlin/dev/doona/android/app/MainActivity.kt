package dev.doona.android.app

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.engine.Status
import dev.doona.android.feature.localnetwork.LocalNetworkHost
import dev.doona.android.feature.permissions.NotifyState
import dev.doona.android.feature.permissions.Permission
import dev.doona.android.feature.permissions.PermissionStep
import dev.doona.android.feature.permissions.PermissionViewModel
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The only activity. It owns the steps that need an activity: the VPN consent before the engine may connect, and the
 * notification permission once it has, each explained first ([PermissionAsks]); and the local network permission's
 * system dialog and the app's settings page, which the screens' engine asks for after a failure (ARCH D26). Every
 * connect request, from the UI or from [connectIntent], goes through [connect]. No other intent connects or
 * disconnects.
 */
class MainActivity : ComponentActivity() {
    private val container get() = (application as DoonaApplication).container
    private val engine get() = container.engine

    private val permissions by viewModels<PermissionViewModel> { PermissionViewModel.Factory }
    private val asks get() = permissions.asks

    // A denied notification permission does not stop the connection; the service then runs without a visible notice,
    // and 活動 says so.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { asks.notifyAnswered(it) }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onPermissionStep(asks.consentAnswered(result.resultCode == RESULT_OK))
    }

    /** The local network permission's only launcher, a field like the two above, so the answer reaches a recreated activity. */
    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { container.localNetwork.answered(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Read here, once, so the settings page never asks the package manager while it composes.
        val version = versionName()
        val localNetwork = LocalNetworkHost(container.localNetwork, ::requestLocalNetwork, ::openAppSettings)
        val lanAsking = container.localNetwork.prompt.map { it != null }
        setContent {
            AppTheme {
                DoonaApp(
                    container.uiEngine,
                    container.installedApps,
                    onConnect = ::connect,
                    imports = container.imports,
                    version = version,
                    crashOffer = container.crashOffer,
                    refusals = container.connectRefusals,
                    onSystemSettings = ::openSystemSettings,
                    lanAsking = lanAsking,
                )
                AppSheets(asks, ::onPermissionStep, localNetwork)
            }
        }
        askNotifyOnceConnected()
        tellRefusals()
        container.checkCrashes()
        // A recreated activity or one reopened from recents must not repeat the request that first launched it.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
    }

    override fun onResume() {
        super.onResume()
        container.logLevels.expireIfDue()
        // Back from the app's settings, where a fetch waiting on the local network refusal may have been allowed.
        container.localNetwork.resumed()
        // Or where the refused notifications were allowed; and a refused VPN consent stands only while doona has none.
        if (notificationsAllowed()) container.connectRefusals.notifications.value = false
        if (VpnService.prepare(this) == null) container.connectRefusals.vpn.value = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** The app's version name; empty when the package manager gives none, which the settings show as a dash. */
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } ?: ""

    // The activity itself is exported for the launcher, so any app can send it this action; only the non-exported
    // alias proves the request came from this app.
    private fun handle(intent: Intent) {
        if (intent.action == ACTION_CONNECT && intent.component?.className == CONNECT_ENTRY) connect()
    }

    /** 連線: at once when the consent is given; otherwise the VPN is explained before the system's consent dialog. */
    private fun connect() = onPermissionStep(asks.connect(consentNeeded = VpnService.prepare(this) != null))

    /** Runs a step of [PermissionAsks]: the engine's connect, or one of the system's two dialogs. */
    @VisibleForTesting
    internal fun onPermissionStep(step: PermissionStep) {
        when (step) {
            PermissionStep.Wait -> Unit

            PermissionStep.Connect -> {
                // A connect has the consent, given now or before, so a refused one no longer stands.
                container.connectRefusals.vpn.value = false
                engine.connect()
            }

            PermissionStep.AskConsent -> requestVpnConsent()

            PermissionStep.AskNotify -> launchNotificationDialog()
        }
    }

    // launch() throws ActivityNotFoundException when no activity can handle the request. That must not end the app or
    // leave the dialog counted as open: the ask is given up and the next status explains again.
    private fun launchNotificationDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return asks.notifyNotAsked()
        try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: ActivityNotFoundException) {
            VpnLog.warn("permissions: notification dialog not opened (${e.javaClass.simpleName})")
            asks.notifyNotAsked()
        }
    }

    // The consent may have been given meanwhile (another app's prompt, the tile), so the dialog is looked up again.
    private fun requestVpnConsent() {
        val consent = VpnService.prepare(this) ?: return onPermissionStep(asks.consentAnswered(given = true))
        try {
            vpnConsent.launch(consent)
        } catch (e: ActivityNotFoundException) {
            VpnLog.warn("permissions: consent dialog not opened (${e.javaClass.simpleName})")
            asks.consentLaunchFailed()
        }
    }

    /**
     * While the activity is started, a connection that is up explains the notification permission, once ever: Android
     * below 13 has none to ask for, and a granted one needs no asking. The user's answer is stored as soon as it is
     * given. A settings file that cannot be read or written skips the question, or the store, instead of ending the app;
     * the next status, or the next start, tries again.
     */
    private fun askNotifyOnceConnected() {
        val settings = AppSettings.get(application)
        val notify = NotifyState {
            val denied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            val askedBefore = denied && settings.notifyAsked.first()
            // Not asked again, but 活動 tells it, in a new process too.
            if (askedBefore) container.connectRefusals.notifications.value = true
            denied && !askedBefore
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Each input can end what held a waiting explanation back, so each runs onStatus again.
                val changes = combine(engine.status, asks.unrecorded, asks.showing, asks.pending) { status, _, _, _ ->
                    status is Status.Running
                }
                changes.collect { running ->
                    try {
                        asks.onStatus(running, notify, settings::recordNotifyAsked)
                    } catch (e: IOException) {
                        VpnLog.warn("permissions: settings failed (${e.javaClass.simpleName})")
                    }
                }
            }
        }
    }

    /**
     * 活動's refusal notices ([ConnectRefusals]) take each refusal [PermissionAsks.refused] tells. The next 連線 clears
     * that one; a notice stands until a connect has the consent ([onPermissionStep]) or the system allows what was
     * refused ([onResume]).
     */
    private fun tellRefusals() {
        lifecycleScope.launch {
            asks.refused.collect { refused ->
                when (refused) {
                    Permission.Vpn -> container.connectRefusals.vpn.value = true
                    Permission.Notify -> container.connectRefusals.notifications.value = true
                    null -> Unit
                }
            }
        }
    }

    private fun notificationsAllowed(): Boolean = getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    /** The system's question; below Android 17 there is no permission to ask for, so access counts as allowed. */
    private fun requestLocalNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            container.localNetwork.answered(true)
        }
    }

    /** The app's page in the system settings, where a refused permission can still be allowed. */
    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /** One of the system's settings pages that 活動's notices open. */
    private fun openSystemSettings(page: SystemSettings) {
        val intent = when (page) {
            SystemSettings.Vpn -> Intent(Settings.ACTION_VPN_SETTINGS)
            SystemSettings.Network -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
            SystemSettings.Notifications -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    companion object {
        const val ACTION_CONNECT = "dev.doona.android.action.CONNECT"

        /** The `<activity-alias>` in the manifest; not exported. */
        private const val CONNECT_ENTRY = "dev.doona.android.app.ConnectEntry"

        /**
         * Opens the app and runs the same path as the connect switch, asking for consent first where needed. For this
         * app's own components only, such as the Quick Settings tile when VPN consent is missing.
         */
        fun connectIntent(context: Context): Intent =
            Intent(ACTION_CONNECT).setClassName(context, CONNECT_ENTRY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
