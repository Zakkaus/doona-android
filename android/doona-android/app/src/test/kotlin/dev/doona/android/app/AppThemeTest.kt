package dev.doona.android.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.theme.DoonaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The activity's theme gives RV parts and the old pages one scheme, light or dark as asked. */
@RunWith(AndroidJUnit4::class)
class AppThemeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lightDrawsRvAndTheOldPagesFromOneScheme() = check(dark = false)

    @Test
    fun darkDrawsRvAndTheOldPagesFromOneScheme() = check(dark = true)

    private fun check(dark: Boolean) {
        var rvPage = 0uL
        var oldPage = 0uL
        var materialPage = 0uL
        var rvDark: Boolean? = null
        compose.setContent {
            AppTheme(dark = dark) {
                rvPage = Rv.colors.page.value
                rvDark = Rv.colors.dark
                oldPage = DoonaTheme.colors.page.value
                materialPage = (if (dark) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow).value
            }
        }
        compose.waitForIdle()
        assertEquals(dark, rvDark)
        assertEquals("the old pages' page colour is RV's", rvPage, oldPage)
        assertEquals("Material's plumbing has the same scheme", rvPage, materialPage)
    }
}
