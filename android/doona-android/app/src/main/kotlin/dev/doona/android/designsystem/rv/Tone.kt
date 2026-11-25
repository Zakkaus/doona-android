package dev.doona.android.designsystem.rv

enum class Tone { Ok, Warn, Err, Neutral }

/** Latency colours (`src/ui/Tile.tsx:79`): under 100 ms ok, under 300 ms warn, otherwise err. */
fun latencyTone(ms: Int?): Tone = when {
    ms == null -> Tone.Neutral
    ms < 100 -> Tone.Ok
    ms < 300 -> Tone.Warn
    else -> Tone.Err
}
