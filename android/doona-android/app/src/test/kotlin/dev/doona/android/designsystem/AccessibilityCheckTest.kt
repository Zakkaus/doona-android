package dev.doona.android.designsystem

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.checkRoboAccessibility
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaIconAction
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessibility check of the screenshot tests must fail on what it is there for. An icon action with no name is the
 * defect it exists to catch: the check passes the labelled action and fails the unlabelled one.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class AccessibilityCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(label: String): Throwable? {
        compose.setDoonaContent { DoonaIconAction(R.drawable.ic_search, label, onClick = {}) }
        compose.drawFully()
        return runCatching { compose.onRoot().checkRoboAccessibility() }.exceptionOrNull()
    }

    @Test
    fun aLabelledIconActionPasses() {
        val failure = check("Search")
        assertNull(failure?.toString(), failure)
    }

    @Test
    fun anUnlabelledIconActionFails() = assertNotNull("the check passed an icon action that a screen reader cannot name", check(""))
}
