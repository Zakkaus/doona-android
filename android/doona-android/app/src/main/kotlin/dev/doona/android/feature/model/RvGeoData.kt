package dev.doona.android.feature.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * Where an update of the GeoIP and GeoSite data stands: not asked for, under way, done with newer data, done with
 * nothing newer, or failed. The APK ships one copy; an update runs only while the VPN is up.
 */
internal enum class RvGeoUpdate { Idle, Updating, Updated, Latest, Failed }

/** The data the rules read places and sites from: its release [date] (null when none is known), where its [update] stands, and whether the VPN is [connected]. */
@Immutable
internal data class RvGeoData(val date: LocalDate?, val update: RvGeoUpdate = RvGeoUpdate.Idle, val connected: Boolean = true)
