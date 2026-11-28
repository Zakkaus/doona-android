package dev.doona.android.feature.config

import dev.doona.android.engine.ExportKind
import dev.doona.android.engine.fake.FakeConfig
import dev.doona.android.engine.fake.RvSampleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 匯出設定檔 on 版本紀錄: what each kind hands the picker and what the page says about files outside the tree. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigExportViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun history(hosts: Boolean) = ConfigHistoryViewModel(
        FakeConfig(revisions = mutableMapOf(RvSampleConfig.HEAD to RvSampleConfig.texts(hosts = hosts))),
        now = { RvSampleConfig.NOW },
        reads = Dispatchers.Unconfined,
    )

    @Test
    fun aRefusedPortableCopyHandsThePickerNothingAndSaysWhy() {
        val vm = history(hosts = true)
        vm.export(ExportKind.Portable)
        assertNull(vm.exported.value)
        assertEquals(RvExportNotice(refused = true, paths = listOf(RvSampleConfig.HOSTS_PATH)), vm.exportNotice.value)
    }

    @Test
    fun anArchiveStreamsAndNamesWhatItLeftOut() {
        val vm = history(hosts = true)
        vm.export(ExportKind.Archive)
        assertEquals("export-${RvSampleConfig.HEAD}-archive", vm.exported.value)
        assertEquals(RvExportNotice(refused = false, paths = listOf(RvSampleConfig.HOSTS_PATH)), vm.exportNotice.value)
        vm.exportNoticeSeen()
        assertNull(vm.exportNotice.value)
    }

    @Test
    fun aClosedTreeExportsPortableWithNoNotice() {
        val vm = history(hosts = false)
        vm.export(ExportKind.Portable)
        assertEquals("export-${RvSampleConfig.HEAD}-portable", vm.exported.value)
        assertNull(vm.exportNotice.value)
    }
}
