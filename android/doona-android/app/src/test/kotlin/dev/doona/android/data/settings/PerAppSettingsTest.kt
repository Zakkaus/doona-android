package dev.doona.android.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class PerAppSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { folder.root.resolve("per_app.preferences_pb") }
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    private val settings by lazy { PerAppSettings(store) }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun defaultsToAll() = runBlocking {
        assertEquals(PerApp(), settings.perApp.first())
    }

    @Test
    fun aCorruptFileIsNotReadAsAll() {
        file.writeBytes(ByteArray(8) { -1 })
        assertThrows(IOException::class.java) { runBlocking { settings.perApp.first() } }
    }

    @Test
    fun anUnknownPolicyIsNotReadAsAll() {
        runBlocking { store.edit { it[stringPreferencesKey("policy")] = "Everything" } }
        assertThrows(IOException::class.java) { runBlocking { settings.perApp.first() } }
    }

    @Test
    fun updatesAreAtomic() = runBlocking {
        settings.update { PerApp(AppPolicy.OnlyListed) }
        val packages = (1..20).map { "app.$it" }
        packages.map { p -> async(Dispatchers.IO) { settings.update { it.copy(packages = it.packages + p) } } }.awaitAll()
        assertEquals(PerApp(AppPolicy.OnlyListed, packages.toSet()), settings.perApp.first())
    }
}
