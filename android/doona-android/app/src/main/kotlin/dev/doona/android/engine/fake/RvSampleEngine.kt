package dev.doona.android.engine.fake

import dev.doona.android.engine.Engine
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status

/** The RV preview and benchmark dataset on the same in-memory engine used by JVM previews. */
class RvSampleEngine : Engine by FakeEngine(
    groups = RvSampleNodes.groups,
    subscriptions = RvSampleNodes.subscriptions,
    status = Status.Running(RvSampleTraffic.START),
    traffic = RvSampleTraffic.traffic,
    mode = Mode.Rule,
    data = FakeData(
        nodes = RvSampleNodes.details,
        usage = RvSampleTraffic.usage,
        today = RvSampleTraffic.today,
        source = RvSampleRules.source,
        rows = RvSampleRules.rows,
        configLines = RvSampleRules.configLines,
        series = RvSampleTraffic.series,
    ),
)
