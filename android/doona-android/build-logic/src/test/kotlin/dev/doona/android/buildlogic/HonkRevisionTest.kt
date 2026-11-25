package dev.doona.android.buildlogic

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HonkRevisionTest {
    private val a = "63de98ba32f871ac6a779e7462a0a79f8a595153"
    private val b = "92211429afea6abb6ee834350d6ff9a5d8d331d2"
    private val honk = "https://github.com/daeuniverse/honk"

    private fun failure(toml: String): String =
        assertThrows(GradleException::class.java) { honkRevision(toml, "Cargo.toml") }.message.orEmpty()

    @Test
    fun inlineDependenciesGiveTheirSharedRevision() {
        val toml = """
            [dependencies]
            # honk-config = { git = "$honk", rev = "$b" }
            honk-config = { git = "$honk", rev = "$a" }
            honk-outbound = { git = "$honk", rev = "$a", features = ["rprx"] }
            honk-netstack = { git = "https://github.com/Glassyiris/honk", rev = "$b" }
        """.trimIndent()
        assertEquals(a, honkRevision(toml, "Cargo.toml"))
    }

    @Test
    fun aDependencyTableIsRead() {
        val toml = """
            [dependencies.honk-config]
            git = "$honk"
            rev = "$a"
            features = ["x"]

            [[bin]]
            name = "tool"
            rev = "$b"

            [dependencies]
            honk-outbound = { git = "$honk", rev = "$a" }
        """.trimIndent()
        assertEquals(a, honkRevision(toml, "Cargo.toml"))
    }

    @Test
    fun noHonkDependencyFails() {
        val message = failure("[dependencies]\nhonk-netstack = { git = \"https://github.com/Glassyiris/honk\", rev = \"$b\" }")
        assertTrue(message, "no honk-* dependency on $honk" in message)
    }

    @Test
    fun aHonkDependencyWithoutAFullRevFails() {
        for (fields in listOf("git = \"$honk\"", "git = \"$honk\", rev = \"63de98ba\"", "git = \"$honk\", branch = \"main\"")) {
            val message = failure("[dependencies]\nhonk-config = { $fields }")
            assertTrue(message, "honk-config pins no full 40-hex rev" in message)
        }
        val table = failure("[dependencies.honk-config]\ngit = \"$honk\"\n\n[dependencies]\nlog = \"0.4\"")
        assertTrue(table, "honk-config pins no full 40-hex rev" in table)
    }

    @Test
    fun twoDifferentRevisionsFail() {
        val toml = "[dependencies]\nhonk-config = { git = \"$honk\", rev = \"$a\" }\n" +
            "[dependencies.honk-outbound]\ngit = \"$honk\"\nrev = \"$b\""
        val message = failure(toml)
        assertTrue(message, "pin different revs" in message && a in message && b in message)
    }
}
