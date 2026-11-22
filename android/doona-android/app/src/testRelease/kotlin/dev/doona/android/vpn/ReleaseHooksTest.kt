package dev.doona.android.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** A release build has none of the debug build's adb hooks (`src/debug`). Run with `:app:testReleaseUnitTest`. */
@RunWith(AndroidJUnit4::class)
class ReleaseHooksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theTunMtuIsTheDefaultWhateverTheDebugPreferenceHolds() {
        // What the debug build's MTU hook writes.
        context.getSharedPreferences("doona.debug", Context.MODE_PRIVATE).edit().putInt("mtu", 1400).commit()
        assertEquals(StartConfigDto.MTU, context.tunMtu())
        assertEquals(StartConfigDto.MTU, Robolectric.buildService(DoonaVpnService::class.java).get().mtu)
    }

    @Test
    fun noReceiverTakesTheDebugActions() {
        assertThrows(ClassNotFoundException::class.java) { Class.forName("dev.doona.android.vpn.DebugHooks") }
        for (action in listOf("dev.doona.android.debug.MTU", "dev.doona.android.debug.FAULT")) {
            val intent = Intent(action).setPackage(context.packageName)
            assertTrue(action, context.packageManager.queryBroadcastReceivers(intent, 0).isEmpty())
        }
    }
}
