package org.imdtv.iptvtv.data.addons

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.imdtv.iptvtv.data.source.StremioAddon
import org.imdtv.iptvtv.data.source.xtreamJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Addons page's list: order, switches, catalogs, removal, kept across a restart (a new repository on the same file). */
class AddonRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val file by lazy { tmp.newFile("a.preferences_pb") }
    private val prefs by lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    private val json = xtreamJson()
    private val repo by lazy { AddonRepository(prefs, AddonClient(OkHttpClient(), null, json), json) }
    @After fun tearDown() = scope.cancel()

    private fun manifest(name: String) = javaClass.getResource("/addons/${name}_manifest.json")!!.readText()
    private fun addon(url: String, name: String) = StremioAddon.of(url, json.parseToJsonElement(manifest(name)))

    @Test fun installOrderSwitchesAndRemove() = runBlocking {
        val cine = addon("https://v3-cinemeta.strem.io/manifest.json", "cinemeta")
        val nuvio = addon("https://catalog.nuvio.tv/manifest.json", "nuvio")
        repo.install(cine, manifest("cinemeta")); repo.install(nuvio, manifest("nuvio"))
        assertEquals(listOf("Cinemeta", "Nuvio Catalog Addon"), repo.entries.first().map { it.full.name })

        repo.move(nuvio.manifestUrl, -1)
        assertEquals(listOf("Nuvio Catalog Addon", "Cinemeta"), repo.enabledNow().map { it.name })

        val popular = cine.catalogs.first { it.id == "top" && it.type == "movie" }
        repo.setCatalog(cine.manifestUrl, popular.key, false)
        assertFalse(repo.enabledNow().first { it.name == "Cinemeta" }.catalogs.any { it.key == popular.key })
        assertFalse(repo.enabledNow().first { it.name == "Cinemeta" }.searchCatalogs.any { it.key == popular.key }) // off for search too

        repo.setEnabled(nuvio.manifestUrl, false)
        assertEquals(listOf("Cinemeta"), repo.enabledNow().map { it.name })

        // Installing the same address again updates it in place.
        repo.install(cine, manifest("cinemeta"))
        assertEquals(2, repo.entries.first().size)

        repo.remove(cine.manifestUrl)
        assertEquals(listOf("Nuvio Catalog Addon"), repo.entries.first().map { it.full.name })
    }

    @Test fun survivesARestart() = runBlocking {
        val cine = addon("https://x.example/SECRET/manifest.json", "cinemeta")
        repo.install(cine, manifest("cinemeta"))
        repo.setEnabled(cine.manifestUrl, false)
        // DataStore allows one store per file: the first must have fully stopped (as when the app restarts).
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val again = AddonRepository(PreferenceDataStoreFactory.create(scope = scope2) { file }, AddonClient(OkHttpClient(), null, json), json)
            val e = again.entries.first().single()
            assertEquals(cine.manifestUrl, e.installed.url)
            assertFalse(e.installed.enabled)
            assertTrue(again.enabledNow().isEmpty())
        } finally { scope2.cancel() }
    }
}
