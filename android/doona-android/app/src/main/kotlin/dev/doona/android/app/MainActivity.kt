package dev.doona.android.app

import android.Manifest
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
 * permission and VPN consent. Every connect request, from the UI or from [ACTION_CONNECT], goes through [connect].
 */
class MainActivity : ComponentActivity() {
    private val engine get() = (application as DoonaApplication).container.engine

    // A denied notification permission does not stop the connection; the service then runs without a visible notice.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpnConsent() }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) engine.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DoonaTheme { DoonaApp(engine, onConnect = ::connect) } }
        // A recreated activity or one reopened from recents must not repeat the request that first launched it.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (intent.action == ACTION_CONNECT) connect()
    }

    /**
     * Asks for the notification permission on the first connect only: once the user has declined it, Android
     * reports a rationale and the request is skipped, and after a permanent denial the system answers at once.
     */
    private fun connect() {
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
        if (consent == null) engine.connect() else vpnConsent.launch(consent)
    }

    companion object {
        /** Sent by the Quick Settings tile when VPN consent is missing; runs the same path as the connect switch. */
        const val ACTION_CONNECT = "dev.doona.android.action.CONNECT"
    }
}
