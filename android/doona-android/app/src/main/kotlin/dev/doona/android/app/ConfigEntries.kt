package dev.doona.android.app

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.doona.android.feature.config.ConfigHistoryRoute
import dev.doona.android.feature.config.ConfigOverviewRoute
import dev.doona.android.feature.config.ConfigRevisionRoute
import dev.doona.android.feature.config.ConfigRoutes
import dev.doona.android.feature.config.ConfigSkippedRoute
import dev.doona.android.feature.config.ConfigSourceRoute

/**
 * The configuration pages: 設定檔, 已略過, 原始碼 with its diff, 版本紀錄 and a revision. The export's stream waits
 * for the picker bridge (config-plan.md slice 13); 設定檔's 匯入 opens the system picker itself.
 */
internal fun EntryProviderScope<NavKey>.configEntries(backStack: MutableList<NavKey>) {
    val routes = ConfigRoutes(
        file = { backStack.push(ConfigSourceKey(it)) },
        skipped = { backStack.push(ConfigSkippedKey) },
        effective = { backStack.push(EffectiveConfigKey) },
        history = { backStack.push(ConfigHistoryKey) },
        revision = { backStack.push(ConfigRevisionKey(it)) },
    )
    entry<ConfigKey> { key -> ConfigOverviewRoute(nativeApi(), onBack = { backStack.pop(key) }, routes) }
    entry<ConfigSkippedKey> { key -> ConfigSkippedRoute(nativeApi(), onBack = { backStack.pop(key) }, onOpen = routes.file) }
    entry<ConfigSourceKey> { key -> ConfigSourceRoute(nativeApi(), key.path, key.line, onBack = { backStack.pop(key) }, onApplied = { backStack.pop(key) }) }
    entry<ConfigHistoryKey> { key -> ConfigHistoryRoute(nativeApi(), onBack = { backStack.pop(key) }, routes) }
    entry<ConfigRevisionKey> { key -> ConfigRevisionRoute(nativeApi(), key.rev, onBack = { backStack.pop(key) }, onRestored = { backStack.pop(key) }) }
}
