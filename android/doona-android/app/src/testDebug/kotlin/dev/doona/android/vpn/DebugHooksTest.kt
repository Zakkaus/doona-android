package dev.doona.android.vpn

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** The debug build's adb hooks; `src/testRelease` shows that a release build has none. */
@RunWith(AndroidJUnit4::class)
class DebugHooksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setMtu(mtu: Int) = DebugHooks().onReceive(context, Intent(DebugHooks.ACTION_MTU).putExtra(DebugHooks.EXTRA_MTU, mtu))

    @Test
    fun adbSetsTheTunMtuUntilItSetsZero() {
        assertEquals(StartConfigDto.MTU, context.tunMtu())
        setMtu(1400)
        assertEquals(1400, context.tunMtu())
        setMtu(1000)
        assertEquals("below the core's minimum: refused", 1400, context.tunMtu())
        setMtu(0)
        assertEquals(StartConfigDto.MTU, context.tunMtu())
    }

    @Test
    fun theServiceBuildsItsTunWithIt() {
        setMtu(1400)
        val service = Robolectric.buildService(DoonaVpnService::class.java).get()
        assertEquals(1400, service.mtu)
        val builder = service.tunBuilder(AppRouting.Allow(emptySet()), service.mtu)
        // The builder keeps the MTU in its private VpnConfig; the public API only hands it to the system.
        val config = builder.javaClass.getDeclaredField("mConfig").apply { isAccessible = true }.get(builder)
        assertEquals(1400, config.javaClass.getField("mtu").getInt(config))
    }

    @Test
    fun onlyTheShellAndTheSystemCanSendThem() {
        for (action in listOf(DebugHooks.ACTION_MTU, DebugHooks.ACTION_FAULT)) {
            val intent = Intent(action).setPackage(context.packageName)
            val receiver = context.packageManager.queryBroadcastReceivers(intent, 0).single().activityInfo
            assertEquals(DebugHooks::class.java.name, receiver.name)
            assertEquals("android.permission.DUMP", receiver.permission)
        }
    }
}
