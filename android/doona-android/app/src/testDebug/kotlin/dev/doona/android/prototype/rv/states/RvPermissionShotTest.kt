package dev.doona.android.prototype.rv.states

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.rv.RvConfirmContent
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.feature.permissions.text
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvActivityScreenSample
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.rvIdleActivity
import dev.doona.android.prototype.rv.rvSampleActivity
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first run's explanations, drawn as their sheet's window draws them once in: the VPN's over 活動 not yet connected,
 * where 連線 raised it (`proto-rv-perm-vpn-*`), and the notification's over 活動 just connected
 * (`-perm-notify-*`); each light, dark, fs115 and fs200 in Traditional Chinese, and light in English (`-en-light`) and
 * Simplified Chinese (`-zhcn-light`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPermissionShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun vpnLight() = shot("perm-vpn", RvPermission.Vpn, dark = false)

    @Test fun vpnDark() = shot("perm-vpn", RvPermission.Vpn, dark = true)

    @Test fun vpnFs115() = shot("perm-vpn", RvPermission.Vpn, fontScale = RV_FS115)

    @Test fun vpnFs200() = shot("perm-vpn", RvPermission.Vpn, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun vpnEn() = shot("perm-vpn-en", RvPermission.Vpn)

    @Test
    @Config(qualifiers = ZH_CN)
    fun vpnZhCn() = shot("perm-vpn-zhcn", RvPermission.Vpn)

    @Test fun notifyLight() = shot("perm-notify", RvPermission.Notify, dark = false)

    @Test fun notifyDark() = shot("perm-notify", RvPermission.Notify, dark = true)

    @Test fun notifyFs115() = shot("perm-notify", RvPermission.Notify, fontScale = RV_FS115)

    @Test fun notifyFs200() = shot("perm-notify", RvPermission.Notify, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = RV_EN)
    fun notifyEn() = shot("perm-notify-en", RvPermission.Notify)

    @Test
    @Config(qualifiers = ZH_CN)
    fun notifyZhCn() = shot("perm-notify-zhcn", RvPermission.Notify)

    private fun shot(name: String, permission: RvPermission, dark: Boolean = false, fontScale: Float = 1f) =
        compose.rvShot(name, dark, fontScale = fontScale) { Over(permission) }

    @Composable
    private fun Over(permission: RvPermission) {
        Box(Modifier.fillMaxSize()) {
            val activity = if (permission == RvPermission.Vpn) rvIdleActivity() else rvSampleActivity()
            RvActivityScreenSample(activity, onAction = {}, onConnect = {})
            val text = permission.text()
            RvSheetLayout({ 1f }, {}, text.title) {
                RvConfirmContent(text.title, text.body, text.confirm, text.dismiss, onConfirm = {}, onDismiss = {})
            }
        }
    }

    private companion object {
        const val ZH_CN = "zh-rCN-w412dp-h915dp-notnight-xhdpi"
    }
}
