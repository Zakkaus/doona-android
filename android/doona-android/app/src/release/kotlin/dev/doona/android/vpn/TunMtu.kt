package dev.doona.android.vpn

import android.content.Context

/** A release build takes no override: the tun's MTU is always [StartConfigDto.MTU]. Debug builds: `src/debug`. */
internal fun Context.tunMtu(): Int = StartConfigDto.MTU
