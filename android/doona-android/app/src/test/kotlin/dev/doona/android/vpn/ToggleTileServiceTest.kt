package dev.doona.android.vpn

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.app.FakeEngineApplication
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class ToggleTileServiceTest {
    private val service = Robolectric.buildService(ToggleTileService::class.java).get()
    private val engine = (service.application as FakeEngineApplication).container.engine as FakeEngine

    @Test
    fun runningShowsTheMainGroupsSelectedNode() = runTest {
        engine.groups.value = engine.groups.value.reversed()
        engine.select("g-Proxy", "n-c2f5a8e1b3c4d5e6").getOrThrow()
        engine.status.value = Status.Running(0)

        withListening { assertSubtitle("us-01") }
    }

    @Test
    fun selectionChangesUpdateTheSubtitleOnlyWhileListening() = runTest {
        engine.status.value = Status.Running(0)

        withListening {
            assertSubtitle("hk-01")
            engine.select("g-Auto", "n-4b1e07d9a2c3b4d5").getOrThrow()
            assertSubtitle("hk-02")
        }

        engine.select("g-Proxy", "n-85d011c7e5f60718").getOrThrow()
        assertSubtitle("hk-02")
        withListening { assertSubtitle("jp-01") }
    }

    @Test
    fun nonRunningStatesShowTheirStatus() = withListening {
        val cases = listOf(
            Status.Starting to R.string.vpn_status_starting,
            Status.Stopping to R.string.vpn_status_stopping,
            Status.Stopped to R.string.vpn_status_stopped,
            Status.Failed(FailureReason.StartFailed) to R.string.vpn_status_failed,
            Status.Failed(FailureReason.Revoked) to R.string.vpn_drop_revoked,
        )
        for ((status, text) in cases) {
            engine.status.value = status
            assertSubtitle(service.getString(text))
        }
    }

    @Test
    fun runningWithoutASelectedNodeShowsTheRunningStatus() {
        engine.status.value = Status.Running(0)
        engine.groups.value = engine.groups.value.map {
            if (it.id == "g-Proxy") it.copy(members = emptyList()).withSharedSelection(selectedId = null, pinned = false) else it
        }

        withListening { assertSubtitle(service.getString(R.string.vpn_status_running)) }
    }

    private inline fun withListening(block: () -> Unit) {
        service.onStartListening()
        try {
            block()
        } finally {
            service.onStopListening()
        }
    }

    private fun assertSubtitle(expected: String) {
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(expected, service.qsTile.subtitle)
    }
}
