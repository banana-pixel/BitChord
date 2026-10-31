package com.music.bitchord.ui.tv.auth

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Drives the TV's pairing server over a real socket.
 *
 * The remote is the one place a phone on the LAN can make the TV do
 * something, so these tests are about the gate rather than the pages: what a
 * request without the pairing code or the session cookie is allowed to do,
 * and what it gets once it has them.
 */
class TvAuthServerTest {

    private lateinit var session: TvServerSession
    private var receivedCookie: String? = null

    private val base get() = "http://127.0.0.1:${session.port}"

    @Before
    fun setUp() = runBlocking {
        receivedCookie = null
        session = TvAuthServer.start { receivedCookie = it }
            ?: throw AssertionError("TV server did not start")
    }

    @After
    fun tearDown() {
        TvAuthServer.stop()
    }

    private class Response(val code: Int, val body: String, val setCookie: String?)

    private fun request(
        path: String,
        method: String = "GET",
        cookie: String? = null,
        form: String? = null,
    ): Response {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.instanceFollowRedirects = false
        cookie?.let { conn.setRequestProperty("Cookie", it) }
        if (form != null) {
            val bytes = form.toByteArray()
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let { s ->
            BufferedReader(InputStreamReader(s)).readText()
        }.orEmpty()
        val setCookie = conn.getHeaderField("Set-Cookie")
        conn.disconnect()
        return Response(code, body, setCookie)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    @Test
    fun `api calls are refused without a session`() {
        for (path in listOf("/api/status", "/api/action?action=next", "/api/search?q=x", "/api/play?videoId=x")) {
            val response = request(path)
            assertEquals("$path must be refused", 200, response.code)
            assertTrue("$path leaked data: ${response.body}", response.body.contains("unauthorized"))
        }
    }

    @Test
    fun `the remote page needs the session cookie`() {
        val response = request("/remote")
        assertFalse("remote must not render unpaired", response.body.contains("Mobile Remote"))
        assertTrue(response.body.contains("Sign In to BitChord TV"))
    }

    @Test
    fun `a wrong pairing code is refused and mints no cookie`() {
        val wrong = if (session.code == "000000") "111111" else "000000"
        val response = request("/submit", method = "POST", form = "code=$wrong&cookie=abc")
        assertNull("no session may be minted", response.setCookie)
        assertTrue(response.body.contains("did not match"))
        assertNull(receivedCookie)
    }

    @Test
    fun `the landing page only carries the code it was given`() {
        val good = request("/?code=${session.code}")
        assertTrue(good.body.contains("value='${session.code}'"))

        val wrong = request("/?code=999999")
        assertFalse("a wrong code must not be echoed back", wrong.body.contains("value='999999'"))
        assertTrue(wrong.body.contains("did not match"))
    }

    @Test
    fun `pairing hands back an HttpOnly session cookie that unlocks the remote`() {
        val paired = request(
            "/submit",
            method = "POST",
            form = "code=${session.code}&cookie=${enc("SAPISID=secret")}",
        )
        assertEquals("SAPISID=secret", receivedCookie)
        val cookie = paired.setCookie ?: throw AssertionError("no Set-Cookie on pairing")
        assertTrue("cookie must be HttpOnly: $cookie", cookie.contains("HttpOnly", ignoreCase = true))
        assertTrue(cookie.startsWith("${TvAuthServer.SESSION_COOKIE_NAME}="))
        assertFalse("the YouTube cookie must not be the session", cookie.contains("SAPISID"))

        val value = cookie.substringBefore(';')

        val remote = request("/remote", cookie = value)
        assertTrue("paired browser gets the remote", remote.body.contains("Mobile Remote"))

        val status = request("/api/status", cookie = value)
        assertTrue(status.body.contains("\"title\""))

        val action = request("/api/action?action=next", method = "POST", cookie = value)
        assertTrue(action.body.contains("success"))
    }

    @Test
    fun `the pairing code is spent after a successful pairing`() {
        val first = request("/submit", method = "POST", form = "code=${session.code}&cookie=${enc("a")}")
        assertTrue(first.setCookie != null)

        val replay = request("/submit", method = "POST", form = "code=${session.code}&cookie=${enc("b")}")
        assertNull("a spent code must not pair again", replay.setCookie)
    }

    @Test
    fun `a stale cookie from a previous session is refused`() {
        val stale = "${TvAuthServer.SESSION_COOKIE_NAME}=" + "0".repeat(32)
        assertTrue(request("/api/status", cookie = stale).body.contains("unauthorized"))
        assertFalse(request("/remote", cookie = stale).body.contains("Mobile Remote"))
    }

    @Test
    fun `the session token rotates on every start`() {
        val before = session.code
        TvAuthServer.stop()
        session = runBlocking { TvAuthServer.start { } ?: throw AssertionError("restart failed") }
        assertNotEquals(before, session.code)
        assertEquals(6, session.code.length)
        assertTrue(session.code.all { it.isDigit() })
    }

    @Test
    fun `the remote page renders results as text, never as markup`() {
        val html = TvAuthServer.getRemoteControlHtml()
        assertFalse("innerHTML on remote data is the injection path", html.contains("innerHTML"))
        assertTrue(html.contains("textContent"))
        assertTrue(html.contains("createElement"))
        assertFalse("no credential belongs in the page", html.contains("const CODE"))
    }

    @Test
    fun `cookie values are parsed out of a browser header`() {
        assertEquals(
            "abc",
            TvAuthServer.extractCookieValue("a=1; bitchord_tv=abc; b=2", "bitchord_tv"),
        )
        assertNull(TvAuthServer.extractCookieValue("a=1; b=2", "bitchord_tv"))
        assertNull(TvAuthServer.extractCookieValue("", "bitchord_tv"))
    }

    @Test
    fun `json values cannot break out of their string`() {
        assertEquals("\"plain\"", TvAuthServer.jsonString("plain"))
        assertEquals(
            "\"He said \\\"hi\\\"\\\\ \\n\\u0001\"",
            TvAuthServer.jsonString("He said \"hi\"\\ \n\u0001"),
        )
        // U+2028 ends a line for a JavaScript parser, so it is escaped too.
        assertEquals("\"a\\u2028b\"", TvAuthServer.jsonString("a\u2028b"))
    }

    @Test
    fun `html interpolation escapes markup`() {
        assertEquals(
            "&lt;b&gt;&amp;&quot;&#39;",
            TvAuthServer.escapeHtml("<b>&\"'"),
        )
    }
}
