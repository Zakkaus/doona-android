package dev.doona.android.report

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

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
        assertEquals(listOf(".lock", "1795300150000.txt"), dir.list()!!.sorted())
    }

    @Test
    fun `what an ended write left is removed at once, the parts of earlier versions too`() {
        dir.mkdirs()
        val leftOver = listOf("1795300149000.part-app", "1795300149001.part-core", ".app.tmp", ".core.tmp", ".app-2.tmp", ".core-77.tmp")
        for (name in leftOver) dir.resolve(name).writeText("x")
        dir.resolve("1795299980000.txt").writeText("source: core")
        files(1_795_300_150_000).sweep()
        assertEquals(listOf("1795299980000.txt"), dir.list()!!.filter { it != ".lock" })
    }

    @Test
    fun `a write waits while another process holds the directory, then takes a number above the mark it finds`() {
        dir.mkdirs()
        val written = AtomicReference<String?>()
        val other = OtherProcess(dir.resolve(".lock"), tmp.newFolder("holder"))
        val writer = thread { written.set(files(1_795_300_150_000).write("app")) }
        writer.join(300)
        val waited = writer.isAlive
        // As a clear in the other process: the mark it saved.
        dir.resolve(".core.mark").writeText("1795300160000\n")
        other.close()
        writer.join(10_000)
        assertTrue("written while another process held the lock", waited)
        assertEquals("1795300160001.txt", written.get())
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
        val files = files(1_795_300_150_000)
        val start = CountDownLatch(1)
        val names = (1..2).map { n -> n to AtomicReference<String?>() }
        val writers = names.map { (n, name) ->
            thread {
                start.await()
                name.set(files.write("crash $n"))
            }
        }
        start.countDown()
        writers.forEach { it.join(10_000) }
        assertEquals(setOf("1795300150000.txt", "1795300150001.txt"), names.mapNotNull { it.second.get() }.toSet())
        for ((n, name) in names) assertEquals("crash $n", dir.resolve(name.get()!!).readText())
    }

    @Test
    fun `a write ended at any step leaves five files at most, and the next removes its part`() {
        val base = 1_795_300_100_000
        for (at in CrashFiles.Step.entries) {
            dir.deleteRecursively()
            repeat(5) { files(base + it).write("old $it") }
            val killed = files(base + 10).apply { step = { if (it == at) throw Killed() } }
            assertThrows(Killed::class.java) { killed.write("app") }
            assertTrue("$at", files(0).list().size <= CrashFiles.MAX_FILES)
            assertEquals("$at", 1, dir.list()!!.count { ".part-" in it })
            val next = files(base + 20).write("next")
            assertEquals("$at", CrashFiles.MAX_FILES, files(0).list().size)
            assertEquals("$at", next, files(0).list().last().name)
            assertEquals("$at", 0, dir.list()!!.count { ".part-" in it })
        }
    }

    @Test
    fun `an old file that will not go stops the write, and its part goes`() {
        val base = 1_795_300_100_000
        repeat(5) { files(base + it).write("old $it") }
        val oldest = dir.resolve("$base.txt")
        val files = files(base + 10).apply {
            step = {
                // The oldest becomes a directory that holds a file, which a delete cannot remove.
                if (it == CrashFiles.Step.Written) {
                    oldest.delete()
                    oldest.resolve("kept").apply { parentFile!!.mkdirs() }.createNewFile()
                }
            }
        }
        assertNull(files.write("app"))
        assertEquals((1 until 5).map { "${base + it}.txt" }, files(0).list().map { it.name })
        assertEquals(0, dir.list()!!.count { ".part-" in it })
    }

    @Test
    fun `a clock set before 2001 still gives a 13-digit name`() {
        // 2000-01-01, a twelve-digit time.
        val name = files(946_684_800_000).write("x")
        assertEquals(13, name?.removeSuffix(".txt")?.length)
    }
}

/** Thrown to end a write at a step, as the system ending the process would: no IOException, so nothing is cleaned up. */
private class Killed : Error()

/** Another process holding the crash directory's lock as the app takes it, with `FileChannel.lock`: a JVM of its own. */
private class OtherProcess(lock: File, scratch: File) : AutoCloseable {
    private val process: Process

    init {
        val source = scratch.resolve("Holder.java").apply { writeText(HOLDER) }
        process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, source.path, lock.path).redirectErrorStream(true).start()
        val first = process.inputStream.bufferedReader().readLine()
        check(first == "locked") { "the other process did not lock: $first" }
    }

    override fun close() {
        process.outputStream.close()
        check(process.waitFor(30, TimeUnit.SECONDS))
    }

    private companion object {
        val HOLDER = """
            public class Holder {
                public static void main(String[] args) throws Exception {
                    try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(args[0], "rw")) {
                        file.getChannel().lock();
                        System.out.println("locked");
                        System.out.flush();
                        System.in.read();
                    }
                }
            }
        """.trimIndent()
    }
}
