package dev.doona.android.vpn

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRoutingTest {
    private val installed = setOf("org.mozilla.firefox", "com.example.chat")

    private fun resolve(policy: AppPolicy, vararg packages: String) =
        AppRouting.resolve(PerApp(policy, packages.toSet()), SELF) { listed -> listed intersect installed }

    @Test
    fun allExcludesOnlyDoona() {
        assertEquals(AppRouting.Exclude(setOf(SELF)), resolve(AppPolicy.All, "org.mozilla.firefox"))
    }

    @Test
    fun exceptListedExcludesTheInstalledListedAppsAndDoona() {
        assertEquals(
            AppRouting.Exclude(setOf("org.mozilla.firefox", SELF)),
            resolve(AppPolicy.ExceptListed, "org.mozilla.firefox", "gone.app"),
        )
    }

    @Test
    fun onlyListedAllowsTheInstalledListedAppsButNeverDoona() {
        assertEquals(
            AppRouting.Allow(setOf("com.example.chat")),
            resolve(AppPolicy.OnlyListed, "com.example.chat", SELF, "gone.app"),
        )
    }

    @Test
    fun onlyListedWithNoInstalledAppIsRefused() {
        assertNull(resolve(AppPolicy.OnlyListed, "gone.app", SELF))
        assertNull(resolve(AppPolicy.OnlyListed))
    }

    private companion object {
        const val SELF = "dev.doona.android"
    }
}
