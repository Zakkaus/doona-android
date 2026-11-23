package dev.doona.android.designsystem

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.AppIcons
import dev.doona.android.designsystem.components.DoonaAppIcon
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.setDoonaContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AppIconTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun emptyCache() = AppIcons.clear()

    /** Robolectric answers `getApplicationIcon` only for a package whose icon the test set; any other is not found. */
    private fun install(pkg: String, color: Int = Color.RED) =
        shadowOf(context.packageManager).setApplicationIcon(pkg, ColorDrawable(color))

    @Test
    fun anInstalledAppsIconIsDrawnAtTheRequestedSizeAndKept() = runBlocking {
        install("com.example.app")
        val icon = AppIcons.load(context, "com.example.app", 48)
        assertNotNull(icon)
        assertEquals(48, icon!!.width)
        assertEquals(48, icon.height)
        assertEquals("the icon is drawn into the bitmap", Color.RED, icon.asAndroidBitmap().getPixel(24, 24))
        assertSame("a row scrolled back into view takes it from the cache", icon, AppIcons.cached("com.example.app", 48))
    }

    @Test
    fun theSameAppAtAnotherSizeIsAnotherEntry() = runBlocking {
        install("com.example.app")
        AppIcons.load(context, "com.example.app", 48)
        assertNull(AppIcons.cached("com.example.app", 96))
    }

    @Test
    fun aPackageThatIsNotInstalledHasNoIcon() = runBlocking {
        assertNull(AppIcons.load(context, "com.example.missing", 48))
        assertNull(AppIcons.cached("com.example.missing", 48))
    }

    @Test
    fun aReadThatFailedIsTriedAgainOnceTheAppIsThere() = runBlocking {
        assertNull(AppIcons.load(context, "com.example.app", 48))
        install("com.example.app")
        assertNotNull("a failure is not kept as a miss", AppIcons.load(context, "com.example.app", 48))
    }

    @Test
    fun theBoxShowsTheIconOnceLoadedAndAPlaceholderOfTheSameSizeWithout() {
        install("com.example.app")
        var edge = 0.dp
        var px = 0
        compose.setDoonaContent {
            edge = DoonaTheme.size.appIcon
            px = with(LocalDensity.current) { edge.roundToPx() }
            // Side by side: a capture of one node includes whatever is drawn over it.
            Row {
                DoonaAppIcon("com.example.app", Modifier.testTag("icon"))
                DoonaAppIcon("com.example.missing", Modifier.testTag("placeholder"))
            }
        }
        compose.waitUntil(5_000) { AppIcons.cached("com.example.app", px) != null }
        compose.waitForIdle()

        for (tag in listOf("icon", "placeholder")) {
            compose.onNodeWithTag(tag).assertWidthIsEqualTo(edge).assertHeightIsEqualTo(edge)
        }
        val center = { tag: String ->
            val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2].toArgb()
        }
        assertEquals("the loaded icon is drawn", Color.RED, center("icon"))
        assertNotEquals("the placeholder is not the icon", Color.RED, center("placeholder"))
    }
}
