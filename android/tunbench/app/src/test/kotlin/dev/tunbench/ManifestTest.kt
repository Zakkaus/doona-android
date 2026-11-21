package dev.tunbench

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/** The merged debug manifest that AGP hands the unit tests (test_config.properties). */
class ManifestTest {
    private val root: Element = run {
        val config = Properties()
        javaClass.classLoader!!.getResourceAsStream("com/android/tools/test_config.properties")!!
            .use { config.load(it) }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(File(config.getProperty("android_merged_manifest")))
            .documentElement
    }

    @Test
    fun everythingRunsInOneProcess() {
        // JNI globals and the bench accounting live in one process.
        val separate = elements("*").filter { it.hasAttributeNS(ANDROID, "process") }
        assertEquals(emptyList<String>(), separate.map { it.attr("name") })
    }

    @Test
    fun theShellStartsBothActivities() {
        for (name in listOf("dev.tunbench.MainActivity", "dev.tunbench.BenchActivity")) {
            assertEquals(name, "true", component("activity", name).attr("exported"))
        }
    }

    @Test
    fun onlyTheSystemBindsTheVpnService() {
        val service = component("service", "dev.tunbench.TunService")
        assertEquals("false", service.attr("exported"))
        assertEquals("android.permission.BIND_VPN_SERVICE", service.attr("permission"))
        assertEquals("systemExempted", service.attr("foregroundServiceType"))
    }

    @Test
    fun foregroundServicePermissionsAreDeclared() {
        val declared = elements("uses-permission").map { it.attr("name") }.toSet()
        val needed = setOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED",
        )
        assertEquals(needed, needed intersect declared)
    }

    @Test
    fun noInitializerOutsideTheAllowList() {
        // Deferred initializer work could wake the process during a measurement. Each one that is
        // allowed is listed here with the reason it may run in the measured process.
        val allowed = emptySet<String>()
        val initializers = elements("provider")
            .filter { it.attr("name") == "androidx.startup.InitializationProvider" }
            .flatMap { it.children("meta-data") }
            .map { it.attr("name") }
        assertEquals(emptySet<String>(), initializers.toSet() - allowed)
    }

    private fun elements(tag: String): List<Element> = root.children(tag, deep = true)

    private fun component(tag: String, name: String): Element =
        elements(tag).singleOrNull { it.attr("name") == name } ?: error("no <$tag> $name")

    private fun Element.children(tag: String, deep: Boolean = false): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { deep || it.parentNode == this }
    }

    private fun Element.attr(name: String): String = getAttributeNS(ANDROID, name)

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
