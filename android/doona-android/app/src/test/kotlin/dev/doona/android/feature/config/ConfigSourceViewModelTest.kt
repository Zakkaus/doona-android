package dev.doona.android.feature.config

import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.engine.ConfigApplied
import dev.doona.android.engine.ConfigDraft
import dev.doona.android.engine.ConfigEngine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.FakeConfig
import dev.doona.android.engine.fake.RvSampleConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 原始碼 and 檢視變更 on the fake core: what a check, an apply and Back leave behind. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigSourceViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun editor(engine: ConfigEngine) = ConfigSourceViewModel(engine, CN, reads = Dispatchers.Unconfined)

    /** Opens rules/cn.dae, sends 影音 to `media` and checks it. */
    private fun ConfigSourceViewModel.reviewed(): ConfigSourceViewModel = apply {
        onValueChange(TextFieldValue(uiState.value.value.text.replace("影音) -> proxy", "影音) -> media")))
        review()
    }

    private suspend fun ConfigEngine.head() = configHistory().getOrThrow().first().rev

    @Test
    fun aCleanCheckShowsTheDiffAgainstTheHead() {
        val ui = editor(FakeConfig()).reviewed().uiState.value
        val diff = checkNotNull(ui.diff)
        assertEquals(RvSampleConfig.HEAD, diff.baseRev)
        assertEquals(RvSampleConfig.HEAD, diff.headRev)
        assertEquals(listOf(CN), diff.files.map { it.source })
        assertNull(ui.failure)
    }

    @Test
    fun anUnknownGroupMarksItsSpanAndShowsNoDiff() {
        val vm = editor(FakeConfig())
        vm.onValueChange(TextFieldValue(vm.uiState.value.value.text.replace("影音) -> proxy", "影音) -> nowhere")))
        vm.review()
        assertNull(vm.uiState.value.diff)
        assertEquals("nowhere", vm.uiState.value.draft?.diagnostic?.name)
    }

    @Test
    fun backNeverApplies() = runTest {
        val fake = FakeConfig()
        val vm = editor(fake).reviewed()
        vm.closeDiff()
        assertNull(vm.uiState.value.diff)
        assertEquals(RvSampleConfig.HEAD, fake.head())
    }

    @Test
    fun aFailedApplyKeepsTheDraftAndTheNextOneLands() = runTest {
        val fake = FakeConfig()
        var fail = true
        val engine = object : ConfigEngine by fake {
            override suspend fun configApply(draft: ConfigDraft): Result<ConfigApplied> =
                if (fail) Result.failure(EngineException(ErrorCode.ActivationFailed)) else fake.configApply(draft)
        }
        val vm = editor(engine).reviewed()
        vm.apply()
        assertEquals(ErrorCode.ActivationFailed, vm.uiState.value.failure)
        assertNotNull(vm.uiState.value.diff)
        assertFalse(vm.uiState.value.applied)
        assertEquals(RvSampleConfig.HEAD, fake.head())
        fail = false
        vm.apply()
        assertTrue(vm.uiState.value.applied)
        assertEquals(RvSampleConfig.HEAD + 1, fake.head())
    }

    @Test
    fun anotherApplyFirstTurnsTheDiffStale() = runTest {
        val fake = FakeConfig()
        val mine = editor(fake).reviewed()
        editor(fake).reviewed().apply()
        mine.apply()
        val ui = mine.uiState.value
        assertFalse(ui.applied)
        assertNull(ui.failure)
        assertEquals(RvSampleConfig.HEAD, ui.diff?.baseRev)
        assertEquals(RvSampleConfig.HEAD + 1, ui.diff?.headRev)
        assertEquals(RvSampleConfig.HEAD + 1, fake.head())
    }

    private companion object {
        const val CN = "rules/cn.dae"
    }
}
