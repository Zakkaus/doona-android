package dev.doona.android.engine.fake

import dev.doona.android.engine.ExportKind
import dev.doona.android.engine.SourceAt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Export (config-plan row 16): an applied revision whose DNS names a hosts file outside the tree. */
class FakeConfigExportTest {
    private fun config(hosts: Boolean) = FakeConfig(revisions = mutableMapOf(RvSampleConfig.HEAD to RvSampleConfig.texts(hosts = hosts)))

    @Test
    fun aClosedTreeExportsEitherWay() = runTest {
        val fake = config(hosts = false)
        for (kind in ExportKind.entries) {
            val staged = fake.configExport(RvSampleConfig.HEAD, kind).getOrThrow()
            assertNotNull(staged.token)
            assertTrue(staged.portable)
            assertEquals(emptyList<String>(), staged.external)
            assertEquals(6, staged.files)
        }
    }

    @Test
    fun aPortableRequestOnAnEscapingHostsSourceStagesNothing() = runTest {
        val staged = config(hosts = true).configExport(RvSampleConfig.HEAD, ExportKind.Portable).getOrThrow()
        assertNull(staged.token)
        assertFalse(staged.portable)
        assertEquals(listOf(RvSampleConfig.HOSTS_PATH), staged.external)
    }

    @Test
    fun anArchiveKeepsTheDeclarationAndDisclosesTheMissingFile() = runTest {
        val fake = config(hosts = true)
        val staged = fake.configExport(RvSampleConfig.HEAD, ExportKind.Archive).getOrThrow()
        assertNotNull(staged.token)
        assertFalse(staged.portable)
        assertEquals(listOf(RvSampleConfig.HOSTS_PATH), staged.external)
        val dns = fake.configSource(SourceAt.Revision(RvSampleConfig.HEAD), RvSampleConfig.DNS).getOrThrow()
        assertTrue("use_host: '${RvSampleConfig.HOSTS_PATH}'" in dns)
    }
}
