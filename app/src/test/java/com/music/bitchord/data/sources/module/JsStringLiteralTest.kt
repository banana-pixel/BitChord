package com.music.bitchord.data.sources.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arguments to a module export are spliced into JavaScript source, so the
 * quoter is the boundary between "remote data" and "code that runs in the
 * module VM". These tests pin both directions: nothing the input carries may
 * end the literal early, and everything it carries must decode back to itself.
 */
class JsStringLiteralTest {

    private val manager = ModuleManager()

    /** Decodes a JS double-quoted literal the way the VM's parser would. */
    private fun decodeLiteral(literal: String): String {
        assertTrue("literal must be double quoted", literal.startsWith("\"") && literal.endsWith("\""))
        val body = literal.substring(1, literal.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            i++
            when (val e = body[i]) {
                '"' -> { out.append('"'); i++ }
                '\\' -> { out.append('\\'); i++ }
                'n' -> { out.append('\n'); i++ }
                'r' -> { out.append('\r'); i++ }
                't' -> { out.append('\t'); i++ }
                'b' -> { out.append('\b'); i++ }
                'f' -> { out.append('\u000C'); i++ }
                'u' -> {
                    val hex = body.substring(i + 1, i + 5)
                    out.append(hex.toInt(16).toChar())
                    i += 5
                }
                else -> throw AssertionError("unexpected escape \\$e")
            }
        }
        return out.toString()
    }

    private fun assertRoundTrip(raw: String) {
        val literal = manager.jsStringLiteral(raw)
        assertEquals("must decode back to the input", raw, decodeLiteral(literal))
        // The literal must be a single JS token: no bare newline, quote or
        // line/paragraph separator may survive unescaped.
        val body = literal.substring(1, literal.length - 1)
        assertFalse("raw quote leaked", hasUnescaped(body, '"'))
        assertFalse("raw LF leaked", hasUnescaped(body, '\n'))
        assertFalse("raw CR leaked", hasUnescaped(body, '\r'))
        assertFalse("raw U+2028 leaked", hasUnescaped(body, '\u2028'))
        assertFalse("raw U+2029 leaked", hasUnescaped(body, '\u2029'))
    }

    /** True when [c] occurs in [body] without a preceding backslash escape. */
    private fun hasUnescaped(body: String, c: Char): Boolean {
        var backslashes = 0
        for (ch in body) {
            if (ch == c && backslashes % 2 == 0) return true
            backslashes = if (ch == '\\') backslashes + 1 else 0
        }
        return false
    }

    @Test
    fun `plain text is quoted unchanged`() {
        assertEquals("\"hello world\"", manager.jsStringLiteral("hello world"))
    }

    @Test
    fun `double quotes cannot end the literal`() {
        assertRoundTrip("""He said "hi" and left""")
    }

    @Test
    fun `single quotes stay data`() {
        assertRoundTrip("""Guns N' Roses - Sweet Child O' Mine""")
    }

    @Test
    fun `backslashes are doubled`() {
        assertRoundTrip("""C:\music\new\track""")
        assertEquals("\"a\\\\b\"", manager.jsStringLiteral("a\\b"))
    }

    @Test
    fun `newlines and tabs are escaped`() {
        assertRoundTrip("line one\nline two\r\n\tindented")
    }

    @Test
    fun `form feed and backspace are escaped`() {
        assertRoundTrip("a\u000Cb\u0008c")
        assertFalse(manager.jsStringLiteral("a\u000Cb").contains('\u000C'))
    }

    @Test
    fun `NUL and other control characters are escaped`() {
        assertRoundTrip("a\u0000b\u0001c\u001Fd")
        assertEquals("\"a\\u0000b\"", manager.jsStringLiteral("a\u0000b"))
    }

    @Test
    fun `line and paragraph separators are escaped`() {
        assertRoundTrip("before\u2028after\u2029end")
    }

    @Test
    fun `unicode text passes through as itself`() {
        assertRoundTrip("कुहू कुहू — 𝄞 🎵")
    }

    @Test
    fun `an injection attempt stays one string argument`() {
        val attack = """'); globalThis.pwned = 1; ('"""
        val literal = manager.jsStringLiteral(attack)
        assertEquals(attack, decodeLiteral(literal))
        // Every double quote inside is escaped, so the emitted call cannot be
        // broken out of and the payload never becomes a statement. (The single
        // quotes in the payload are ordinary characters inside a double-quoted
        // literal and correctly stay as data.)
        assertFalse(hasUnescaped(literal.substring(1, literal.length - 1), '"'))
    }

    @Test
    fun `a very long argument is still one literal`() {
        val raw = "\"\\\n".repeat(20_000)
        assertRoundTrip(raw)
    }
}
