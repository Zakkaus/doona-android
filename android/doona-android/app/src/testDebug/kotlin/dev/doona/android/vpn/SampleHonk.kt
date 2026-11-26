package dev.doona.android.vpn

/**
 * honk serving the approved sample's traffic (rvSampleEngine's, as the `-sample.json` files of `honk/runtime`), so the screenshot
 * tests draw the pages from honk's reads with the numbers their goldens were approved on. [running] false is honk not
 * running: `/runtime` answers 503, and the samples it kept stay readable. [failing] makes every read fail. The history
 * holds the sample's session peak 400 s ago, which only the ranges past 5 分 draw.
 */
internal fun sampleHonk(running: Boolean = true, failing: Boolean = false): HonkFake {
    val honk = HonkFake()
    val unavailable = HonkExamples.UNAVAILABLE
    if (running && !failing) {
        honk.get(HonkExamples.RUNTIME_PATH, HonkExamples.fixture("runtime/runtime-sample.json"))
    } else {
        honk.on("GET", HonkExamples.RUNTIME_PATH, UNAVAILABLE, unavailable)
    }
    if (failing) {
        honk.on("GET", HonkExamples.HISTORY_PATH, UNAVAILABLE, unavailable)
    } else {
        honk.get(HonkExamples.HISTORY_PATH, HonkExamples.fixture("runtime/traffic-history-sample.json"))
    }
    return honk
}

private const val UNAVAILABLE = 503
