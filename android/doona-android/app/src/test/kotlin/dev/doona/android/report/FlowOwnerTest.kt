package dev.doona.android.report

import android.os.Process
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowOwnerTest {
    @Test
    fun passesResolvedAppSideEndpointsToTheOwnerLookup() {
        for ((protocol, expectedUid) in listOf(OsConstants.IPPROTO_TCP to 10123, OsConstants.IPPROTO_UDP to Process.INVALID_UID)) {
            val uid = connectionOwnerUid(protocol, "172.19.0.1:41001", "192.0.2.1:443") { transport, local, remote ->
                assertEquals(protocol, transport)
                assertFalse(local.isUnresolved)
                assertFalse(remote.isUnresolved)
                assertEquals("172.19.0.1", local.address.hostAddress)
                assertEquals(41001, local.port)
                assertEquals("192.0.2.1", remote.address.hostAddress)
                assertEquals(443, remote.port)
                expectedUid
            }
            assertEquals(expectedUid, uid)
        }
    }

    @Test
    fun anEndpointThatDoesNotParseHasNoOwner() {
        for ((local, remote) in listOf("172.19.0.1" to "192.0.2.1:443", "172.19.0.1:41001" to "192.0.2.1:", "172.19.0.1:70000" to "192.0.2.1:443")) {
            val uid = connectionOwnerUid(OsConstants.IPPROTO_TCP, local, remote) { _, _, _ -> error("looked up $local -> $remote") }
            assertEquals(Process.INVALID_UID, uid)
        }
    }
}
