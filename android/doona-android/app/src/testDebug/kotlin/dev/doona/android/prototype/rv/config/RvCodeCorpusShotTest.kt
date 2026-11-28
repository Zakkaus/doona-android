package dev.doona.android.prototype.rv.config

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.doona.android.feature.config.RvConfigData
import dev.doona.android.feature.config.RvConfigSourcePage
import dev.doona.android.feature.config.RvSourceDraft
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The highlighting corpus (`hl-corpus.honk`: every honk construct, and the cases a highlighter gets wrong) in the source
 * editor, whole, light and dark (`proto-rv-config-source-corpus-*`): the one page that shows each token kind.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvCodeCorpusShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WHOLE)
    fun corpusLight() = compose.rvShot("config-source-corpus", dark = false, before = { assertWhole() }) { Corpus() }

    @Test
    @Config(qualifiers = WHOLE)
    fun corpusDark() = compose.rvShot("config-source-corpus", dark = true, before = { assertWhole() }) { Corpus() }

    @Composable
    private fun Corpus() {
        val draft = remember { RvSourceDraft(PATH, rvCodeCorpus(), RvConfigData.REV) }
        var value by remember { mutableStateOf(TextFieldValue(draft.text)) }
        RvConfigSourcePage(draft, value, { value = it }, onBack = {})
    }

    private companion object {
        const val PATH = "hl-corpus.dae"
        const val WHOLE = "+h2488dp"
    }
}

/** The highlighting corpus as written, without its final line feed. */
internal fun rvCodeCorpus(): String =
    requireNotNull(RvCodeCorpusShotTest::class.java.getResource("/dev/doona/android/prototype/rv/hl-corpus.honk")).readText().removeSuffix("\n")
