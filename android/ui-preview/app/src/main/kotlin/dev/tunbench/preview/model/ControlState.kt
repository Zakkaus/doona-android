package dev.tunbench.preview.model

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tunbench.preview.R
import kotlin.math.floor
import kotlin.math.sin

// Sample data for a UI preview: no service, VPN or network code.

enum class Engine(@param:StringRes val label: Int) {
    Honk(R.string.engine_honk),
    Hev(R.string.engine_hev),
}

/** One engine's figures for bulk download over one stream. Null where no run has measured it. */
data class EngineFigures(
    val gbps: Double,
    val cpuPercent: Int?,
    val wakeupsPerSecond: Int?,
    val joulesPerGb: Double?,
    val connections: Int?,
)

fun figuresOf(engine: Engine): EngineFigures = when (engine) {
    Engine.Honk -> EngineFigures(gbps = 6.76, cpuPercent = 76, wakeupsPerSecond = 2_710, joulesPerGb = 2.48, connections = 2)
    Engine.Hev -> EngineFigures(gbps = 3.73, cpuPercent = 84, wakeupsPerSecond = 3_950, joulesPerGb = 2.21, connections = 2)
}

data class TunnelParameters(
    val upstream: String,
    val mtu: Int,
    val cpuCore: Int,
)

/** Seconds of history the live charts show. */
const val WINDOW_SECONDS = 60

/** Upper bound of the throughput chart's rate axis, in Gbit/s. */
const val CHART_MAX_GBPS = 8.0

class ControlState(engine: Engine, running: Boolean, uptimeSeconds: Long, ipv6: Boolean) {
    var engine by mutableStateOf(engine)
    var running by mutableStateOf(running)
        private set
    var uptimeSeconds by mutableLongStateOf(uptimeSeconds)
        private set
    var ipv6 by mutableStateOf(ipv6)
    val parameters = TunnelParameters(upstream = "127.0.0.1:1080", mtu = 8500, cpuCore = 7)

    val figures: EngineFigures get() = figuresOf(engine)

    /** The last [WINDOW_SECONDS] seconds of throughput in Gbit/s, oldest first; zero when stopped. */
    val series: List<Double>
        get() = if (running) wave(figures.gbps, phase = 0.0) else List(WINDOW_SECONDS) { 0.0 }

    /**
     * [level] with a slow drift of at most ±2 % (two long sine periods) and ±0.4 % noise. The
     * noise is a hash of the second, so a sample keeps its value while the window moves.
     */
    private fun wave(level: Double, phase: Double): List<Double> = List(WINDOW_SECONDS) { i ->
        val t = uptimeSeconds - WINDOW_SECONDS + 1 + i + phase
        val drift = 0.014 * sin(t / 23.0) + 0.006 * sin(t / 7.0)
        val hash = sin(t * 12.9898) * 43_758.5453
        val noise = 0.008 * (hash - floor(hash) - 0.5)
        level * (1 + drift + noise)
    }

    fun toggle() {
        running = !running
        if (running) uptimeSeconds = 0
    }

    fun tick() {
        if (running) uptimeSeconds++
    }

    companion object {
        /** Twelve and a half minutes into a honk run, IPv6 off. */
        fun sample() = ControlState(engine = Engine.Honk, running = true, uptimeSeconds = 754, ipv6 = false)
    }
}
