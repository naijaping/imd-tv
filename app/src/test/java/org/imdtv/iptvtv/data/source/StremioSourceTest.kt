package org.imdtv.iptvtv.data.source

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.imdtv.iptvtv.data.model.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StremioSourceTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()

    // Shapes as api.strem.io and Cinemeta returned them (2026-11-22), trimmed. Cinemeta pages by 50.
    private fun manifest(id: String, extra: String) = """{"id":"$id","name":"${id.uppercase()}","types":["movie","series"],"idPrefixes":["tt"],$extra}"""
    private val cinemeta = manifest("cinemeta", """"resources":["catalog","meta"],"catalogs":[
        {"type":"movie","id":"top","name":"Popular","extra":[{"name":"genre"},{"name":"skip"}]},
        {"type":"movie","id":"year","name":"New","extra":[{"name":"genre","isRequired":true}]},
        {"type":"series","id":"top","name":"Popular","extra":[]},
        {"type":"series","id":"last-videos","extraRequired":["lastVideosIds"]}]""")
    private val torrents = manifest("torrents", """"resources":[{"name":"stream","types":["movie"],"idPrefixes":["tt"]}],"catalogs":[]""")
    private val direct = manifest("direct", """"resources":["stream"],"catalogs":[]""")
    private fun addons() = """{"result":{"addons":[
        {"transportUrl":"${server.url("/cm/manifest.json")}","manifest":$cinemeta},
        {"transportUrl":"${server.url("/tor/manifest.json")}","manifest":$torrents},
        {"transportUrl":"${server.url("/dir/manifest.json")}","manifest":$direct}]}}"""
    private fun metas(from: Int, n: Int) = """{"metas":[""" + (from until from + n).joinToString(",") {
        """{"id":"tt$it","type":"movie","name":"Film $it","releaseInfo":"2024","imdbRating":"7.5","poster":"https://p/$it"}""" } + "]}"

    private val paths = mutableListOf<String>()
    private val puts = mutableListOf<String>()
    private fun auth() = StremioAuth(http, json, server.url("/").toString().trimEnd('/'), server.url("/link").toString())

    private fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                paths += p
                val body = when {
                    p == "/api/login" -> if ("\"password\":\"good\"" in request.body.readUtf8()) """{"result":{"authKey":"KEY"}}"""
                        else """{"error":{"code":3,"message":"Wrong passphrase","wrongPass":true}}"""
                    p == "/api/addonCollectionGet" -> addons()
                    // Library items as stremio-core stores them: in progress, finished (offset 0), removed, removed but temp.
                    p == "/api/datastorePut" -> { puts += request.body.readUtf8(); """{"result":{"success":true}}""" }
                    p == "/api/datastoreGet" && "\"all\":false" in request.body.peek().readUtf8() ->
                        if ("tt1" in request.body.readUtf8()) """{"result":[{"_id":"tt1","type":"movie","name":"Film","removed":false,"temp":false,"_mtime":"x","myField":7,
                            "state":{"timeOffset":600000,"duration":5400000,"timesWatched":2,"flaggedWatched":0,"timeWatched":5,"overallTimeWatched":9,"noNotif":false}}]}"""
                        else """{"result":[]}"""
                    p == "/api/datastoreGet" -> """{"result":[
                        {"_id":"tt1","type":"movie","name":"Film","removed":false,"temp":false,"state":{"timeOffset":600000,"duration":5400000,"lastWatched":"2026-11-20T21:00:00.000Z"}},
                        {"_id":"tt2","type":"movie","removed":false,"temp":false,"state":{"timeOffset":0,"duration":5400000}},
                        {"_id":"tt3","type":"movie","removed":true,"temp":false,"state":{"timeOffset":5000,"duration":5400000}},
                        {"_id":"tt9","type":"series","removed":true,"temp":true,"state":{"timeOffset":1200000,"duration":3400000,"video_id":"tt9:1:2","lastWatched":"2026-11-21T20:00:00.000Z"}}]}"""
                    p == "/cm/catalog/movie/top.json" -> metas(0, 50)
                    p == "/cm/catalog/movie/top/skip=50.json" -> metas(50, 3) // short: the last page
                    p == "/cm/catalog/series/top.json" -> """{"metas":[{"id":"tt9","type":"series","name":"Show","releaseInfo":"2011–2019"}]}"""
                    p == "/cm/meta/series/tt9.json" -> """{"meta":{"id":"tt9","name":"Show","runtime":"57 min","genres":["Drama"],"background":"https://b","videos":[
                        {"id":"tt9:1:2","name":"Two","season":1,"episode":2},{"id":"tt9:0:1","name":"Preview","season":0,"episode":1},{"id":"tt9:1:1","name":"One","season":1,"episode":1}]}}"""
                    p == "/tor/stream/movie/tt1.json" -> """{"streams":[{"name":"1080p","infoHash":"abc","fileIdx":0}]}"""
                    p == "/dir/stream/movie/tt1.json" -> """{"streams":[{"ytId":"x"},{"name":"HD","url":"https://cdn/film.mp4"}]}"""
                    p == "/dir/stream/series/tt9:1:1.json" -> """{"streams":[{"infoHash":"def"}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun progressKeepsTheLibraryItemAndAddsATempOneWhenNew() = runTest {
        start()
        val t = java.time.Instant.parse("2026-11-22T22:00:00Z").toEpochMilli()
        auth().saveProgress("KEY", ProgressReport(Kind.MOVIE, "tt1", "", 900_000, 5_400_000, false, "Film", null), t)
        auth().saveProgress("KEY", ProgressReport(Kind.SERIES, "tt9", "tt9:1:2", 0, 3_400_000, true, "Show", "https://p"), t)
        val (kept, made) = puts.map { json.parseToJsonElement(it).jsonObject["changes"]!!.jsonArray.single().jsonObject }
        assertEquals("7", kept["myField"].toString()); assertEquals("false", kept["temp"].toString()) // existing item: only state and _mtime change
        val ks = kept["state"]!!.jsonObject
        assertEquals("900000", ks["timeOffset"].toString()); assertEquals("2", ks["timesWatched"].toString()); assertEquals("5", ks["timeWatched"].toString())
        assertEquals("\"2026-11-22T22:00:00Z\"", kept["_mtime"].toString())
        assertEquals("true", made["temp"].toString()); assertEquals("true", made["removed"].toString()); assertEquals("\"series\"", made["type"].toString())
        val ms = made["state"]!!.jsonObject
        assertEquals("0", ms["timeOffset"].toString()); assertEquals("1", ms["flaggedWatched"].toString()); assertEquals("\"tt9:1:2\"", ms["video_id"].toString())
        assertTrue(made["behaviorHints"]!!.jsonObject.containsKey("hasScheduledVideos"))
    }

    @Test fun continueWatchingFollowsStremiosRule() = runTest {
        start()
        val r = auth().continueWatching("KEY")
        assertEquals(listOf(org.imdtv.iptvtv.data.source.ResumePoint(Kind.MOVIE, "tt1", "", 600_000, 5_400_000, java.time.Instant.parse("2026-11-20T21:00:00Z").toEpochMilli()),
            org.imdtv.iptvtv.data.source.ResumePoint(Kind.SERIES, "tt9", "tt9:1:2", 1_200_000, 3_400_000, java.time.Instant.parse("2026-11-21T20:00:00Z").toEpochMilli())), r)
    }

    @Test fun signInAndCatalogs() = runTest {
        start()
        val a = auth()
        assertEquals("KEY", a.withPassword("me@x", "good"))
        val wrong = runCatching { a.withPassword("me@x", "bad") }.exceptionOrNull() as StremioException
        assertEquals(3, wrong.code)
        // A catalog needing a genre or ids can't be listed; the others can.
        assertEquals(listOf("cinemeta/movie/top" to Kind.MOVIE, "cinemeta/series/top" to Kind.SERIES), a.catalogs(a.addons("KEY")).map { it.first to it.third })
    }

    private fun client() = org.imdtv.iptvtv.data.addons.AddonClient(http, null, json)

    // runBlocking: the stream add-ons' per-add-on timeout must run on the real clock, not runTest's virtual one.
    @Test fun syncDetailAndStreams() = kotlinx.coroutines.runBlocking {
        start()
        val src = StremioSource("KEY", emptySet(), auth(), client())
        val movies = src.movies()
        assertEquals(53, movies.size)
        assertEquals(Triple("Film 0", 2024, 7.5), movies[0].let { Triple(it.name, it.year, it.rating) })
        assertEquals("${STREMIO_PLAY}movie/tt0", movies[0].streamUrl)
        assertEquals(listOf("Show"), src.series().map { it.name })
        // Specials (season 0) left out, the rest in order.
        assertEquals(listOf("One", "Two"), src.seriesDetail("tt9").episodes.map { it.title })
        // The torrent add-on comes first but only the direct link plays; a torrent-only title has nothing.
        suspend fun pick(play: String) = org.imdtv.iptvtv.data.addons.autoPick(src.streams(play).last())
        assertEquals("https://cdn/film.mp4", (pick("${STREMIO_PLAY}movie/tt1") as org.imdtv.iptvtv.data.addons.AutoPick.Play).stream.playUrl)
        assertEquals(org.imdtv.iptvtv.data.addons.AutoPick.None, pick("${STREMIO_PLAY}series/tt9:1:1"))
        // Unticked catalogs aren't fetched.
        paths.clear()
        assertTrue(StremioSource("KEY", setOf("cinemeta/movie/top"), auth(), client()).movies().isEmpty())
        assertTrue(paths.none { "catalog" in it })
    }
}
