package dev.doona.android.feature.perapp

import dev.doona.android.R
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import org.junit.Assert.assertEquals
import org.junit.Test

/** The page model the mapper builds from the engine's values. */
class PerAppMapperTest {
    @Test
    fun `each reason the tunnel did not come back has 活動's sentence and the action that can mend it`() {
        val expected = mapOf(
            null to (R.string.vpn_failure_start_failed to RvRebuildFix.Retry),
            FailureReason.StartFailed to (R.string.vpn_failure_start_failed to RvRebuildFix.Retry),
            FailureReason.VpnNotEstablished to (R.string.vpn_failure_start_failed to RvRebuildFix.Retry),
            FailureReason.CoreStopped to (R.string.rv_core_stopped_reason to RvRebuildFix.Retry),
            FailureReason.Revoked to (R.string.rv_vpn_taken_reason to RvRebuildFix.Retry),
            FailureReason.NoAllowedApps to (R.string.vpn_failure_no_allowed_apps to RvRebuildFix.EditList),
            FailureReason.IncompatibleCore to (R.string.vpn_failure_incompatible_core to RvRebuildFix.CheckUpdate),
            FailureReason.LockdownEnabled to (R.string.vpn_failure_lockdown_enabled to RvRebuildFix.VpnSettings),
        )
        assertEquals("every reason is mapped", FailureReason.entries.toSet() + null, expected.keys)
        expected.forEach { (reason, words) ->
            val state = rvPerAppState(PerApp(), null, emptyList(), "", busy = false, saveFailed = false, policyLoaded = true, rebuildFailure = reason, readFailed = false)
            assertEquals("$reason", reason != null, state.rebuildFailed)
            assertEquals("$reason's sentence", words.first, state.rebuildReason)
            assertEquals("$reason's action", words.second, state.rebuildFix)
        }
    }
}
