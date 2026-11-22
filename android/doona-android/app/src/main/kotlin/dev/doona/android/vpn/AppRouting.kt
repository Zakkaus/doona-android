package dev.doona.android.vpn

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp

/** The apps a tun captures, resolved against the installed packages each time a tunnel is built. */
internal sealed interface AppRouting {
    /** Every app except these; doona is always among them. */
    data class Exclude(val packages: Set<String>) : AppRouting

    /** Only these apps; never doona, never empty. */
    data class Allow(val packages: Set<String>) : AppRouting

    companion object {
        /**
         * Null when [AppPolicy.OnlyListed] leaves no installed app: a tun with no allowed app captures every app,
         * doona included, so the caller refuses instead of falling back to [AppPolicy.All].
         */
        fun resolve(perApp: PerApp, self: String, installed: (Set<String>) -> Set<String>): AppRouting? =
            when (perApp.policy) {
                AppPolicy.All -> Exclude(setOf(self))
                AppPolicy.ExceptListed -> Exclude(installed(perApp.packages - self) + self)
                AppPolicy.OnlyListed -> installed(perApp.packages - self).takeIf { it.isNotEmpty() }?.let(::Allow)
            }
    }
}
