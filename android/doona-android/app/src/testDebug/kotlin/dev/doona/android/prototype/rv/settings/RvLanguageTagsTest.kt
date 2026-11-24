package dev.doona.android.prototype.rv.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each 語言 choice as the per-app locale tag it sets, and the choice a tag list reads back as. */
class RvLanguageTagsTest {
    @Test
    fun eachChoiceReadsBackFromItsOwnTag() {
        RvLanguage.entries.forEach { assertEquals(it, RvLanguage.of(it.tag)) }
    }

    @Test
    fun chineseIsTraditionalByScriptOrRegion() {
        assertEquals(RvLanguage.TraditionalChinese, RvLanguage.of("zh-Hant-HK"))
        assertEquals(RvLanguage.TraditionalChinese, RvLanguage.of("zh-HK,en"))
        assertEquals(RvLanguage.TraditionalChinese, RvLanguage.of("zh-MO"))
        assertEquals(RvLanguage.SimplifiedChinese, RvLanguage.of("zh-Hans-TW"))
        assertEquals(RvLanguage.SimplifiedChinese, RvLanguage.of("zh-SG"))
        assertEquals(RvLanguage.SimplifiedChinese, RvLanguage.of("zh"))
    }

    @Test
    fun anyOtherLanguageShowsEnglishAndNoneFollowsTheSystem() {
        assertEquals(RvLanguage.English, RvLanguage.of("en-GB"))
        assertEquals(RvLanguage.English, RvLanguage.of("fr-FR,zh-TW"))
        assertEquals(RvLanguage.System, RvLanguage.of(""))
    }
}
