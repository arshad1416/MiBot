package app.mibot.core.fuzz

import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The address a person types (or scans) when adding the phone to a hub. Whatever comes out is where
 * the phone sends its pairing code and, from then on, its sign-in, so it has to be either nothing or
 * the place that was typed.
 */
class UrlFuzzTest {
    private val hosts = listOf(
        "mac-mini" to "mac-mini", "localhost" to "localhost", "mini.example.ts.net" to "mini.example.ts.net", "192.168.1.20" to "192.168.1.20",
        "MINI.Example.TS.net" to "mini.example.ts.net", "[::1]" to "[::1]", "[fd7a:115c:a1e0::1]" to "[fd7a:115c:a1e0::1]", "[FD7A:115C:A1E0::1]" to "[fd7a:115c:a1e0::1]",
        "hub" to "hub", "a.b" to "a.b", "xn--bcher-kva.example" to "xn--bcher-kva.example", "bücher.example" to "xn--bcher-kva.example", "10.0.0.1" to "10.0.0.1",
    )
    private val spaces = listOf("", " ", "  ", "\t", "\n", " ", " \r\n")

    /** The checks any answer has to pass, whatever was typed. */
    private fun clean(out: String, typed: String) {
        val u = out.toHttpUrlOrNull()
        assertNotNull("normalizeUrl(${show(typed)}) = $out is not a web address", u)
        assertTrue("normalizeUrl(${show(typed)}) = $out: scheme", u!!.scheme == "http" || u.scheme == "https")
        assertEquals("normalizeUrl(${show(typed)}) = $out keeps a path", "/", u.encodedPath)
        assertNull("normalizeUrl(${show(typed)}) = $out keeps a query", u.query)
        assertNull("normalizeUrl(${show(typed)}) = $out keeps a fragment", u.fragment)
        assertTrue("normalizeUrl(${show(typed)}) = $out ends in a slash or has spaces", !out.endsWith("/") && out == out.trim() && out.none { it.isWhitespace() })
        assertEquals("normalizeUrl is not settled on $out", out, HubClient.normalizeUrl(out))
        // What the rest of the client does with it.
        val client = HubClient(HubAccount(out, "t"))
        assertEquals(u.host, client.url("/api/bots").toHttpUrlOrNull()?.host)
        assertEquals(u.port, client.url("/api/bots").toHttpUrlOrNull()?.port)
    }

    private fun show(s: String) = "\"" + clip(s, 120).map { if (it.code < 0x20 || it.code > 0x7E) "\\u%04x".format(it.code) else it.toString() }.joinToString("") + "\""

    @Test fun `an address typed in any usual way comes out as that host and port`() {
        repeat(6000) { n ->
            val r = Random(n)
            val (host, wantHost) = hosts[r.nextInt(hosts.size)]
            val scheme = listOf(null, "http", "https", "HTTP", "Https", "hTTps")[r.nextInt(6)]
            val port = if (r.nextBoolean()) listOf(8787, 80, 443, 1, 65535, 3000)[r.nextInt(6)] else null
            val tail = listOf("", "/", "//", "/pair", "/pair/", "/pair#ABCD2345", "/a/b/c?x=1&y=2#frag", "?x=1", "#frag", "/#/route", "/pair#ABCD2345#more")[r.nextInt(11)]
            val typed = spaces[r.nextInt(spaces.size)] + (scheme?.let { "$it://" } ?: "") + host + (port?.let { ":$it" } ?: "") + tail + spaces[r.nextInt(spaces.size)]

            val out = try { HubClient.normalizeUrl(typed) } catch (e: Throwable) { throw AssertionError("seed $n: normalizeUrl(${show(typed)}) threw $e", e) }
            assertNotNull("seed $n: normalizeUrl(${show(typed)}) gave nothing", out)
            clean(out!!, typed)
            val u = out.toHttpUrlOrNull()!!
            val wantScheme = scheme?.lowercase() ?: "http"
            assertEquals("seed $n: scheme of ${show(typed)}", wantScheme, u.scheme)
            assertEquals("seed $n: host of ${show(typed)} -> $out", wantHost.removeSurrounding("[", "]"), u.host)
            assertEquals("seed $n: port of ${show(typed)} -> $out", port ?: if (wantScheme == "https") 443 else 80, u.port)
            assertEquals("seed $n: no sign-in part appears from nowhere", "", u.username)
        }
    }

    @Test fun `junk comes out as nothing or as a clean address, and never throws`() {
        val alphabet = "abcHTPShttps:/.#?@[]%\\ -_0189\t\né中😀\u0000‮".toCharArray()
        repeat(8000) { n ->
            val r = Random(100_000 + n)
            val typed = when (r.nextInt(4)) {
                0 -> r.unicode(30)
                1 -> r.ascii(30)
                2 -> r.oddString().take(5_000)
                else -> String(CharArray(r.nextInt(0, 25)) { alphabet[r.nextInt(alphabet.size)] })
            }
            val out = try { HubClient.normalizeUrl(typed) } catch (e: Throwable) { throw AssertionError("seed ${100_000 + n}: normalizeUrl(${show(typed)}) threw $e", e) }
            if (out != null) clean(out, typed)

            val link = try { HubClient.parsePairLink(typed) } catch (e: Throwable) { throw AssertionError("seed ${100_000 + n}: parsePairLink(${show(typed)}) threw $e", e) }
            if (link != null) {
                clean(link.hubUrl, typed)
                assertTrue("parsePairLink(${show(typed)}) code ${link.code}", Regex("^[A-Z0-9]*$").matches(link.code))
            }
            val code = try { HubClient.normalizeCode(typed) } catch (e: Throwable) { throw AssertionError("seed ${100_000 + n}: normalizeCode(${show(typed)}) threw $e", e) }
            assertTrue("normalizeCode(${show(typed)}) = $code", code.all { it in 'A'..'Z' || it in '0'..'9' })
            assertEquals(code, HubClient.normalizeCode(code))
        }
    }

    /**
     * A slip of the thumb in the scheme. The person meant `host`; the app may refuse the text, but it must not
     * go and talk to a different machine (one named after the scheme).
     */
    @Test fun `a mistyped scheme is refused or still means the host that was typed`() {
        val slips = listOf<(String) -> String>(
            { "http:/$it" }, { "https:/$it" }, { "http//$it" }, { "https//$it" }, { "http:///$it" }, { "http;//$it" }, { "https;//$it" },
            { "htp://$it" }, { "httpss://$it" }, { "ws://$it" }, { "wss://$it" }, { "ftp://$it" }, { "http:\\\\$it" }, { "https:\\\\$it" },
            { "http: //$it" }, { "http ://$it" }, { "://$it" }, { "http.//$it" }, { "https:/ /$it" }, { "http:$it" }, { "https:$it" },
        )
        val wrong = ArrayList<String>()
        for ((host, wantHost) in hosts) for (port in listOf("", ":8787")) for ((i, slip) in slips.withIndex()) {
            val typed = slip(host + port)
            val out = try { HubClient.normalizeUrl(typed) } catch (e: Throwable) { throw AssertionError("normalizeUrl(${show(typed)}) threw $e", e) } ?: continue
            val u = out.toHttpUrlOrNull()
            if (u == null || u.host != wantHost.removeSurrounding("[", "]")) wrong += "slip #$i: typed ${show(typed)} -> $out (meant $wantHost$port)"
        }
        // Nothing but a scheme is not an address at all.
        for (typed in listOf("http://", "https://", "HTTP://", "http:", "https:", "http:///", " https:// ")) {
            val out = HubClient.normalizeUrl(typed)
            if (out != null) wrong.add(0, "typed ${show(typed)} -> $out (nothing was named)")
        }
        assertTrue("${wrong.size} typed addresses came out pointing somewhere else:\n" + wrong.take(30).joinToString("\n"), wrong.isEmpty())
    }

    @Test fun `a pairing link is read back as the hub and code it was made from, and anything more is refused`() {
        val codeChars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        repeat(6000) { n ->
            val r = Random(200_000 + n)
            val (host, _) = hosts[r.nextInt(hosts.size)]
            val scheme = listOf("http", "https", "HTTPS", "Http")[r.nextInt(4)]
            val port = if (r.nextBoolean()) ":${r.nextInt(1, 65536)}" else ""
            val hub = "$scheme://$host$port"
            val raw = String(CharArray(8) { codeChars[r.nextInt(codeChars.length)] })
            val code = if (r.nextBoolean()) raw.take(4) + "-" + raw.drop(4) else raw
            val pair = listOf("/pair", "/pair/", "/PAIR", "/Pair/")[r.nextInt(4)]
            val good = spaces[r.nextInt(spaces.size)] + hub + pair + "#" + code + spaces[r.nextInt(spaces.size)]

            val link = HubClient.parsePairLink(good)
            assertNotNull("seed ${200_000 + n}: ${show(good)} was not read", link)
            assertEquals("seed ${200_000 + n}: hub of ${show(good)}", HubClient.normalizeUrl(hub), link!!.hubUrl)
            assertEquals("seed ${200_000 + n}: code of ${show(good)}", raw.uppercase(), link.code)
            clean(link.hubUrl, good)

            // The same link with something more on it is not a pairing link.
            val more = listOf(
                "$hub$pair#$code#x", "$hub$pair#$code?x=1", "$hub$pair?x=1#$code", "$hub/other#$code", "$hub/x$pair#$code", "$hub$pair#", "$hub$pair#abc",
                "$hub$pair#$code $code", "$hub$pair#$code/x", "$hub#$code", "$code", "$hub$pair", "x$hub$pair#$code", "$hub$pair#${"A".repeat(21)}", "$hub$pair#$code\n$hub$pair#$code",
            )
            for (m in more) assertNull("seed ${200_000 + n}: ${show(m)} was read as a pairing link", HubClient.parsePairLink(m))
        }
    }

    @Test fun `a code is read the same however it is typed`() {
        repeat(5000) { n ->
            val r = Random(300_000 + n)
            val raw = String(CharArray(r.nextInt(1, 12)) { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[r.nextInt(32)] })
            val typed = raw.map { c -> (if (r.nextBoolean()) c.lowercaseChar() else c).toString() + listOf("", "", "", "-", " ", "–", ".")[r.nextInt(7)] }.joinToString("")
            assertEquals("seed ${300_000 + n}: ${show(typed)}", raw, HubClient.normalizeCode(" $typed\n"))
        }
    }
}
