package dev.doona.android.prototype.rv

import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import org.junit.Assert.assertEquals
import org.robolectric.RuntimeEnvironment

/** Records every haptic tick the screen asks for. */
internal class RvTaps : HapticFeedback {
    val kinds = mutableListOf<HapticFeedbackType>()

    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        kinds += hapticFeedbackType
    }
}

/** Sets the system animator duration scale, as Settings › Developer options or Remove animations do. */
internal fun rvAnimatorScale(scale: Float) {
    Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
}

internal fun rvText(@StringRes id: Int): String = RuntimeEnvironment.getApplication().getString(id)

internal fun SemanticsNodeInteractionCollection.assertCountIs(count: Int, why: String) = assertEquals(why, count, fetchSemanticsNodes().size)
