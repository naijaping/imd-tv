package org.imdtv.iptvtv.data.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.security.Login

/** Why Dispatcharr sign-in failed; [resId] is what the playlist form shows. */
class DispatcharrException(message: String, @androidx.annotation.StringRes override val resId: Int, override val args: List<Any> = emptyList()) :
    java.io.IOException(message), UiMessage

/**
 * Dispatcharr (github.com/Dispatcharr/Dispatcharr) sign-in. Its Xtream Codes API (player_api.php, xmltv.php,
 * /live/user/pass/id) takes the user's *XC password*, a separate password an admin sets on the user, not the login
 * password. So the login (password via `POST /api/accounts/token/`, or an API key in `X-API-Key`) is used once, to
 * read that XC password from `GET /api/accounts/users/me/`; the playlist then runs as Xtream (XtreamSource) with
 * username + XC password. No token is kept: the JWT (30 min) is only needed for this one call, and the XC API
 * needs none, so there is nothing to refresh. If an admin changes the XC password, Edit playlist signs in again.
 */
class DispatcharrAuth(private val http: OkHttpClient, private val json: Json) {

    /** The server's address as typed, without a trailing slash or a pasted page path (".../channels", "/api/..."). */
    fun base(url: String): String? {
        val u = url.trim().trimEnd('/').toHttpUrlOrNull() ?: return null
        // Keep a sub-path install (https://host/dispatcharr) but drop the web UI page or API path someone pasted.
        val keep = u.pathSegments.takeWhile { it.isNotEmpty() && it != "api" && it !in UI_PAGES }
        return u.newBuilder().encodedPath("/").apply { keep.forEach { addPathSegment(it) } }.query(null).fragment(null).build().toString().trimEnd('/')
    }

    /**
     * The Xtream login for [server]: username and XC password. [secret] is the Dispatcharr password, or an API key
     * (tried when the password sign-in is refused, or straight away when [username] is blank).
     */
    suspend fun xcLogin(server: String, username: String, secret: String): Login = withContext(Dispatchers.IO) {
        val base = base(server) ?: throw DispatcharrException("bad address", R.string.set_b_enter_full_url)
        if (secret.isBlank()) throw DispatcharrException("no password", R.string.set_b_dp_enter_login)
        val me = if (username.isBlank()) me(base, apiKey = secret.trim())
        else {
            val access = try { token(base, username.trim(), secret) } catch (e: DispatcharrException) {
                // A refused password may be an API key typed in the same box (they're 54 characters, no spaces).
                if (e.resId != R.string.set_b_dp_wrong_login || !looksLikeApiKey(secret)) throw e
                null
            }
            if (access != null) me(base, bearer = access) else me(base, apiKey = secret.trim())
        }
        val user = me["username"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: username.trim()
        val xc = (me["custom_properties"] as? JsonObject)?.get("xc_password")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw DispatcharrException("no XC password", R.string.set_b_dp_no_xc_password, listOf(user))
        Login(user, xc)
    }

    /** JWT access token for username + password. */
    private fun token(base: String, username: String, password: String): String {
        val body = JsonObject(mapOf("username" to JsonPrimitive(username), "password" to JsonPrimitive(password))).toString()
            .toRequestBody("application/json".toMediaType())
        return call(Request.Builder().url("$base/api/accounts/token/").post(body)) { o ->
            o["access"]?.jsonPrimitive?.contentOrNull ?: throw notDispatcharr("no access token")
        }
    }

    /** The signed-in user: username and custom_properties (with xc_password). */
    private fun me(base: String, bearer: String? = null, apiKey: String? = null): JsonObject =
        call(Request.Builder().url("$base/api/accounts/users/me/").apply {
            if (bearer != null) header("Authorization", "Bearer $bearer")
            if (apiKey != null) header("X-API-Key", apiKey)
        }) { it }

    private fun <T> call(r: Request.Builder, read: (JsonObject) -> T): T =
        http.newCall(r.header("Accept", "application/json").build()).execute().use { resp ->
            when (resp.code) {
                in 200..299 -> Unit
                400, 401 -> throw DispatcharrException("HTTP ${resp.code}", R.string.set_b_dp_wrong_login)
                403 -> throw DispatcharrException("HTTP 403", R.string.set_b_dp_blocked) // Dispatcharr's network access settings
                404 -> throw notDispatcharr("HTTP 404")
                429 -> throw DispatcharrException("HTTP 429", R.string.set_b_dp_rate_limited)
                else -> throw DispatcharrException("HTTP ${resp.code}", R.string.set_b_dp_failed, listOf(resp.code))
            }
            val o = runCatching { json.parseToJsonElement(resp.body!!.string()).jsonObject }.getOrNull() ?: throw notDispatcharr("not JSON")
            read(o)
        }

    private fun notDispatcharr(why: String) = DispatcharrException(why, R.string.set_b_dp_not_dispatcharr)

    companion object {
        /** Dispatcharr's web UI pages, in case the address was copied from the browser. */
        private val UI_PAGES = setOf("channels", "sources", "guide", "dvr", "settings", "stats", "users", "vods", "login", "plugins", "logos", "logs", "connect")

        /** Dispatcharr's API keys are `secrets.token_urlsafe(40)`: 54 URL-safe characters. */
        fun looksLikeApiKey(s: String) = s.trim().length >= 40 && s.trim().all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
