package dev.doona.android.prototype.rv

import android.content.res.XmlResourceParser
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.feature.nodes.RV_PICKER
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The launcher shortcuts: every entry of xml/rv_shortcuts opens [RvPreviewActivity] with an action it handles, and the
 * sample runs that action on screen (連線 through 連線中, with its one tick) or opens on it (節點, the picker).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvShortcutsTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val taps = RvTaps()

    @Test
    fun everyShortcutOpensTheSampleWithAnActionItHandles() {
        val intents = shortcutIntents()
        assertEquals("one shortcut per launch action", RvLaunch.entries.mapNotNull { it.action }.sorted(), intents.map { it.action }.sorted())
        intents.forEach { assertEquals("${it.action} opens the sample", RvPreviewActivity::class.java.name, it.target) }
    }

    /** The template's package is the variant's own (the profile build installs as dev.doona.android.profile). */
    @Test
    fun everyShortcutTargetsThisBuildsPackage() {
        val app = RuntimeEnvironment.getApplication().packageName
        shortcutIntents().forEach { assertEquals("${it.action}'s package", app, it.pkg) }
    }

    @Test
    fun theConnectShortcutConnectsOnScreen() {
        app(RvSampleStart(connected = false, launch = RvLaunch.Connect))
        compose.mainClock.advanceTimeBy(RvMotion.FADE_MS + FRAMES_MS)
        compose.onNodeWithText(caption(R.string.status_starting)).assertExists()
        compose.mainClock.advanceTimeBy(RV_CONNECT_MS + RvMotion.FADE_MS)
        compose.onNodeWithText(caption(R.string.backend_connected)).assertExists()
        assertEquals(listOf(HapticFeedbackType.ToggleOn), taps.kinds)
    }

    @Test
    fun theNodesShortcutOpensOnThePicker() {
        app(RvSampleStart(launch = RvLaunch.Nodes))
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        assertEquals(0f, compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot().top.value, EDGE)
    }

    private fun app(start: RvSampleStart) {
        compose.setContent { CompositionLocalProvider(LocalHapticFeedback provides taps) { RvPreviewApp(RvMotionOptions(), start) } }
        compose.mainClock.autoAdvance = false
    }

    private data class ShortcutIntent(val action: String, val pkg: String, val target: String)

    /** Each shortcut's intent: its action and the package and activity it targets. */
    private fun shortcutIntents(): List<ShortcutIntent> {
        val parser = RuntimeEnvironment.getApplication().resources.getXml(R.xml.rv_shortcuts)
        val found = mutableListOf<ShortcutIntent>()
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType != XmlResourceParser.START_TAG || parser.name != "intent") continue
            found += ShortcutIntent(
                parser.getAttributeValue(ANDROID, "action"),
                parser.getAttributeValue(ANDROID, "targetPackage"),
                parser.getAttributeValue(ANDROID, "targetClass"),
            )
        }
        return found
    }

    /** 活動's caption in a state: the state, then the mode (規則). */
    private fun caption(@StringRes state: Int) = rvText(state) + SEPARATOR + rvText(R.string.rv_mode_rule)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val FRAMES_MS = 50L
        const val EDGE = 0.5f
    }
}
