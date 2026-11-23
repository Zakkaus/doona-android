package dev.doona.android.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The app's crash files follow the core's rules: the name, the mark, the cap and the five kept. */
class CrashFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root.resolve("crash")

    private fun files(now: Long) = CrashFiles(dir, clock = { now })

    @Test
    fun `a file is named by the time in milliseconds`() {
        assertEquals("1795300150000.txt", files(1_795_300_150_000).write("text"))
        assertEquals("text", dir.resolve("1795300150000.txt").readText())
    }

    @Test
    fun `a name is above every file present and the mark the core saved`() {
        dir.mkdirs()
        dir.resolve("1795300150000.txt").writeText("core")
        assertEquals("1795300150001.txt", files(1_795_300_100_000).write("app"))
        dir.resolve(".core.mark").writeText("1795300160000\n")
        assertEquals("1795300160001.txt", files(1_795_300_100_000).write("app"))
    }

    @Test
    fun `no part of a file is left under another name`() {
        files(1_795_300_150_000).write("text")
        assertEquals(listOf("1795300150000.txt"), dir.list()!!.toList())
    }

    @Test
    fun `the directory keeps the newest five`() {
        for (i in 0 until 7) files(1_795_300_150_000 + i).write("crash $i")
        assertEquals((2 until 7).map { "${1_795_300_150_000 + it}.txt" }, files(0).list().map { it.name })
    }

    @Test
    fun `a file holds 64 KiB at most, cut where a character starts`() {
        // Three bytes a character, so the cap falls inside one.
        val name = files(1_795_300_150_000).write("診".repeat(CrashFiles.MAX_BYTES))!!
        val bytes = dir.resolve(name).readBytes()
        assertTrue(bytes.size <= CrashFiles.MAX_BYTES)
        assertEquals(0, bytes.size % 3)
        assertArrayEquals(bytes, files(0).read(name))
    }

    @Test
    fun `only crash files are listed or read`() {
        dir.mkdirs()
        for (name in listOf("note.txt", "1795300150000.txt.bak", ".core.tmp", ".core.mark", "99999999999999.txt", "12a.txt")) dir.resolve(name).writeText("x")
        dir.resolve("1795300150000.txt").mkdirs()
        assertTrue(files(0).list().isEmpty())
        assertNull(files(0).read("../state.json"))
        assertNull(files(0).read("note.txt"))
        assertEquals(0, files(0).newest())
    }

    @Test
    fun `a directory that cannot be made gives no name`() {
        tmp.newFile("crash")
        assertNull(files(1_795_300_150_000).write("text"))
        assertFalse(dir.isDirectory)
    }
}
