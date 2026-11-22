package dev.doona.android.prototype.rv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Debug builds only: the 活動 sample with its invented state, full screen on a phone, so the glass blur can be seen
 * where the platform draws it (Robolectric's goldens cannot). A traffic tile opens the traffic page; back returns.
 * Start it from the launcher's "doona RV sample", or with
 * `adb shell am start -n dev.doona.android/dev.doona.android.prototype.rv.RvPreviewActivity`.
 */
class RvPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            RvTheme(rvSystemScheme(dark), dark) {
                var traffic by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = traffic) { traffic = false }
                if (traffic) {
                    RvTrafficScreen(rvSampleActivity(), RV_SAMPLE_CONNECTION, onBack = { traffic = false })
                } else {
                    RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}, onOpenGroup = {}, onOpenTraffic = { traffic = true })
                }
            }
        }
    }
}
