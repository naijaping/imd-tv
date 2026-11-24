package org.imdtv.iptvtv.data.addons

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.source.StremioAddon
import org.imdtv.iptvtv.data.source.StremioCatalog
import org.imdtv.iptvtv.data.source.UiMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Why an add-on request failed. Never carries the URL (it may hold a personal token). */
class AddonException(val reason: Reason, val code: Int = 0) : IOException("addon: $reason${if (code != 0) " $code" else ""}"), UiMessage {
    enum class Reason { BAD_URL, UNREACHABLE, TIMEOUT, HTTP, NOT_ADDON, NEEDS_CONFIG }
    override val resId: Int get() = when (reason) {
        Reason.BAD_URL -> R.string.set_c_addon_bad_url
        Reason.UNREACHABLE -> R.string.set_c_addon_unreachable
        Reason.TIMEOUT -> R.string.set_c_addon_timeout
        Reason.HTTP -> R.string.set_c_addon_http
        Reason.NOT_ADDON -> R.string.set_c_addon_not_addon
        Reason.NEEDS_CONFIG -> R.string.set_c_addon_needs_config
    }
    override val args: List<Any> get() = if (reason == Reason.HTTP) listOf(code) else emptyList()
}

/** A subtitle file an add-on offers (subtitles resource, or a stream's own). */
data class AddonSubtitle(val url: String, val lang: String)

/** Why a stream can't play here: it needs a torrent engine, YouTube, a browser, or a source type the player lacks. */
enum class StreamBlock { TORRENT, YOUTUBE, EXTERNAL, OTHER }

/** One answer of a stream add-on. [playUrl]: the link, with its request headers appended ("url|Header=value"). */
data class AddonStream(val name: String, val description: String?, val playUrl: String?, val blocked: StreamBlock?, val subtitles: List<AddonSubtitle>)

/** One add-on's answer while streams load: LOADING until it replies, then DONE, FAILED or TIMEOUT. */
data class StreamGroup(val addon: String, val state: State, val streams: List<AddonStream> = emptyList()) {
    enum class State { LOADING, DONE, FAILED, TIMEOUT }
}

/** What "Play the first stream" plays: the best add-on's first playable stream once every add-on before it answered. */
sealed interface AutoPick {
    data object Wait : AutoPick
    data object None : AutoPick
    data class Play(val stream: AddonStream) : AutoPick
}

/** Add-on order is priority: an earlier add-on still loading is waited for; one that failed or timed out is passed over. */
fun autoPick(groups: List<StreamGroup>): AutoPick {
    for (g in groups) {
        if (g.state == StreamGroup.State.LOADING) return AutoPick.Wait
        g.streams.firstOrNull { it.playUrl != null }?.let { return AutoPick.Play(it) }
    }
    return AutoPick.None
}

/**
 * Talks to Stremio add-ons (catalog, meta, stream, subtitles). Responses go through a small disk cache that follows
 * the add-on's Cache-Control (Cinemeta: 4 hours); catalogs, details and manifests without one are kept 5 minutes,
 * streams not at all. Calls are cancelled with their coroutine (leaving a screen stops its requests).
 * Nothing here logs a URL.
 */
class AddonClient(base: OkHttpClient, cacheDir: File?, private val json: Json) {
    val http: OkHttpClient = base.newBuilder()
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "addons"), 20L * 1024 * 1024)) }
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val r = chain.proceed(req)
            val keep = req.tag(Keep::class.java)?.secs
            // Only for what we asked to keep, and only when the add-on says nothing itself.
            if (keep != null && r.isSuccessful && r.header("Cache-Control") == null && r.header("Expires") == null)
                r.newBuilder().header("Cache-Control", "public, max-age=$keep").build() else r
        }
        .build()

    /** GET [url] as JSON. [keepSecs]: cache it this long when the add-on gives no cache headers; [fresh]: skip the cache. */
    suspend fun get(url: String, keepSecs: Int? = 300, fresh: Boolean = false): JsonElement {
        val req = Request.Builder().url(url).header("Accept", "application/json")
            .apply { if (keepSecs != null) tag(Keep::class.java, Keep(keepSecs)); if (fresh) cacheControl(CacheControl.FORCE_NETWORK) }.build()
        val resp = try { http.newCall(req).await() } catch (e: CancellationException) { throw e } catch (e: IOException) { throw mapped(e) }
        return resp.use { r ->
            if (!r.isSuccessful) throw AddonException(AddonException.Reason.HTTP, r.code)
            withContext(Dispatchers.IO) {
                val text = try { r.body?.string().orEmpty() } catch (e: IOException) { throw mapped(e) }
                try { json.parseToJsonElement(text) } catch (e: Exception) { throw AddonException(AddonException.Reason.NOT_ADDON) }
            }
        }
    }

    /** Fetches and parses [manifestUrl] (fresh, not cached); throws [AddonException]. */
    suspend fun manifest(manifestUrl: String): Pair<StremioAddon, String> {
        val e = get(manifestUrl, keepSecs = null, fresh = true)
        val addon = try { StremioAddon.of(manifestUrl, e) } catch (x: IllegalArgumentException) { throw AddonException(AddonException.Reason.NOT_ADDON) }
        return addon to e.toString()
    }

    /** One page of [c]: from [skip], or the titles matching [search]. */
    suspend fun catalog(a: StremioAddon, c: StremioCatalog, skip: Int = 0, search: String? = null): List<JsonElement> =
        (get(a.url("catalog", c.type, c.id, AddonUrls.extra("search" to search, "skip" to skip.takeIf { it > 0 }?.toString()))) as? JsonObject)
            ?.get("metas").let { it as? JsonArray }.orEmpty()

    /** The details from the first of [addons] that serves meta for [type] / [id] and answers. */
    suspend fun meta(addons: List<StremioAddon>, type: String, id: String): JsonElement? =
        addons.filter { it.serves("meta", type, id) }.firstNotNullOfOrNull { a ->
            try { (get(a.url("meta", type, id)) as? JsonObject)?.get("meta")?.takeIf { it is JsonObject } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { warn("${a.name}: meta failed (${e.message})"); null }
        }

    /**
     * Asks every stream add-on at once; emits all groups (in add-on order) each time one answers, so the list fills in
     * as replies come. Each add-on gets [timeoutMs]: a slow one ends as TIMEOUT and doesn't hold the others back.
     */
    fun streams(addons: List<StremioAddon>, type: String, id: String, timeoutMs: Long = STREAM_TIMEOUT_MS): Flow<List<StreamGroup>> = channelFlow {
        val serving = addons.filter { it.serves("stream", type, id) }
        val groups = serving.map { StreamGroup(it.name, StreamGroup.State.LOADING) }.toMutableList()
        val lock = Mutex()
        send(groups.toList())
        serving.forEachIndexed { i, a ->
            launch {
                val g = try {
                    withTimeout(timeoutMs) { StreamGroup(a.name, StreamGroup.State.DONE, parseStreams((get(a.url("stream", type, id), keepSecs = null) as? JsonObject)?.get("streams") as? JsonArray)) }
                } catch (e: TimeoutCancellationException) { StreamGroup(a.name, StreamGroup.State.TIMEOUT) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { warn("${a.name}: streams failed (${e.message})"); StreamGroup(a.name, StreamGroup.State.FAILED) }
                lock.withLock { groups[i] = g; send(groups.toList()) }
            }
        }
    }

    /** Subtitles from every add-on with the subtitles resource, asked at once ([timeoutMs] each); failures give none. */
    suspend fun subtitles(addons: List<StremioAddon>, type: String, id: String, timeoutMs: Long = SUBTITLE_TIMEOUT_MS): List<AddonSubtitle> = coroutineScope {
        addons.filter { it.serves("subtitles", type, id) }.map { a ->
            async {
                withTimeoutOrNull(timeoutMs) {
                    try { parseSubtitles((get(a.url("subtitles", type, id)) as? JsonObject)?.get("subtitles") as? JsonArray) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { warn("${a.name}: subtitles failed (${e.message})"); null }
                }.orEmpty()
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    companion object {
        const val TAG = "IptvAddons"
        const val STREAM_TIMEOUT_MS = 12_000L
        const val SUBTITLE_TIMEOUT_MS = 8_000L

        private fun JsonElement?.s(k: String) = (this as? JsonObject)?.get(k)?.let { it as? JsonPrimitive }?.contentOrNull?.takeIf { it.isNotBlank() }

        fun parseSubtitles(a: JsonArray?): List<AddonSubtitle> = a.orEmpty().mapNotNull { e ->
            val url = e.s("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            AddonSubtitle(url, e.s("lang") ?: "und")
        }

        /** A stream's link and headers, or why it can't play here. */
        fun parseStreams(a: JsonArray?): List<AddonStream> = a.orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val url = o.s("url")
            val headers = ((o["behaviorHints"] as? JsonObject)?.get("proxyHeaders") as? JsonObject)?.get("request")?.let { it as? JsonObject }
                ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
            val blocked = when {
                url != null && (url.startsWith("http://") || url.startsWith("https://")) -> null
                o.s("infoHash") != null -> StreamBlock.TORRENT
                o.s("ytId") != null -> StreamBlock.YOUTUBE
                o.s("externalUrl") != null -> StreamBlock.EXTERNAL
                else -> StreamBlock.OTHER
            }
            val name = (o.s("name") ?: "").replace('\n', ' ').trim()
            val desc = (o.s("description") ?: o.s("title"))?.trim()
            AddonStream(name.ifEmpty { desc?.lineSequence()?.firstOrNull().orEmpty() }, desc,
                if (blocked == null) org.imdtv.iptvtv.data.source.withStreamHeaders(url!!, headers) else null, blocked,
                parseSubtitles(o["subtitles"] as? JsonArray))
        }

        private fun mapped(e: IOException): IOException = when (e) {
            is AddonException -> e
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> AddonException(AddonException.Reason.TIMEOUT)
            is java.net.UnknownHostException, is java.net.ConnectException, is javax.net.ssl.SSLException, is java.net.NoRouteToHostException ->
                AddonException(AddonException.Reason.UNREACHABLE)
            else -> AddonException(AddonException.Reason.UNREACHABLE)
        }
    }
}

/** Logs a warning (an add-on's name and what went wrong; never its URL). Never throws: not even off Android. */
internal fun warn(message: String) { runCatching { android.util.Log.w(AddonClient.TAG, message) } }

/** Request tag: keep the answer this long if the add-on sends no cache headers (a tag never leaves the device). */
private class Keep(val secs: Int)

/** Enqueues the call and resumes with its response; cancelling the coroutine cancels the request. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
