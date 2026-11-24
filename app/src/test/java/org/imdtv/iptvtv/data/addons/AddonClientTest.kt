package org.imdtv.iptvtv.data.addons

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.source.StremioAddon
import org.imdtv.iptvtv.data.source.xtreamJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** Add-ons against a MockWebServer: real Cinemeta / Nuvio manifests, and stream answers shaped as the protocol describes. */
class AddonClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val json = xtreamJson()
    private val paths = mutableListOf<String>()
    private fun res(name: String) = javaClass.getResource("/addons/$name")!!.readText()
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.requestUrl!!.encodedPath + (request.requestUrl!!.encodedQuery?.let { "?$it" } ?: "")
                synchronized(paths) { paths += p }
                return when {
                    p == "/cine/manifest.json" -> ok(res("cinemeta_manifest.json"))
                    p == "/nuvio/manifest.json" -> ok(res("nuvio_manifest.json"))
                    p == "/html/manifest.json" -> MockResponse().setBody("<html>hi</html>")
                    p == "/gone/manifest.json" -> MockResponse().setResponseCode(404)
                    p == "/cfg/manifest.json" -> ok("""{"id":"x.cfg","name":"Needs setup","version":"1","resources":["stream"],"types":["movie"],"behaviorHints":{"configurable":true,"configurationRequired":true}}""")
                    p.startsWith("/cine/catalog/movie/top") -> ok(res("cinemeta_catalog.json"))
                    p.startsWith("/cine/meta/series/") -> ok(res("cinemeta_meta_series.json"))
                    p.startsWith("/fast/stream/") -> ok("""{"streams":[
                        {"name":"Fast\n1080p","description":"Movie.1080p.mkv\n💾 2 GB","url":"https://cdn.example/a.mkv","behaviorHints":{"notWebReady":true,"proxyHeaders":{"request":{"User-Agent":"Stremio","Referer":"https://x"}}},
                         "subtitles":[{"id":"1","url":"https://subs.example/en.srt","lang":"eng"}]},
                        {"name":"Torrent","infoHash":"abc","fileIdx":1},
                        {"name":"Trailer","ytId":"xyz"},
                        {"name":"Web","externalUrl":"https://netflix.com/x"}]}""")
                    p.startsWith("/slow/stream/") -> ok("""{"streams":[{"name":"Slow","url":"https://slow.example/a.mp4"}]}""").setBodyDelay(3, TimeUnit.SECONDS)
                    p.startsWith("/bad/stream/") -> MockResponse().setResponseCode(500)
                    p.startsWith("/fast/subtitles/") -> ok("""{"subtitles":[{"id":"s1","url":"https://subs.example/fr.srt","lang":"fre"},{"id":"bad","url":"ftp://x","lang":"x"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun tearDown() = server.shutdown()

    private fun url(p: String) = server.url(p).toString()
    private val client by lazy { AddonClient(OkHttpClient(), tmp.newFolder("cache"), json) }

    private fun addon(name: String, resources: String = """["stream"]""") = StremioAddon.of(url("/$name/manifest.json?token=SECRET"),
        json.parseToJsonElement("""{"id":"x.$name","name":"$name","resources":$resources,"types":["movie","series"],"idPrefixes":["tt"]}"""))

    @Test fun parsesRealManifests() = runBlocking {
        val (cine, _) = client.manifest(url("/cine/manifest.json"))
        assertEquals("Cinemeta", cine.name)
        // Rows: Popular and Featured for movies and series. New needs a year (a required genre) and Last videos /
        // Calendar need ids, so they aren't rows.
        assertEquals(listOf("movie/top", "series/top", "movie/imdbRating", "series/imdbRating"), cine.catalogs.map { "${it.type}/${it.id}" })
        assertTrue(cine.catalogs.all { it.kind != null && it.browse })
        assertEquals(listOf("movie/top", "series/top"), cine.searchCatalogs.map { "${it.type}/${it.id}" })
        assertTrue(cine.serves("meta", "movie", "tt0133093"))
        assertFalse(cine.hasResource("stream"))

        val (nuvio, _) = client.manifest(url("/nuvio/manifest.json"))
        assertEquals("Nuvio Catalog Addon", nuvio.name)
        assertTrue(nuvio.serves("meta", "movie", "tmdb:603")) // meta as an object resource with its own idPrefixes
        assertTrue(nuvio.serves("meta", "series", "tt0903747"))
        assertTrue(nuvio.configurable && !nuvio.configurationRequired)
        assertEquals(setOf("popular-movies", "popular-series"), nuvio.searchCatalogs.map { it.id }.toSet())
        assertTrue(nuvio.catalogs.first { it.id == "trending-movies" }.skip)
    }

    @Test fun badAddressesSayWhy() = runBlocking {
        suspend fun reason(u: String) = try { client.manifest(u); null } catch (e: AddonException) { e.reason }
        assertEquals(AddonException.Reason.NOT_ADDON, reason(url("/html/manifest.json")))
        assertEquals(AddonException.Reason.HTTP, reason(url("/gone/manifest.json")))
        assertEquals(AddonException.Reason.UNREACHABLE, reason("http://127.0.0.1:1/manifest.json"))
        // The message never holds the address.
        val e = try { client.manifest(url("/gone/manifest.json?token=SECRET")); null } catch (e: AddonException) { e }
        assertFalse(e!!.message!!.contains("SECRET") || e.message!!.contains("gone"))
    }

    @Test fun configurationRequiredIsRefused() = runBlocking {
        val repo = AddonRepository(androidx.datastore.preferences.core.PreferenceDataStoreFactory.create { tmp.newFile("a.preferences_pb") }, client, json)
        val r = try { repo.check(url("/cfg/manifest.json")); null } catch (e: AddonException) { e.reason }
        assertEquals(AddonException.Reason.NEEDS_CONFIG, r)
        assertEquals(AddonException.Reason.BAD_URL, try { repo.check("not a url"); null } catch (e: AddonException) { e.reason })
    }

    @Test fun catalogPagesAndSearchAreCached() = runBlocking {
        val (cine, _) = client.manifest(url("/cine/manifest.json"))
        val top = cine.catalogs.first { it.type == "movie" && it.id == "top" }
        assertEquals(3, client.catalog(cine, top).size)
        client.catalog(cine, top) // again: from the cache (5 minutes; the mock sends no cache headers)
        client.catalog(cine, top, skip = 50)
        client.catalog(cine, top, search = "the matrix")
        val asked = synchronized(paths) { paths.filter { it.startsWith("/cine/catalog") } }
        assertEquals(listOf("/cine/catalog/movie/top.json", "/cine/catalog/movie/top/skip=50.json", "/cine/catalog/movie/top/search=the%20matrix.json"), asked)
    }

    @Test fun metaFallsThroughToAnAddonThatServesIt() = runBlocking {
        val (cine, _) = client.manifest(url("/cine/manifest.json"))
        val noMeta = addon("fast")
        val m = client.meta(listOf(noMeta, cine), "series", "tt0903747")
        assertEquals("Breaking Bad", (m as kotlinx.serialization.json.JsonObject)["name"].toString().trim('"'))
    }

    @Test fun streamsArriveAsEachAddonAnswersInAddonOrder() = runBlocking {
        val addons = listOf(addon("slow"), addon("bad"), addon("fast"), addon("cine", """["meta"]"""))
        val seen = client.streams(addons, "movie", "tt0133093", timeoutMs = 1_000).toList()
        // First: all loading (the meta-only add-on isn't asked); then one change per add-on.
        assertEquals(listOf("slow", "bad", "fast"), seen.first().map { it.addon })
        assertTrue(seen.first().all { it.state == StreamGroup.State.LOADING })
        val last = seen.last()
        assertEquals(listOf(StreamGroup.State.TIMEOUT, StreamGroup.State.FAILED, StreamGroup.State.DONE), last.map { it.state })
        val fast = last[2].streams
        assertEquals(listOf(null, StreamBlock.TORRENT, StreamBlock.YOUTUBE, StreamBlock.EXTERNAL), fast.map { it.blocked })
        // Headers ride on the link, as the player reads them (url|Header=value); the stream's own subtitles come along.
        assertEquals("https://cdn.example/a.mkv|User-Agent=Stremio&Referer=https%3A%2F%2Fx", fast[0].playUrl)
        assertEquals("Fast 1080p", fast[0].name)
        assertEquals(listOf(AddonSubtitle("https://subs.example/en.srt", "eng")), fast[0].subtitles)
        // The request kept the manifest's query (the add-on's token) and encoded the id.
        assertTrue(synchronized(paths) { paths.any { it == "/fast/stream/movie/tt0133093.json?token=SECRET" } })
    }

    @Test fun autoPickWaitsForBetterAddons() {
        val play = AddonStream("a", null, "https://x/a.mp4", null, emptyList())
        val torrent = AddonStream("t", null, null, StreamBlock.TORRENT, emptyList())
        fun g(state: StreamGroup.State, vararg s: AddonStream) = StreamGroup("x", state, s.toList())
        assertEquals(AutoPick.Wait, autoPick(listOf(g(StreamGroup.State.LOADING), g(StreamGroup.State.DONE, play))))
        assertEquals(AutoPick.Play(play), autoPick(listOf(g(StreamGroup.State.TIMEOUT), g(StreamGroup.State.DONE, torrent, play))))
        assertEquals(AutoPick.None, autoPick(listOf(g(StreamGroup.State.FAILED), g(StreamGroup.State.DONE, torrent))))
    }

    @Test fun subtitlesFromSubtitleAddons() = runBlocking {
        val subs = client.subtitles(listOf(addon("fast", """["subtitles"]"""), addon("bad", """["stream"]""")), "movie", "tt0133093")
        assertEquals(listOf(AddonSubtitle("https://subs.example/fr.srt", "fre")), subs)
    }
}
