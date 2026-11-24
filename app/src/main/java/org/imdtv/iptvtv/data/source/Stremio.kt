package org.imdtv.iptvtv.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.model.Category
import org.imdtv.iptvtv.data.model.Channel
import org.imdtv.iptvtv.data.model.Episode
import org.imdtv.iptvtv.data.model.Kind
import org.imdtv.iptvtv.data.model.Movie
import org.imdtv.iptvtv.data.model.MovieDetail
import org.imdtv.iptvtv.data.model.Series
import org.imdtv.iptvtv.data.model.SeriesDetail

/** [code] is the API's error code (3 = wrong password); [text] its message. */
class StremioException(val code: Int, val text: String) : java.io.IOException("Stremio: $code $text"), UiMessage {
    override val resId: Int? get() = if (code == 3) R.string.set_b_stremio_wrong_login else R.string.set_b_stremio_failed
    override val args: List<Any> get() = if (code == 3) emptyList() else listOf(text)
}

/** Saved instead of a stream link: the stream is looked up when it plays (add-ons answer per title). "stremio:movie/tt123". */
const val STREMIO_PLAY = "stremio:"

private fun JsonElement?.str(key: String) = (this as? JsonObject)?.get(key)?.let { it as? JsonPrimitive }?.contentOrNull
private fun JsonElement?.arr(key: String) = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

private suspend fun OkHttpClient.json(json: Json, r: Request): JsonElement = withContext(Dispatchers.IO) {
    newCall(r).execute().use { resp ->
        if (!resp.isSuccessful) throw StremioException(resp.code, "HTTP ${resp.code} ${r.url.encodedPath}")
        json.parseToJsonElement(resp.body!!.string())
    }
}

/** Sign-in: a link code (link.stremio.com, approved where the user is signed in) or email + password. */
class StremioAuth(private val http: OkHttpClient, private val json: Json, private val api: String = "https://api.strem.io", private val link: String = "https://link.stremio.com") {
    /** The result object, or throws the API's error. */
    private suspend fun call(r: Request): JsonElement {
        val e = http.json(json, r)
        (e as? JsonObject)?.get("error")?.takeIf { it is JsonObject }?.let { throw StremioException(it.jsonObject["code"]?.jsonPrimitive?.intOrNull ?: 0, it.str("message").orEmpty()) }
        return (e as? JsonObject)?.get("result") ?: e
    }
    private fun post(path: String, vararg kv: Pair<String, Any>) = Request.Builder().url("$api/api/$path")
        .post(JsonObject(kv.associate { (k, v) -> k to when (v) { is JsonElement -> v; is Boolean -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) } }).toString().toRequestBody("application/json".toMediaType())).build()

    /** A code to enter (or a QR to scan) at [StremioLink.url]. */
    suspend fun createLink(): StremioLink = call(Request.Builder().url("$link/api/v2/create?type=Create").build()).let { StremioLink(it.str("code").orEmpty(), it.str("link").orEmpty()) }

    // shortcut: the read reply before approval wasn't seen (it errors "Invalid or expired token"), so any error means "not yet"; the caller makes a new code after a while.
    /** The account's key once [code] was approved; null while waiting. */
    suspend fun readLink(code: String): String? =
        runCatching { call(Request.Builder().url("$link/api/v2/read?type=Read&code=$code").build()).str("authKey") }.getOrNull()

    suspend fun withPassword(email: String, password: String): String =
        call(post("login", "type" to "Login", "email" to email, "password" to password, "facebook" to false)).str("authKey")
            ?: throw StremioException(0, "no authKey")

    /** The add-ons installed on the account, in its order (each one's address is all it needs, settings included). */
    suspend fun addons(authKey: String): List<StremioAddon> =
        call(post("addonCollectionGet", "type" to "AddonCollectionGet", "authKey" to authKey, "update" to true)).arr("addons").mapNotNull { a ->
            val url = a.str("transportUrl") ?: return@mapNotNull null
            runCatching { StremioAddon.of(url, (a as JsonObject)["manifest"] ?: return@mapNotNull null) }.getOrNull()
        }

    /**
     * The account's Continue watching, from its library: as Stremio's own rule, an item with a position that's still in the
     * library or only watched (temp). A series' position is on its last video ("tt…:season:episode").
     */
    suspend fun continueWatching(authKey: String): List<ResumePoint> =
        call(post("datastoreGet", "type" to "DatastoreGet", "authKey" to authKey, "collection" to "libraryItem", "ids" to JsonArray(emptyList()), "all" to true))
            .let { it as? JsonArray ?: JsonArray(emptyList()) }.mapNotNull { i ->
                val o = i as? JsonObject ?: return@mapNotNull null
                val st = o["state"] as? JsonObject ?: return@mapNotNull null
                val off = st["timeOffset"]?.jsonPrimitive?.longOrNull ?: 0
                val removed = o["removed"]?.jsonPrimitive?.booleanOrNull == true
                if (off <= 0 || (removed && o["temp"]?.jsonPrimitive?.booleanOrNull != true)) return@mapNotNull null
                val id = o.str("_id") ?: return@mapNotNull null
                val at = st.str("lastWatched")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
                val dur = st["duration"]?.jsonPrimitive?.longOrNull ?: 0
                when (o.str("type")) {
                    "movie" -> ResumePoint(Kind.MOVIE, id, "", off, dur, at)
                    "series" -> st.str("video_id")?.let { ResumePoint(Kind.SERIES, id, it, off, dur, at) }
                    else -> null
                }
            }

    /**
     * Saves where [p] was left in the account's library, as Stremio's apps do: the existing item with its state updated, or
     * a new temp item (watched, not added to the library) with every field Stremio's apps expect.
     */
    suspend fun saveProgress(authKey: String, p: ProgressReport, now: Long = System.currentTimeMillis()) {
        val old = call(post("datastoreGet", "type" to "DatastoreGet", "authKey" to authKey, "collection" to "libraryItem",
            "ids" to JsonArray(listOf(JsonPrimitive(p.remoteId))), "all" to false)).let { (it as? JsonArray)?.firstOrNull() as? JsonObject }
        val iso = java.time.Instant.ofEpochMilli(now).toString()
        val was = old?.get("state") as? JsonObject
        fun n(k: String) = was?.get(k)?.jsonPrimitive?.longOrNull ?: 0
        val state = JsonObject((was ?: JsonObject(mapOf("timeWatched" to JsonPrimitive(0), "overallTimeWatched" to JsonPrimitive(0),
            "watched" to kotlinx.serialization.json.JsonNull, "noNotif" to JsonPrimitive(false)))) + mapOf(
            "lastWatched" to JsonPrimitive(iso),
            "timeOffset" to JsonPrimitive(if (p.watched) 0 else p.positionMs),
            "duration" to JsonPrimitive(p.durationMs.takeIf { it > 0 } ?: n("duration")),
            "video_id" to JsonPrimitive(p.episode.ifEmpty { p.remoteId }),
            "timesWatched" to JsonPrimitive(n("timesWatched") + if (p.watched) 1 else 0),
            "flaggedWatched" to JsonPrimitive(if (p.watched) 1 else if (p.positionMs == 0L) 0 else n("flaggedWatched")),
        ))
        val item = JsonObject((old ?: JsonObject(mapOf(
            "_id" to JsonPrimitive(p.remoteId), "name" to JsonPrimitive(p.title), "type" to JsonPrimitive(if (p.kind == Kind.MOVIE) "movie" else "series"),
            "poster" to (p.poster?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull), "posterShape" to JsonPrimitive("poster"),
            "removed" to JsonPrimitive(true), "temp" to JsonPrimitive(true), "_ctime" to JsonPrimitive(iso),
            "behaviorHints" to JsonObject(mapOf("defaultVideoId" to kotlinx.serialization.json.JsonNull, "featuredVideoId" to kotlinx.serialization.json.JsonNull,
                "hasScheduledVideos" to JsonPrimitive(false))),
        ))) + mapOf("_mtime" to JsonPrimitive(iso), "state" to state))
        call(post("datastorePut", "type" to "DatastorePut", "authKey" to authKey, "collection" to "libraryItem", "changes" to JsonArray(listOf(item))))
    }

    /** Movie and series catalogs as (key, "Catalog · Add-on", kind): one needing a search or genre to answer is left out. */
    fun catalogs(addons: List<StremioAddon>): List<Triple<String, String, Kind>> = addons.flatMap { a -> a.catalogs.map { Triple(it.key, "${it.name} · ${a.name}", it.kind!!) } }
}

data class StremioLink(val code: String, val url: String)

/** An add-on title as a poster: its id (an IMDb "tt…" or the add-on's own), name, poster, year and rating. */
data class AddonCard(val id: String, val name: String, val poster: String?, val year: Int?, val rating: Double?)

/**
 * One catalog of an add-on. [kind] null: a type the app doesn't list (tv, channel…). [browse]: it answers with no extra
 * (a row can show it); [search]: it takes `search=`; [skip]: it pages with `skip=`.
 */
data class StremioCatalog(val key: String, val type: String, val id: String, val name: String, val kind: Kind?,
                          val search: Boolean = false, val skip: Boolean = false, val browse: Boolean = true)

/** One add-on: where it lives, what it lists and whether it answers streams / details for a type and id. */
data class StremioAddon(
    /** The manifest URL, query included: it may hold a personal token (AddonUrls.mask it, never log it). */
    val manifestUrl: String, val id: String, val name: String,
    /** Movie and series catalogs that list without a search or genre: the rows and categories. */
    val catalogs: List<StremioCatalog>,
    private val resources: List<Triple<String, Set<String>?, List<String>?>>,
    val version: String = "", val description: String? = null, val logo: String? = null, val types: List<String> = emptyList(),
    /** Every catalog, any type (the Addons page lists them all). */
    val allCatalogs: List<StremioCatalog> = catalogs,
    val configurable: Boolean = false, val configurationRequired: Boolean = false,
) {
    val base get() = org.imdtv.iptvtv.data.addons.AddonUrls.base(manifestUrl)
    /** `{base}/{resource}/{type}/{id}[/{extra}].json`, with the manifest's query kept. */
    fun url(resource: String, type: String, id: String, extra: String? = null) =
        org.imdtv.iptvtv.data.addons.AddonUrls.resource(manifestUrl, resource, type, id, extra)
    /** Movie / series catalogs that take a search. */
    val searchCatalogs get() = allCatalogs.filter { it.search && it.kind != null }
    fun hasResource(name: String) = resources.any { it.first == name }
    val resourceNames get() = resources.map { it.first }.distinct()

    /** Answers [resource] ("stream", "meta") for [type] and [id] (its idPrefixes, when it lists any). */
    fun serves(resource: String, type: String, id: String) = resources.any { (name, types, prefixes) ->
        name == resource && (types == null || type in types) && (prefixes == null || prefixes.any { id.startsWith(it) })
    }

    /** Without the catalogs switched off on the Addons page (keys). */
    fun without(off: Set<String>) = if (off.isEmpty()) this else copy(catalogs = catalogs.filter { it.key !in off }, allCatalogs = allCatalogs.filter { it.key !in off })

    companion object {
        private fun JsonElement?.strings(key: String) = arr(key).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        /** Parses a manifest; throws [IllegalArgumentException] when it isn't one (no id, name or resources). */
        fun of(transportUrl: String, m: JsonElement): StremioAddon {
            val types = m.strings("types").toSet()
            val prefixes = m.strings("idPrefixes").ifEmpty { null }
            // A resource is "stream", or {name, types, idPrefixes} narrowing the manifest's own.
            val res = m.arr("resources").mapNotNull { r ->
                if (r is JsonPrimitive) Triple(r.content, types, prefixes)
                else r.str("name")?.let { n -> Triple(n, r.strings("types").toSet().ifEmpty { types }, r.strings("idPrefixes").ifEmpty { prefixes }) }
            }
            val id = m.str("id").orEmpty()
            val name = m.str("name").orEmpty()
            require(id.isNotBlank() && name.isNotBlank() && (res.isNotEmpty() || m.arr("catalogs").isNotEmpty())) { "not a manifest" }
            val all = m.arr("catalogs").mapNotNull { c ->
                val type = c.str("type") ?: return@mapNotNull null
                val cid = c.str("id") ?: return@mapNotNull null
                val kind = when (type) { "movie" -> Kind.MOVIE; "series" -> Kind.SERIES; else -> null }
                // extra: [{name, isRequired}]; older manifests: extraSupported / extraRequired name lists.
                val extras = c.arr("extra").mapNotNull { e -> e.str("name")?.let { it to ((e as JsonObject)["isRequired"]?.jsonPrimitive?.booleanOrNull == true || e.str("isRequired") == "true") } } +
                    c.strings("extraSupported").map { it to false } + c.strings("extraRequired").map { it to true }
                val required = extras.filter { it.second }.map { it.first }.toSet()
                StremioCatalog("$id/$type/$cid", type, cid, c.str("name") ?: cid, kind,
                    search = extras.any { it.first == "search" } && required.all { it == "search" },
                    skip = extras.any { it.first == "skip" }, browse = required.isEmpty())
            }
            val hints = (m as? JsonObject)?.get("behaviorHints") as? JsonObject
            fun hint(k: String) = hints?.get(k)?.let { it as? JsonPrimitive }?.booleanOrNull == true
            return StremioAddon(transportUrl, id, name, all.filter { it.kind != null && it.browse }, res,
                version = m.str("version").orEmpty(), description = m.str("description"), logo = m.str("logo"),
                types = types.toList(), allCatalogs = all, configurable = hint("configurable"), configurationRequired = hint("configurationRequired"))
        }
    }
}

/**
 * Stremio add-ons as VOD: a Stremio account's (a media-server playlist: catalogs synced as categories, Continue watching
 * both ways) or the add-ons installed on the Addons page ([addons] given, no account). Links are [STREMIO_PLAY] ids;
 * the streams are asked for at play time (AddonClient.streams), so a torrent-only answer shows as unplayable (no
 * torrent engine, a known limitation).
 */
class StremioSource(
    private val account: Pair<String, StremioAuth>?, private val skip: Set<String>, private val client: org.imdtv.iptvtv.data.addons.AddonClient,
    /** Where the add-ons come from: the account's collection (cached for this source) or the Addons page. */
    private val addonList: suspend () -> List<StremioAddon> = { account!!.second.addons(account.first) },
) : PlaylistSource {
    /** A Stremio account playlist. */
    constructor(authKey: String, skip: Set<String>, auth: StremioAuth, client: org.imdtv.iptvtv.data.addons.AddonClient) : this(authKey to auth, skip, client)

    private var addonCache: List<StremioAddon>? = null
    /** The add-ons, in priority order. */
    suspend fun addons() = if (account == null) addonList() else addonCache ?: addonList().also { addonCache = it }
    private suspend fun catalogs(kind: Kind) = addons().flatMap { a -> a.catalogs.filter { it.kind == kind && it.key !in skip }.map { a to it } }

    /** Every page of a catalog, up to [MAX] titles (Cinemeta's Popular never ends). */
    private suspend fun items(a: StremioAddon, c: StremioCatalog, fetched: (Int, Int?) -> Unit = { _, _ -> }): List<JsonElement> {
        val out = ArrayList<JsonElement>()
        while (out.size < MAX) {
            val page = client.catalog(a, c, skip = out.size)
            if (page.isEmpty()) break
            out += page
            fetched(minOf(out.size, MAX), null)
            if (page.size < 10 || !c.skip && out.size > page.size) break // a short page is the last; no skip = one page
        }
        return out.take(MAX)
    }

    private fun year(m: JsonElement?) = (m.str("releaseInfo") ?: m.str("year"))?.take(4)?.toIntOrNull()
    private fun rating(m: JsonElement?) = (m as? JsonObject)?.get("imdbRating")?.let { (it as? JsonPrimitive)?.doubleOrNull ?: it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
    fun movie(m: JsonElement, cat: String?) = Movie(m.str("id")!!, cat, m.str("name").orEmpty(), m.str("poster"), year(m), rating(m), "${STREMIO_PLAY}movie/${m.str("id")}")
    fun series(m: JsonElement, cat: String?) = Series(m.str("id")!!, cat, m.str("name").orEmpty(), m.str("poster"), year(m), rating(m))

    /** The same title in several catalogs is kept once, in the first (a title has one category). */
    private suspend fun <T> list(kind: Kind, make: (JsonElement, String) -> T): List<T> = coroutineScope {
        catalogs(kind).map { (a, c) -> async { c.key to runCatching { items(a, c) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("Stremio", "${a.name} ${c.name} failed: ${it.message}"); emptyList() } } }.awaitAll()
            .flatMap { (key, metas) -> metas.filter { it.str("id") != null }.map { it.str("id")!! to make(it, key) } }
            .distinctBy { it.first }.map { it.second }
    }

    override suspend fun liveCategories(): List<Category> = emptyList()
    override suspend fun liveChannels(): List<Channel> = emptyList()
    override fun epgUrls(): List<String> = emptyList()
    override suspend fun vodCategories() = catalogs(Kind.MOVIE).map { (a, c) -> Category(c.key, "${c.name} · ${a.name}", Kind.MOVIE) }
    override suspend fun seriesCategories() = catalogs(Kind.SERIES).map { (a, c) -> Category(c.key, "${c.name} · ${a.name}", Kind.SERIES) }
    override suspend fun movies() = list(Kind.MOVIE) { m, cat -> movie(m, cat) }
    override suspend fun series() = list(Kind.SERIES) { m, cat -> series(m, cat) }
    // Catalogs are rankings (Popular, New…) with no "changed since": always the whole catalog (up to MAX).
    override suspend fun moviesIn(cat: String, since: Long?, fetched: (Int, Int?) -> Unit) = catalog(Kind.MOVIE, cat, fetched) { m, c -> movie(m, c) }
    override suspend fun seriesIn(cat: String, since: Long?, fetched: (Int, Int?) -> Unit) = catalog(Kind.SERIES, cat, fetched) { m, c -> series(m, c) }
    private suspend fun <T> catalog(kind: Kind, cat: String, fetched: (Int, Int?) -> Unit, make: (JsonElement, String) -> T): LibraryPage<T> {
        val (a, c) = catalogs(kind).first { it.second.key == cat }
        val metas = runCatching { items(a, c, fetched) }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("Stremio", "${a.name} ${c.name} failed: ${it.message}"); emptyList() }
        return metas.filter { it.str("id") != null }.map { make(it, cat) }.let { LibraryPage(it, it.size, changesOnly = false) }
    }

    private suspend fun meta(type: String, id: String): JsonElement? = client.meta(addons(), type, id)

    private fun List<JsonElement>.names() = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString().ifEmpty { null }

    override suspend fun movieDetail(id: String): MovieDetail {
        val m = meta("movie", id) ?: throw StremioException(404, "no details")
        val mins = m.str("runtime")?.takeWhile { it.isDigit() }?.toIntOrNull()
        return MovieDetail(movie(m, null), m.str("background"), m.str("description"), m.arr("genres").names(), m.arr("director").names(),
            m.arr("cast").take(6).names(), mins?.times(60))
    }

    override suspend fun seriesDetail(id: String): SeriesDetail {
        val m = meta("series", id) ?: throw StremioException(404, "no details")
        val eps = m.arr("videos").mapNotNull { v ->
            val vid = v.str("id") ?: return@mapNotNull null
            val season = (v as JsonObject)["season"]?.jsonPrimitive?.intOrNull ?: 0
            val number = (v["episode"] ?: v["number"])?.jsonPrimitive?.intOrNull ?: 0
            Episode(vid, season, number, v.str("name") ?: v.str("title").orEmpty(), v.str("overview") ?: v.str("description"), v.str("thumbnail"), null, "${STREMIO_PLAY}series/$vid")
        }.filter { it.season > 0 }.sortedWith(compareBy({ it.season }, { it.number })) // season 0: specials and trailers
        return SeriesDetail(series(m, null), m.str("background"), m.str("description"), m.arr("genres").names(), eps)
    }

    /** A catalog or search entry as a poster card; null without an id or a name. */
    fun card(m: JsonElement): AddonCard? {
        val id = m.str("id") ?: return null
        val name = m.str("name") ?: return null
        return AddonCard(id, name, m.str("poster"), year(m), rating(m))
    }

    /** "stremio:movie/tt123" → ("movie", "tt123"); null for another link. */
    fun target(play: String): Pair<String, String>? =
        play.takeIf { it.startsWith(STREMIO_PLAY) }?.removePrefix(STREMIO_PLAY)?.split('/', limit = 2)?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    /** Every stream add-on's answers for [play], as they arrive (see AddonClient.streams). */
    suspend fun streams(play: String): kotlinx.coroutines.flow.Flow<List<org.imdtv.iptvtv.data.addons.StreamGroup>> {
        val (type, id) = target(play) ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return client.streams(addons(), type, id)
    }

    /** Subtitles from the subtitle add-ons for [play]. */
    suspend fun subtitles(play: String): List<org.imdtv.iptvtv.data.addons.AddonSubtitle> {
        val (type, id) = target(play) ?: return emptyList()
        return client.subtitles(addons(), type, id)
    }

    // shortcut: only titles in the chosen catalogs can show (the rest have no row here); add them to a category if people ask.
    override suspend fun resumePoints() = account?.let { (key, auth) -> auth.continueWatching(key) }.orEmpty()
    override suspend fun reportProgress(p: ProgressReport) { account?.let { (key, auth) -> auth.saveProgress(key, p) } }

    companion object { const val MAX = 500 }
}
