package dev.doona.android.report

import dev.doona.android.engine.CrashFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exports' tokens: a pick is kept on disk before anything waits, a save that fails is tried again, and a file this version cannot read stays. */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportPicksTest : ExportFixture() {
    @Test
    fun `a pick that waits for another export's clear is noted first, so a process that ends meanwhile leaves it to the next start`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val dying = Recording(fake, hangs = true)
        val process = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val before = export(dying, scope = process)
        val tokens = (1..2).map {
            before.startExport()
            advanceUntilIdle()
            before.take()!!.token.also { before.shared() }
        }
        // The first clear does not return; the second pick waits for it.
        tokens.forEach { before.picked(it) }
        advanceUntilIdle()
        assertEquals(1, dying.cleared.size)
        process.cancel()
        val engine = Recording(fake)
        export(engine).sweep()
        advanceUntilIdle()
        assertEquals(List(2) { listOf(crash) to 1_795_300_150_000L }, engine.cleared)
    }

    @Test
    fun `a token save that fails once is tried again before the export fails`() = runTest {
        // An empty directory holds the part's name: the first try fails and removes it, the second succeeds.
        tmp.root.resolve(tokensFile.name + ".part").mkdirs()
        val fake = fixtureEngine()
        fake.crashes = listOf(CrashFile(appCrash(1_795_300_150_000), 1))
        val export = export(Recording(fake))
        export.startExport()
        advanceUntilIdle()
        assertTrue(export.export.value.toString(), export.export.value is ExportState.Ready)
    }

    @Test
    fun `a pick whose note fails once is noted on the second try, so a clear that failed is sent again`() = runTest {
        val fake = fixtureEngine()
        val crash = appCrash(1_795_300_150_000)
        fake.crashes = listOf(CrashFile(crash, 1))
        val engine = Recording(fake, failures = 1)
        val export = export(engine)
        export.startExport()
        advanceUntilIdle()
        val ready = export.take()!!
        tmp.root.resolve(tokensFile.name + ".part").mkdirs()
        export.picked(ready.token)
        advanceUntilIdle()
        export(engine).sweep()
        advanceUntilIdle()
        assertEquals(List(2) { listOf(crash) to 1_795_300_150_000L }, engine.cleared)
    }

    @Test
    fun `a tokens file this version cannot decode is kept as it is, the export fails, and a code is logged`() = runTest {
        val later = """[{"token":"t1","names":["1795300140000.txt"],"mark":1795300140000,"picked":true,"sheet":"a later version's"}]"""
        tokensFile.writeText(later)
        val fake = fixtureEngine()
        fake.crashes = listOf(CrashFile(appCrash(1_795_300_150_000), 1))
        val export = export(Recording(fake))
        export.startExport()
        advanceUntilIdle()
        export(Recording(fake)).sweep()
        advanceUntilIdle()
        assertEquals(ExportState.Failed, export.export.value)
        assertEquals(later, tokensFile.readText())
        assertTrue(AppEvents.snapshot().toString(), AppEvents.snapshot().any { it.text == ExportTokens.TOKENS_UNDECODABLE })
    }
}
