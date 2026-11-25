package dev.doona.android.designsystem.rv

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch

/**
 * Where the map is centred while it pans to [spot] after a node switch, over [RvMotion.MOVE_MS] the short way round
 * ([rvPanLon]); with no place to mark it pans back to where it rests, so neither way jumps.
 */
@Composable
internal fun rvMapPan(spot: RvSpot?): Pair<() -> Float, () -> Float> {
    val motion = Rv.motion
    val toLat = spot?.lat ?: RV_MAP_REST_LAT
    val toLon = spot?.lon ?: RV_MAP_REST_LON
    val lat = remember { Animatable(toLat) }
    val lon = remember { Animatable(toLon) }
    LaunchedEffect(toLat, toLon) {
        launch { lat.animateTo(toLat, motion.tween(RvMotion.MOVE_MS)) }
        lon.animateTo(rvPanLon(lon.value, toLon), motion.tween(RvMotion.MOVE_MS))
    }
    return { lat.value } to { rvWrapLon(lon.value) }
}

/**
 * The longitude a pan [from] runs to for [to]: [to] give or take whole turns, at most half a turn from [from], so it
 * goes the short way round, across the antimeridian when that is shorter (Asia to America over the Pacific).
 */
internal fun rvPanLon(from: Float, to: Float): Float {
    var at = to
    while (at - from > HALF_TURN) at -= FULL_TURN
    while (at - from < -HALF_TURN) at += FULL_TURN
    return at
}

/** [lon] as the map reads it, from 180 degrees west up to 180 east; a pan past the antimeridian leaves it beyond them. */
internal fun rvWrapLon(lon: Float): Float = if (lon >= -HALF_TURN && lon < HALF_TURN) lon else (lon + HALF_TURN).mod(FULL_TURN) - HALF_TURN

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
