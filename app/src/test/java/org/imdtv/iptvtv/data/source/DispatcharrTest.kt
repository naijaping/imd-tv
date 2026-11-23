package org.imdtv.iptvtv.data.source

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.imdtv.iptvtv.R
import org.imdtv.iptvtv.data.model.StreamFormat
import org.imdtv.iptvtv.data.security.Login
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Dispatcharr sign-in and its Xtream Codes API, against responses shaped like Dispatcharr 0.34's
 * (apps/accounts token + users/me, apps/output/views.py xc_get_info / _xc_channel_entry).
 */
class DispatcharrTest {
    private val server = MockWebServer()
    private val apiKey = "k".repeat(54)
    private var xcPassword: String? = "xcpass"
    private var networkBlocked = false

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (networkBlocked) return json(403, """{"error":"Forbidden"}""")
                return when (url.encodedPath) {
                    "/api/accounts/token/" -> {
                        val body = request.body.readUtf8()
                        if (request.method == "POST" && "\"username\":\"alice\"" in body && "\"password\":\"secret\"" in body)
                            json(200, """{"refresh":"r.r.r","access":"a.a.a"}""")
                        else json(401, """{"detail":"No active account found with the given credentials"}""")
                    }
                    "/api/accounts/users/me/" -> {
                        val ok = request.getHeader("Authorization") == "Bearer a.a.a" || request.getHeader("X-API-Key") == apiKey
                        if (!ok) json(401, """{"detail":"Authentication credentials were not provided."}""")
                        else json(200, """{"id":2,"username":"alice","api_key":null,"user_level":1,"custom_properties":""" +
                            (xcPassword?.let { """{"xc_password":"$it","hide_adult_content":false}""" } ?: "{}") + "}")
                    }
                    "/player_api.php" -> {
                        if (url.queryParameter("username") != "alice" || url.queryParameter("password") != "xcpass") json(401, """{"error":"Unauthorized"}""")
                        else when (url.queryParameter("action")) {
                            null -> json(200, """{"user_info":{"username":"alice","password":"xcpass","message":"Dispatcharr XC API","auth":1,"status":"Active",
                                "exp_date":"1900000000","active_cons":"0","max_connections":"2","allowed_output_formats":["ts"]},
                                "server_info":{"url":"localhost","server_protocol":"http","port":"9191","timezone":"UTC","timestamp_now":1700000000,
                                "time_now":"2026-11-23 12:00:00","process":true}}""")
                            "get_live_categories" -> json(200, """[{"category_id":"7","category_name":"UK Entertainment","parent_id":0}]""")
                            "get_live_streams" -> json(200, """[{"num":101,"name":"BBC One FHD","stream_type":"live","stream_id":42,
                                "stream_icon":"http://localhost:9191/api/channels/logos/9/cache/","epg_channel_id":"101","added":"1700000000",
                                "is_adult":0,"category_id":"7","category_ids":[7],"custom_sid":"","tv_archive":1,"direct_source":"","tv_archive_duration":7},
                                {"num":102,"name":"Radio 2","stream_type":"radio_streams","stream_id":43,"stream_icon":null,"epg_channel_id":"102",
                                "added":"1700000000","is_adult":0,"category_id":"7","category_ids":[7],"custom_sid":"","tv_archive":0,"direct_source":"","tv_archive_duration":0}]""")
                            else -> json(200, "[]")
                        }
                    }
                    else -> MockResponse().setResponseCode(404).setBody("<html>Not found</html>")
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private val base get() = server.url("/").toString().trimEnd('/')
    private val auth = DispatcharrAuth(OkHttpClient(), xtreamJson())

    private suspend fun failure(user: String, secret: String, url: String = base): Int =
        try { auth.xcLogin(url, user, secret); fail("signed in"); 0 } catch (e: DispatcharrException) { e.resId }

    @Test fun passwordGivesTheXcLogin() = runTest {
        assertEquals(Login("alice", "xcpass"), auth.xcLogin(base, "alice", "secret"))
    }

    @Test fun apiKeyWithoutUsername() = runTest {
        assertEquals(Login("alice", "xcpass"), auth.xcLogin(base, "", apiKey))
    }

    @Test fun apiKeyInThePasswordBox() = runTest {
        // The password sign-in is refused, then the same text is tried as an API key.
        assertEquals(Login("alice", "xcpass"), auth.xcLogin(base, "alice", apiKey))
    }

    @Test fun wrongPassword() = runTest {
        assertEquals(R.string.set_b_dp_wrong_login, failure("alice", "nope"))
        assertEquals(R.string.set_b_dp_enter_login, failure("alice", " "))
    }

    @Test fun noXcPasswordSaysWhatToDo() = runTest {
        xcPassword = null
        assertEquals(R.string.set_b_dp_no_xc_password, failure("alice", "secret"))
    }

    @Test fun blockedAndNotDispatcharr() = runTest {
        // A sub-path with no Dispatcharr behind it (the mock answers 404 there).
        assertEquals(R.string.set_b_dp_not_dispatcharr, failure("alice", "secret", "$base/elsewhere"))
        networkBlocked = true
        assertEquals(R.string.set_b_dp_blocked, failure("alice", "secret"))
    }

    @Test fun addressFromTheBrowser() {
        assertEquals("http://nas:9191", auth.base("http://nas:9191/channels"))
        assertEquals("http://nas:9191", auth.base(" http://nas:9191/ "))
        assertEquals("http://nas:9191", auth.base("http://nas:9191/api/accounts/token/"))
        assertEquals("https://host/dispatcharr", auth.base("https://host/dispatcharr/guide?x=1#top"))
        assertNull(auth.base("nas:9191"))
    }

    @Test fun xtreamApiLoadsChannelsWithTheXcLogin() = runTest {
        val login = auth.xcLogin(base, "alice", "secret")
        val src = XtreamSource(base, login.username, login.password, StreamFormat.TS, OkHttpClient(), xtreamJson(), userAgent = null)
        assertEquals(listOf("UK Entertainment"), src.liveCategories().map { it.name })
        val chans = src.liveChannels()
        val bbc = chans.single { it.remoteId == "42" }
        assertEquals("BBC One FHD", bbc.name)
        assertEquals(101, bbc.number)
        assertEquals("7", bbc.categoryRemoteId)
        assertEquals("http://localhost:9191/api/channels/logos/9/cache/", bbc.logo)
        assertTrue(chans.any { it.remoteId == "43" && it.logo == null })
        // The saved link carries placeholders, not the XC password (filled in at play time).
        assertTrue("xcpass" !in bbc.streamUrl)
        assertTrue(src.epgUrls().single().contains("/xmltv.php"))
    }
}
