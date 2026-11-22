package dev.doona.android.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * The only activity. It owns the steps that need an activity before the engine may connect: the notification
 * permission and VPN consent. Every connect request, from the UI or from [connectIntent], goes through [connect].
 */
class MainActivity : ComponentActivity() {
    private val engine get() = (application as DoonaApplication).container.engine
    private val imports get() = (application as DoonaApplication).container.imports

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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        connecting = savedInstanceState?.getBoolean(KEY_CONNECTING) ?: false
        setContent { DoonaTheme { DoonaApp(engine, onConnect = ::connect, imports = imports) } }
        // A recreated activity or one reopened from recents must not repeat the request that first launched it.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_CONNECTING, connecting)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

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
