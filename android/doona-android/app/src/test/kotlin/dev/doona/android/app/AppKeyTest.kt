package dev.doona.android.app

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavKeySerializer
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The back stack keys as process death sees them: Navigation 3 saves each under its class name and finds its
 * serializer by that name (`NavKeySerializer`), which is what `proguard-rules.pro` keeps through R8 for every
 * `@Serializable` class in the app. Every key round-trips, carries only ids, and the list here is every key there is.
 */
class AppKeyTest {
    private val keys: List<AppKey> = listOf(
        ActivityKey, PoliciesKey, SettingsKey, TrafficKey, StatsKey, NodesKey, NodeSearchKey, NodeKey("n-1"), GroupKey("g-Proxy"),
        MainNodeChoiceKey, RulesKey, TemplateKey, TemplateImpactKey("t-1"), EffectiveConfigKey, SubscriptionsKey, SubAddKey,
        SubDetailKey("s-1"), PerAppKey, ReportKey, AboutKey, LanguageKey, IntervalKey, LabKey, ThemeKey, DnsKey, LicensesKey,
        LicenseTextKey("0"),
    )

    @Test
    fun everyKeyIsListedHere() {
        val sealed = AppKey.serializer().descriptor.getElementDescriptor(1).elementDescriptors.map { it.serialName }.toSet()
        assertEquals(sealed, keys.map { it::class.java.name }.toSet())
    }

    @Test
    fun everyKeyRoundTripsUnderItsClassName() {
        val json = Json
        keys.forEach { key ->
            val text = json.encodeToString(NavKeySerializer<NavKey>(), key)
            assertEquals(key, json.decodeFromString(NavKeySerializer<NavKey>(), text))
        }
    }

    @Test
    fun aKeyCarriesOnlyIds() {
        keys.forEach { key ->
            val fields = key::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            fields.forEach { assertEquals("$key: ${it.name}", String::class.java, it.type) }
        }
    }
}
