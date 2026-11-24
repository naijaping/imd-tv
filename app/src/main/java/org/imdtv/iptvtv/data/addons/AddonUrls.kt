package org.imdtv.iptvtv.data.addons

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Stremio add-on addresses. An add-on is its manifest URL; configured add-ons often carry a personal token in the path
 * (or the query), so the full URL is a secret: [mask] it on screen and never log it.
 * Resource URLs follow the protocol (`{base}/{resource}/{type}/{id}[/{extra}].json`) and, as Nuvio does, keep the
 * manifest's query string, where some add-ons keep their settings.
 */
object AddonUrls {
    /** Pasted text → the manifest URL; `stremio://` becomes https, a base address gets `/manifest.json`. Null if not usable. */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.startsWith("stremio://", ignoreCase = true)) s = "https://" + s.substring("stremio://".length)
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
        if (s.any { it.isWhitespace() }) return null
        val u = s.toHttpUrlOrNull()?.takeIf { it.host.isNotBlank() } ?: return null
        val path = u.encodedPath.trimEnd('/')
        return (if (path.endsWith("/manifest.json")) u else u.newBuilder().encodedPath("$path/manifest.json").build()).toString()
    }

    /** The add-on's base: the manifest URL without its query and `/manifest.json`. */
    fun base(manifestUrl: String) = manifestUrl.substringBefore('?').removeSuffix("/manifest.json").trimEnd('/')

    private fun query(manifestUrl: String) = manifestUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }

    /** `/catalog/movie/top/skip=50.json` etc. [extra] is already built with [extra]. */
    fun resource(manifestUrl: String, resource: String, type: String, id: String, extra: String? = null): String =
        base(manifestUrl) + "/" + resource + "/" + encode(type) + "/" + encode(id) + (if (extra.isNullOrEmpty()) "" else "/$extra") + ".json" + query(manifestUrl)

    /** The extra path segment, e.g. `search=the%20matrix&skip=20` (values encoded as encodeURIComponent does). */
    fun extra(vararg pairs: Pair<String, String?>): String? =
        pairs.filter { !it.second.isNullOrEmpty() }.joinToString("&") { (k, v) -> "$k=" + encode(v!!) }.ifEmpty { null }

    /** Percent-encodes everything but unreserved characters (RFC 3986), so ids like `tt123:1:2` stay one path segment. */
    fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') append(c)
            else append('%').append(HEX[(b.toInt() and 0xFF) shr 4]).append(HEX[b.toInt() and 0x0F])
        }
    }
    private const val HEX = "0123456789ABCDEF"

    /**
     * For the screen: scheme and host, then "…" for anything personal in between, then the file name.
     * `https://torrentio.strem.fun/…/manifest.json`; a plain `https://v3-cinemeta.strem.io/manifest.json` shows whole.
     */
    fun mask(manifestUrl: String): String {
        val u = manifestUrl.toHttpUrlOrNull() ?: return "…"
        val origin = u.scheme + "://" + u.host + (if (u.port != okhttp3.HttpUrl.defaultPort(u.scheme)) ":${u.port}" else "")
        val middle = u.pathSegments.dropLast(1).any { it.isNotEmpty() } || u.query != null
        return origin + (if (middle) "/…" else "") + "/" + (u.pathSegments.lastOrNull() ?: "")
    }

    /** The add-on's configuration page, for add-ons that must be set up before use. */
    fun configurePage(manifestUrl: String) = base(manifestUrl) + "/configure"
}
