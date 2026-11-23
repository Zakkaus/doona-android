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
    fun `what an ended write left is removed, a write in progress keeps its own`() {
        dir.mkdirs()
        fun leave(name: String, text: String, at: Long) = dir.resolve(name).apply { writeText(text) }.setLastModified(at)
        val then = 1_795_300_000_000
        val now = 1_795_300_150_000
        leave(".app-1.tmp", "x", then)
        leave(".core-77.tmp", "x", then)
        leave("1795299990000.txt", "", then)
        leave("1795299980000.txt", "source: core", then)
        leave(".app-2.tmp", "x", now)
        leave("1795300149000.txt", "", now)
        files(now).sweep()
        assertEquals(listOf(".app-2.tmp", "1795299980000.txt", "1795300149000.txt"), dir.list()!!.sorted())
    }

    @Test
    fun `a name claimed but not yet filled is no crash file`() {
        dir.mkdirs()
        dir.resolve("1795300150000.txt").createNewFile()
        files(1_795_300_150_000).write("source: app")
        assertEquals(listOf("1795300150001.txt"), files(0).list().map { it.name })
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

    @Test
    fun `two crashes at once each publish their own text`() {
        // The second is written while the first is between its part and its name, as two threads crashing together.
        var second: String? = null
        var nested = false
        lateinit var files: CrashFiles
        files = CrashFiles(dir, clock = {
            if (!nested) {
                nested = true
                second = files.write("second")
            }
            1_795_300_150_000
        })
        val first = files.write("first")
        assertEquals("first", first?.let { dir.resolve(it).readText() })
        assertEquals("second", second?.let { dir.resolve(it).readText() })
    }

    @Test
    fun `a file the core publishes meanwhile still leaves five`() {
        val base = 1_795_300_100_000
        repeat(5) { files(base + it).write("old $it") }
        var published = false
        val files = CrashFiles(dir, clock = {
            // The core publishes between the app's listing and its name.
            if (!published) dir.resolve("${base + 10}.txt").writeText("core").also { published = true }
            base + 20
        })
        files.write("app")
        assertEquals(listOf(base + 2, base + 3, base + 4, base + 10, base + 20), CrashFiles(dir).list().map { CrashFiles.number(it.name) })
    }

    @Test
    fun `a clock set before 2001 still gives a 13-digit name`() {
        // 2000-01-01, a twelve-digit time.
        val name = files(946_684_800_000).write("x")
        assertEquals(13, name?.removeSuffix(".txt")?.length)
    }
}
