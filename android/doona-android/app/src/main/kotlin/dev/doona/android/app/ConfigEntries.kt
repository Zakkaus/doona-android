package dev.doona.android.app

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.doona.android.feature.config.ConfigHistoryRoute
import dev.doona.android.feature.config.ConfigOverviewRoute
import dev.doona.android.feature.config.ConfigRevisionRoute
import dev.doona.android.feature.config.ConfigRoutes
import dev.doona.android.feature.config.ConfigSkippedRoute
import dev.doona.android.feature.config.ConfigSourceRoute
import dev.doona.android.feature.config.ConfigTemplateRoute

/** The configuration overview, skipped files, source editor and revision history. */
internal fun EntryProviderScope<NavKey>.configEntries(backStack: MutableList<NavKey>) {
    val routes = ConfigRoutes(
        file = { backStack.push(ConfigSourceKey(it)) },
        skipped = { backStack.push(ConfigSkippedKey) },
        effective = { backStack.push(EffectiveConfigKey) },
        history = { backStack.push(ConfigHistoryKey) },
        revision = { backStack.push(ConfigRevisionKey(it)) },
        template = { backStack.push(ConfigTemplateKey) },
    )
    entry<ConfigKey> { key -> ConfigOverviewRoute(nativeApi(), onBack = { backStack.pop(key) }, routes) }
    entry<ConfigTemplateKey> { key -> ConfigTemplateRoute(nativeApi(), onBack = { backStack.pop(key) }) }
    entry<ConfigSkippedKey> { key -> ConfigSkippedRoute(nativeApi(), onBack = { backStack.pop(key) }, onOpen = routes.file) }
    entry<ConfigSourceKey> { key ->
        ConfigSourceRoute(nativeApi(), key.path, key.line, onBack = { backStack.pop(key) }, onApplied = { backStack.pop(key) }, onFile = { path ->
            val target = ConfigSourceKey(path)
            if (path != key.path) {
                backStack.remove(target)
                backStack.add(target)
            }
        })
    }
    entry<ConfigHistoryKey> { key -> ConfigHistoryRoute(nativeApi(), onBack = { backStack.pop(key) }, routes) }
    entry<ConfigRevisionKey> { key -> ConfigRevisionRoute(nativeApi(), key.rev, onBack = { backStack.pop(key) }, onRestored = { backStack.pop(key) }) }
}
