package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.settings.RvLicenseText
import dev.doona.android.feature.settings.RvLicenses
import dev.doona.android.feature.settings.RvLicensesScreen
import dev.doona.android.feature.settings.rvBrandMarks
import dev.doona.android.feature.settings.rvParagraphs
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
 * text; that every component's text is in the assets; and how a licence file becomes paragraphs. The brand marks'
 * entry, last on the page, and its notice (`proto-rv-license-brand-marks-*`), also in English and Simplified Chinese.
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

    @Test fun brandMarksLight() = compose.rvShot("license-brand-marks", dark = false) { BrandMarks() }

    @Test fun brandMarksDark() = compose.rvShot("license-brand-marks", dark = true) { BrandMarks() }

    @Test fun brandMarksFs115() = compose.rvShot("license-brand-marks", dark = false, fontScale = RV_FS115) { BrandMarks() }

    @Test fun brandMarksFs200() = compose.rvShot("license-brand-marks", dark = false, fontScale = RV_FS200) { BrandMarks() }

    @Test
    @Config(qualifiers = "+en")
    fun brandMarksEnglish() = compose.rvShot("license-brand-marks-en", dark = false) { BrandMarks() }

    @Test
    @Config(qualifiers = "+zh-rCN")
    fun brandMarksSimplified() = compose.rvShot("license-brand-marks-zh-cn", dark = false) { BrandMarks() }

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun aRowOpensItsComponentsText() {
        var opened: Int? = null
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLicensesScreen(onOpen = { opened = it }, onBack = {}) } }
        compose.onNodeWithText("tokio").performClick()
        assertEquals(RvLicenses.all(RuntimeEnvironment.getApplication().assets).indexOfFirst { it.name == "tokio" }, opened)
    }

    @Test
    fun everyComponentHasItsText() {
        val assets = RuntimeEnvironment.getApplication().assets
        for (license in RvLicenses.all(assets)) {
            val text = license.text(assets)
            if (license.name == "tokio") assertTrue(text.contains("Copyright (c) Tokio Contributors"))
            if (license.name == "BoringSSL") assertTrue(text.contains("Neither the name of Google Inc."))
            if (license.name == "Kotlin") assertTrue(text.contains("org.jetbrains.kotlin:kotlin-stdlib: Apache-2.0 AND BSD-3-Clause AND BSL-1.0"))
            if (license.name == "AndroidX") assertTrue(text.contains("androidx.datastore:datastore-preferences-external-protobuf: BSD-3-Clause"))
            assertTrue("${license.name}'s text is empty", text.isNotBlank())
        }
    }

    /** The brand marks are the page's last entry; their text is the trademark notice, then each source with its licence. */
    @Test
    @Config(qualifiers = LONG_PAGE)
    fun theBrandMarksComeLastWithTheirNotice() {
        var opened: Int? = null
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLicensesScreen(onOpen = { opened = it }, onBack = {}) } }
        compose.onNodeWithText("品牌標誌").performClick()
        assertEquals(RvLicenses.all(RuntimeEnvironment.getApplication().assets).size, opened)
        val text = RuntimeEnvironment.getApplication().getString(R.string.rv_license_brand_marks_text)
        assertEquals(
            listOf(
                "新增服務規則頁上各服務的品牌標誌是其所有者的商標，只用來標示規則所指的服務。使用這些標誌不代表與所有者有任何關聯或獲其認可，doona 的授權也不涵蓋這些標誌。",
                "來源：\n- Simple Icons：CC0 1.0\n- Wikimedia Commons 標誌（Nintendo、Disney+、Prime Video）：公有領域\n- Bootstrap Icons（Xbox、Microsoft、OpenAI）：MIT",
            ),
            rvParagraphs(text),
        )
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
    private fun Tokio() = RvLicenseText(dev.doona.android.feature.settings.rvLicenseEntries().first { it.name == "tokio" }, onBack = {})

    @Composable
    private fun BrandMarks() = RvLicenseText(rvBrandMarks(), onBack = {})

    private companion object {
        /** As tall as the whole 授權條款 page at each font size, so its last row shows as the phone shows it. */
        const val LONG_PAGE = "+h1464dp"
        const val LONG_PAGE_FS115 = "+h1472dp"
        const val LONG_PAGE_FS200 = "+h2042dp"
    }
}
