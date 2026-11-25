package dev.doona.android.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The RV gestures, measured as the perf rounds did by hand (plans/perf5/one.sh), [ITERATIONS] times each on the app's
 * profile build, its baseline profile compiled in: the process killed, the target activity started and left
 * [SETTLE_MS], then a trace of [LEAD_MS] at rest, the gesture, and [TAIL_MS] after it. The target is the app's
 * MainActivity (plan §3, P1b); the instrumentation argument `doona.target=prototype` measures the RV prototype instead,
 * on which the reference of the performance gate is recorded (plan §4), with the same gestures. The gestures act on
 * the RV 活動 page, which MainActivity shows once lane P3a wires it; before that a MainActivity run measures nothing
 * the gate compares. Each gesture reports
 * [RvDeadlineMissedMetric]'s App Deadline Missed count and [FrameTimingMetric]'s frame times. The profile build is the
 * package [PACKAGE], beside doona's, so installing it and the uninstall after the run leave doona's data alone.
 *
 * The gestures are the same `input` commands, in the pixels of the 1080 x 2410 phone the perf rounds used: [pull] pulls
 * 活動 down past the threshold, [reveal] further, [close] backs out of the open picker, [search] taps the search pill,
 * [node] taps the main node's row.
 *
 * All of them: `./gradlew -Pdoona.skipCore=true :benchmark:connectedProfileAndroidTest`; one of them: add
 * `-Pandroid.testInstrumentationRunnerArguments.class=dev.doona.android.benchmark.RvGestureBenchmark#pull`; the
 * prototype: add `-Pandroid.testInstrumentationRunnerArguments.doona.target=prototype`. Results
 * (min, median, max of each metric per gesture) and each iteration's Perfetto trace land in
 * `benchmark/build/outputs/connected_android_test_additional_output/profileAndroidTest/connected/<device>/`.
 */
@RunWith(AndroidJUnit4::class)
class RvGestureBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun pull() = measure { shell(PULL) }

    @Test
    fun reveal() = measure { shell(REVEAL) }

    @Test
    fun close() = measure(
        setup = {
            shell(REVEAL)
            Thread.sleep(OPEN_MS)
        },
    ) { shell(BACK) }

    @Test
    fun search() = measure { shell(SEARCH) }

    @Test
    fun node() = measure { shell(NODE) }

    @OptIn(ExperimentalMetricApi::class)
    private fun measure(setup: MacrobenchmarkScope.() -> Unit = {}, gesture: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(RvDeadlineMissedMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = ITERATIONS,
        setupBlock = {
            killProcess()
            startActivityAndWait(Intent().setComponent(ComponentName(PACKAGE, target)))
            Thread.sleep(SETTLE_MS)
            setup()
        },
    ) {
        Thread.sleep(LEAD_MS)
        gesture()
        Thread.sleep(TAIL_MS)
    }

    /** The activity measured: `doona.target=prototype` names the RV prototype; anything else, or none, MainActivity. */
    private val target = when (InstrumentationRegistry.getArguments().getString(TARGET_ARGUMENT)) {
        "prototype" -> PROTOTYPE_ACTIVITY
        else -> MAIN_ACTIVITY
    }

    /** Runs an `input` command on the phone; it returns once the gesture is over. */
    private fun MacrobenchmarkScope.shell(command: String) {
        device.executeShellCommand(command)
    }

    private companion object {
        const val PACKAGE = "dev.doona.android.profile"
        const val TARGET_ARGUMENT = "doona.target"
        const val MAIN_ACTIVITY = "dev.doona.android.app.MainActivity"
        const val PROTOTYPE_ACTIVITY = "dev.doona.android.prototype.rv.RvPreviewActivity"
        const val ITERATIONS = 10
        const val SETTLE_MS = 3_000L
        const val LEAD_MS = 1_500L
        const val TAIL_MS = 3_500L
        const val OPEN_MS = 2_500L
        const val PULL = "input swipe 540 500 540 900 600"
        const val REVEAL = "input swipe 540 500 540 1400 600"
        const val BACK = "input keyevent BACK"
        const val SEARCH = "input tap 540 260"
        const val NODE = "input tap 540 1650"
    }
}
