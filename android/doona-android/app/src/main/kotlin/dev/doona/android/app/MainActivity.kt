package dev.doona.android.app

import android.Manifest
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
import dev.doona.android.feature.permissions.PermissionPrompts
import dev.doona.android.feature.permissions.PermissionStep
import dev.doona.android.feature.permissions.PermissionViewModel
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.flow.first
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

    // A denied notification permission does not stop the connection; the service then runs without a visible notice.
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
        setContent {
            AppTheme {
                DoonaApp(
                    container.uiEngine,
                    container.installedApps,
                    onConnect = ::connect,
                    imports = container.imports,
                    version = version,
                    localNetwork = localNetwork,
                )
                PermissionPrompts(asks, ::onPermissionStep)
            }
        }
        askNotifyOnceConnected()
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

            PermissionStep.Connect -> engine.connect()

            PermissionStep.AskConsent -> requestVpnConsent()

            PermissionStep.AskNotify -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // The consent may have been given meanwhile (another app's prompt, the tile), so the dialog is looked up again.
    private fun requestVpnConsent() {
        val consent = VpnService.prepare(this) ?: return onPermissionStep(asks.consentAnswered(given = true))
        vpnConsent.launch(consent)
    }

    /**
     * While the activity is started, a connection that is up explains the notification permission, once ever: Android
     * below 13 has none to ask for, and a granted one needs no asking. A settings file that cannot be read or written
     * skips the question for that status instead of ending the app; the next status asks again.
     */
    private fun askNotifyOnceConnected() {
        val settings = AppSettings.get(application)
        val notify = NotifyState {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !settings.notifyAsked.first()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                engine.status.collect {
                    try {
                        asks.onStatus(it is Status.Running, notify, settings::recordNotifyAsked)
                    } catch (e: IOException) {
                        VpnLog.warn("permissions: settings failed (${e.javaClass.simpleName})")
                    }
                }
            }
        }
    }

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
