package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 授權條款 (`proto-rv-licenses-*`, the whole page at each font size) and tokio's licence as its text page opens it
 * (`proto-rv-license-text-*`, the phone's first screen), light, dark, fs115 and fs200; that a row opens its component's
 * text; that every component's text is in the assets; and how a licence file becomes paragraphs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLicensesShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun licensesLight() = compose.rvShot("licenses", dark = false, before = { assertWhole() }) { Licenses() }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun licensesDark() = compose.rvShot("licenses", dark = true, before = { assertWhole() }) { Licenses() }

    @Test
    @Config(qualifiers = LONG_PAGE_FS115)
    fun licensesFs115() = compose.rvShot("licenses", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Licenses() }

    @Test
    @Config(qualifiers = LONG_PAGE_FS200)
    fun licensesFs200() = compose.rvShot("licenses", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Licenses() }

    @Test fun textLight() = compose.rvShot("license-text", dark = false) { Tokio() }

    @Test fun textDark() = compose.rvShot("license-text", dark = true) { Tokio() }

    @Test fun textFs115() = compose.rvShot("license-text", dark = false, fontScale = RV_FS115) { Tokio() }

    @Test fun textFs200() = compose.rvShot("license-text", dark = false, fontScale = RV_FS200) { Tokio() }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun aRowOpensItsComponentsText() {
        var opened: Int? = null
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLicensesScreen(onOpen = { opened = it }, onBack = {}) } }
        compose.onNodeWithText("tokio").performClick()
        assertEquals(RvLicenses.all.indexOfFirst { it.name == "tokio" }, opened)
    }

    @Test
    fun everyComponentHasItsText() {
        val assets = RuntimeEnvironment.getApplication().assets
        for (license in RvLicenses.all) {
            val text = assets.open(license.file).bufferedReader().use { it.readText() }
            assertTrue("${license.name}'s text is empty", text.isNotBlank())
        }
    }

    @Test
    fun aLicenceFileBecomesParagraphs() {
        val mit = "MIT License\n\nCopyright (c) Tokio Contributors\n\nPermission is hereby granted, free of charge, to any person obtaining a copy\nof this software.\n"
        assertEquals(listOf("MIT License", "Copyright (c) Tokio Contributors", "Permission is hereby granted, free of charge, to any person obtaining a copy of this software."), rvParagraphs(mit))
        val ofl = "-----------\nSIL OPEN FONT LICENSE Version 1.1\n-----------\n\nPermission is granted:\n* to use\n  and study\n* to modify\n"
        assertEquals(listOf("-----------\nSIL OPEN FONT LICENSE Version 1.1\n-----------", "Permission is granted:\n* to use and study\n* to modify"), rvParagraphs(ofl))
    }

    @Composable
    private fun Licenses() = RvLicensesScreen(onOpen = {}, onBack = {})

    @Composable
    private fun Tokio() = RvLicenseText(RvLicenses.all.first { it.name == "tokio" }, onBack = {})

    private companion object {
        /** As tall as the whole 授權條款 page at each font size, so its last row shows as the phone shows it. */
        const val LONG_PAGE = "+h1281dp"
        const val LONG_PAGE_FS115 = "+h1281dp"
        const val LONG_PAGE_FS200 = "+h1596dp"
    }
}
