package dev.doona.android.feature.perapp

import dev.doona.android.R
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.feature.FailureFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page model the mapper builds from the engine's values. */
class PerAppMapperTest {
    @Test
    fun `each reason the tunnel did not come back has 活動's sentence and the action that can mend it`() {
        val expected = mapOf(
            null to (R.string.vpn_failure_start_failed to FailureFix.Retry),
            Status.Failed(FailureReason.StartFailed) to (R.string.vpn_failure_start_failed to FailureFix.Retry),
            Status.Failed(FailureReason.StartFailed, ErrorCode.Internal) to (R.string.vpn_failure_start_failed to FailureFix.Retry),
            Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes) to (R.string.first_no_nodes to FailureFix.AddSubscription),
            Status.Failed(FailureReason.VpnNotEstablished) to (R.string.vpn_failure_start_failed to FailureFix.Retry),
            Status.Failed(FailureReason.CoreStopped) to (R.string.rv_core_stopped_reason to FailureFix.Retry),
            Status.Failed(FailureReason.Revoked) to (R.string.rv_vpn_taken_reason to FailureFix.Retry),
            Status.Failed(FailureReason.NoAllowedApps) to (R.string.vpn_failure_no_allowed_apps to FailureFix.EditList),
            Status.Failed(FailureReason.IncompatibleCore) to (R.string.vpn_failure_incompatible_core to FailureFix.CheckUpdate),
            Status.Failed(FailureReason.LockdownEnabled) to (R.string.vpn_failure_lockdown_enabled to FailureFix.VpnSettings),
        )
        assertEquals("every reason is mapped", FailureReason.entries.toSet() + null, expected.keys.map { it?.reason }.toSet())
        expected.forEach { (failure, words) ->
            val state = state(rebuildFailure = failure)
            assertEquals("$failure", failure != null, state.rebuildFailed)
            assertEquals("$failure's sentence", words.first, state.rebuildReason)
            assertEquals("$failure's action", words.second, state.rebuildFix)
        }
    }

    @Test
    fun `a core this doona does not match offers 檢查更新 and no node offers 新增訂閱`() {
        assertEquals(R.string.rv_settings_check_update, state(rebuildFailure = Status.Failed(FailureReason.IncompatibleCore)).rebuildFix.label)
        assertEquals(R.string.rv_add_subscription, state(rebuildFailure = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes)).rebuildFix.label)
    }

    @Test
    fun `a read that failed is carried to the page, and with no app known nothing is listed`() {
        val unread = state(installed = emptyList(), readFailed = true)
        assertTrue(unread.readFailed && unread.unread)
        val kept = state(installed = APPS, readFailed = true)
        assertTrue("the apps an earlier read found stay", kept.readFailed && !kept.unread)
        assertFalse(state().readFailed)
    }

    @Test
    fun `until the stored policy is read the apps show as loading, even those already read`() {
        assertNull("policy not loaded", state(installed = APPS, policyLoaded = false).installed)
        assertNull("apps not read", state(installed = null).installed)
        assertEquals(listOf(RvApp("Mail", "com.example.mail")), state(installed = APPS).installed)
    }

    @Test
    fun `a save under way, a failed write and none are the page's three save states`() {
        assertEquals(RvSave.Idle, state().save)
        assertEquals(RvSave.Saving, state(busy = true).save)
        assertEquals(RvSave.Failed, state(saveFailed = true).save)
        assertEquals("a save under way hides the last failure", RvSave.Saving, state(busy = true, saveFailed = true).save)
    }

    private fun state(
        installed: List<InstalledApp>? = APPS,
        busy: Boolean = false,
        saveFailed: Boolean = false,
        policyLoaded: Boolean = true,
        rebuildFailure: Status.Failed? = null,
        readFailed: Boolean = false,
    ) = rvPerAppState(PerApp(AppPolicy.OnlyListed, setOf("com.example.mail")), null, installed, "", busy, saveFailed, policyLoaded, rebuildFailure, readFailed)

    private companion object {
        val APPS = listOf(InstalledApp("com.example.mail", "Mail"))
    }
}
