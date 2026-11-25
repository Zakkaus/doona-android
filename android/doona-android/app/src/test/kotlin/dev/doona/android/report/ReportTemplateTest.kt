package dev.doona.android.report

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The report names the rule template as the core does, whichever of its templates the user picked. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportTemplateTest : ExportFixture() {
    @Test
    fun `the report carries the core's template id`() = runTest {
        val fake = fixtureEngine()
        val shown = fake.catalog.value!!.let { it.copy(ruleSource = it.ruleSource.copy(template = "gfw")) }
        val engine = object : Engine by fake {
            override val catalog: StateFlow<Catalog?> = MutableStateFlow(shown)
        }
        val report = entries(export(engine).build()!!.file).getValue(ReportExport.REPORT_ENTRY)
        val source = Json.parseToJsonElement(report).jsonObject.getValue("settings").jsonObject.getValue("catalog").jsonObject.getValue("ruleSource").jsonObject
        assertEquals("gfw", source.getValue("template").jsonPrimitive.content)
    }
}
