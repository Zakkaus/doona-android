package dev.doona.android.vpn

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.app.FakeEngineApplication
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = FakeEngineApplication::class)
class ToggleTileServiceTest {
    @Test
    fun revokedTileShowsTheReasonInsteadOfConnectionFailed() {
        val service = Robolectric.buildService(ToggleTileService::class.java).get()
        val engine = (service.application as FakeEngineApplication).container.engine as FakeEngine
        engine.status.value = Status.Failed(FailureReason.Revoked)
        service.onStartListening()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(service.getString(R.string.vpn_drop_revoked), service.qsTile.subtitle)
        } finally {
            service.onStopListening()
        }
    }
}
