package dev.doona.android.designsystem.components

import dev.doona.android.designsystem.rv.AppIcons as RvAppIcons
import dev.doona.android.designsystem.rv.Tone as RvTone

// Forwards for the pages still on the old library; P7 deletes this file with them.

@Deprecated("Moved to designsystem.rv.", ReplaceWith("Tone", "dev.doona.android.designsystem.rv.Tone"))
typealias Tone = RvTone

@Deprecated("Moved to designsystem.rv.", ReplaceWith("latencyTone(ms)", "dev.doona.android.designsystem.rv.latencyTone"))
fun latencyTone(ms: Int?): Tone = dev.doona.android.designsystem.rv.latencyTone(ms)

@Deprecated("Moved to designsystem.rv.", ReplaceWith("AppIcons", "dev.doona.android.designsystem.rv.AppIcons"))
internal typealias AppIcons = RvAppIcons
