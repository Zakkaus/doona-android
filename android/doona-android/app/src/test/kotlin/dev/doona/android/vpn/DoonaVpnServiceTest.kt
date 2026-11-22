package dev.doona.android.vpn

import android.net.RouteInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
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
}
