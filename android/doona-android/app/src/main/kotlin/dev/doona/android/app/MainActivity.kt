package dev.doona.android.app

import android.Manifest
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.feature.localnetwork.LocalNetworkHost
import dev.doona.android.report.ExportState
import dev.doona.android.report.reportShareIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only activity. It owns the steps that need an activity: before the engine may connect, the notification
 * permission and VPN consent; and the local network permission's system dialog and the app's settings page, which the
 * screens' engine asks for after a failure (ARCH D26); and the share sheet of the diagnostic report. Every connect
 * request, from the UI or from [connectIntent], goes through [connect].
 */
class MainActivity : ComponentActivity() {
    private val container get() = (application as DoonaApplication).container
    private val engine get() = container.engine

    /**
     * True from a connect request until it connects or consent is refused, so a repeated tap or a redelivered intent
     * joins the pending request. Saved with the activity, because a pending result is delivered to the recreated one.
     */
    private var connecting = false

    // A denied notification permission does not stop the connection; the service then runs without a visible notice.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpnConsent() }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        connecting = false
        if (result.resultCode == RESULT_OK) engine.connect()
    }

    /** The local network permission's only launcher, a field like the two above, so the answer reaches a recreated activity. */
    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { container.localNetwork.answered(it) }

    /**
     * The share sheet's only launcher. Its return, to this activity or a recreated one, says nothing of what the user did
     * there; a pick reaches the export by the sheet's own callback ([dev.doona.android.report.reportPicks]).
     */
    private val reportShare =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { container.reports.shared() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        connecting = savedInstanceState?.getBoolean(KEY_CONNECTING) ?: false
        // Read here, once, so the settings page never asks the package manager while it composes.
        val version = versionName()
        val localNetwork = LocalNetworkHost(container.localNetwork, ::requestLocalNetwork, ::openAppSettings)
        setContent {
            DoonaTheme {
                DoonaApp(
                    container.uiEngine,
                    container.installedApps,
                    onConnect = ::connect,
                    imports = container.imports,
                    version = version,
                    localNetwork = localNetwork,
                    reports = container.reports,
                )
            }
        }
        container.checkCrashes()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.reports.export.collect { if (it is ExportState.Ready) shareReport() }
            }
        }
        // A recreated activity or one reopened from recents must not repeat the request that first launched it.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back from the app's settings, where a fetch waiting on the local network refusal may have been allowed.
        container.localNetwork.resumed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CONNECTING, connecting)
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

    /**
     * Asks for the notification permission on the first connect only: once the user has declined it, Android
     * reports a rationale and the request is skipped, and after a permanent denial the system answers at once.
     */
    private fun connect() {
        if (connecting) return
        connecting = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            if (!granted && !shouldShowRequestPermissionRationale(permission)) {
                notificationPermission.launch(permission)
                return
            }
        }
        requestVpnConsent()
    }

    private fun requestVpnConsent() {
        val consent = VpnService.prepare(this)
        if (consent != null) return vpnConsent.launch(consent)
        connecting = false
        engine.connect()
    }

    /** The system's question; below Android 17 there is no permission to ask for, so access counts as allowed. */
    private fun requestLocalNetwork() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            container.localNetwork.answered(true)
        }
    }

    /**
     * Opens the share sheet for the report that is ready, once; nothing is sent unless the user picks an app there. The
     * zip's URI is resolved off the main thread. An activity that ends meanwhile, as on rotation, puts the zip back for
     * the next one; a sheet that cannot open lets the next export start.
     */
    private fun shareReport() {
        val ready = container.reports.take() ?: return
        val title = getString(R.string.report_share_title)
        lifecycleScope.launch {
            val sheet = try {
                withContext(Dispatchers.IO) { reportShareIntent(this@MainActivity, ready.file, title) }
            } catch (e: CancellationException) {
                container.reports.putBack(ready)
                throw e
            } catch (_: IllegalArgumentException) {
                // The provider does not serve the file.
                container.reports.shareFailed()
                return@launch
            }
            try {
                reportShare.launch(sheet)
            } catch (_: ActivityNotFoundException) {
                container.reports.shareFailed()
            }
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
        private const val KEY_CONNECTING = "connecting"

        /**
         * Opens the app and runs the same path as the connect switch, asking for consent first where needed. For this
         * app's own components only, such as the Quick Settings tile when VPN consent is missing.
         */
        fun connectIntent(context: Context): Intent =
            Intent(ACTION_CONNECT).setClassName(context, CONNECT_ENTRY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
