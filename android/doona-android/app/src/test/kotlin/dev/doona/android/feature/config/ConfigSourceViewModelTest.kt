package dev.doona.android.feature.config

import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.RvSampleConfig
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigSourceViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val honk = PolicyHonkFixtures.configTree()

    private fun editor(reads: CoroutineDispatcher = Dispatchers.Unconfined) = ConfigSourceViewModel(honk, CN, reads).apply {
        onValueChange(TextFieldValue(RvSampleConfig.cn("media", wiki = true)))
    }

    @Test
    fun aCleanCheckLaysTheBufferOverTheFileAfterTheEntryAndShowsItsDiff() {
        val ui = editor().apply { review() }.uiState.value
        val diff = checkNotNull(ui.diff)
        assertEquals(RvSampleConfig.HEAD, diff.baseRev)
        assertFalse(diff.stale)
        assertEquals(listOf(CN), diff.files.map { it.source })
        val sent = Json.parseToJsonElement(honk.calls.single { it.path == "/api/v1/config/validate" }.body!!).jsonObject
        assertEquals("full", sent.getValue("mode").jsonPrimitive.content)
        assertEquals(listOf("config.dae", CN), sent.getValue("sources").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content })
        assertNull(ui.failure)
    }

    @Test
    fun aGroupHonkDoesNotKnowMarksItsSpanAndShowsNoDiff() {
        honk.on("POST", "/api/v1/config/validate", 200, HonkFixtures.body("config-validate/unknown-target"))
        val vm = editor()
        vm.onValueChange(TextFieldValue(RvSampleConfig.cn("stream", wiki = true)))
        vm.review()
        assertNull(vm.uiState.value.diff)
        assertEquals("stream", vm.uiState.value.draft?.diagnostic?.name)
    }

    @Test
    fun applyReplacesTheFileWithTheHashItWasReadAt() = runTest {
        val vm = editor(UnconfinedTestDispatcher(testScheduler)).apply { review() }
        vm.apply()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.applied)
        val put = honk.calls.single { it.method == "PUT" }
        assertEquals("/api/v1/config/sources/source-f4", put.path)
        assertEquals(RvSampleConfig.cn("media", wiki = true), Json.parseToJsonElement(put.body!!).jsonObject.getValue("content").jsonPrimitive.content)
        val read = Json.parseToJsonElement(HonkFixtures.body("config/rv-config")).jsonObject.getValue("sources").jsonArray
            .single { it.jsonObject.getValue("path").jsonPrimitive.content == CN }.jsonObject.getValue("content_sha256").jsonPrimitive.content
        assertEquals("\"$read\"", put.headers["If-Match"])
    }

    @Test
    fun backNeverWrites() {
        editor().apply { review() }.closeDiff()
        assertTrue(honk.calls.none { it.method == "PUT" })
    }

    @Test
    fun aWriteThatLandedFirstTurnsTheDiffStaleAtReviewAndAtApply() {
        val vm = editor()
        honk.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-config-landed"))
        vm.review()
        assertEquals(RvSampleConfig.HEAD + 1, vm.uiState.value.diff?.headRev)
        honk.on("PUT", "/api/v1/config/sources/source-f4", 412, HonkFixtures.body("errors/stale-revision"))
        vm.apply()
        assertFalse(vm.uiState.value.applied)
        assertTrue(checkNotNull(vm.uiState.value.diff).stale)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun aRefusedWriteKeepsTheDiffWithItsReason() {
        val vm = editor().apply { review() }
        honk.on("PUT", "/api/v1/config/sources/source-f4", 422, HonkFixtures.body("errors/snapshot-unavailable").replace("503", "422"))
        vm.apply()
        assertFalse(vm.uiState.value.applied)
        assertEquals(ErrorCode.InvalidArgument, vm.uiState.value.failure)
    }

    private companion object {
        const val CN = "rules/cn.dae"
    }
}
