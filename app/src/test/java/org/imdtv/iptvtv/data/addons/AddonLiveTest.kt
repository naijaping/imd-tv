package org.imdtv.iptvtv.data.addons

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.source.StremioSource
import org.imdtv.iptvtv.data.source.xtreamJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against the real Cinemeta and Nuvio catalog add-ons. Off unless IMDTV_LIVE_ADDONS=1 (it needs the internet):
 * IMDTV_LIVE_ADDONS=1 ./gradlew :app:testDebugUnitTest --tests '*AddonLiveTest'
 */
class AddonLiveTest {
    private val json = xtreamJson()
    private val client = AddonClient(OkHttpClient(), null, json)
    private fun live() = assumeTrue("set IMDTV_LIVE_ADDONS=1", System.getenv("IMDTV_LIVE_ADDONS") == "1")

    @Test fun cinemetaAndNuvio() = runBlocking {
        live()
        val (cine, _) = client.manifest(AddonUrls.normalize("https://v3-cinemeta.strem.io/manifest.json")!!)
        val (nuvio, _) = client.manifest(AddonUrls.normalize("https://catalog.nuvio.tv")!!)
        // Rows page with skip.
        val popular = cine.catalogs.first { it.type == "movie" && it.id == "top" }
        val p1 = client.catalog(cine, popular); val p2 = client.catalog(cine, popular, skip = p1.size)
        println("Cinemeta Popular: ${p1.size} + ${p2.size}")
        assertTrue(p1.size >= 20 && p2.isNotEmpty())
        val trending = nuvio.catalogs.first { it.id == "trending-movies" }
        assertTrue(client.catalog(nuvio, trending).isNotEmpty())
        // Search across every search catalog of both.
        val hits = listOf(cine, nuvio).flatMap { a -> a.searchCatalogs.filter { it.kind == Kind.MOVIE }.flatMap { client.catalog(a, it, search = "the matrix") } }
        println("search 'the matrix': ${hits.size} results")
        assertTrue(hits.any { it.toString().contains("tt0133093") })
        // Details through the add-on list (Cinemeta first), a series' episodes, and streams: neither gives any.
        val src = StremioSource(null, emptySet(), client) { listOf(cine, nuvio) }
        val bb = src.seriesDetail("tt0903747")
        println("Breaking Bad: ${bb.episodes.size} episodes, seasons ${bb.episodes.map { it.season }.distinct()}")
        assertEquals(5, bb.episodes.map { it.season }.distinct().size)
        assertTrue(src.movieDetail("tt0133093").plot!!.isNotBlank())
        assertEquals(emptyList<StreamGroup>(), src.streams("stremio:movie/tt0133093").last())
    }
}
