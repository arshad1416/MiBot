package app.mibot.core.fuzz

import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import app.mibot.core.HubException
import app.mibot.core.VoiceLine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every call HubClient makes, against a stand-in hub on 127.0.0.1 that answers with anything: any
 * status, bodies that are right, nearly right, empty, HTML, cut off or random. The screens catch
 * HubException and show its message; anything else that comes out of a call is a crash.
 */
class HubClientFuzzTest {
    private val hub = FakeHub()

    @After fun tearDown() = hub.close()

    private val goodBodies = listOf(
        """{"user":{"id":"u1","email":"a@example.com","name":"Ari","avatarUrl":null}}""", """{"user":null}""",
        """{"bots":[{"id":"b1","name":"Scout"}],"live":[{"botId":"b1","isRunning":true}]}""", """{"bot":{"id":"b1","name":"Scout"}}""",
        """{"generation":1,"rows":[{"botId":"b1","seq":1,"body":{"type":"message","text":"hi"}}]}""", """{"routines":[{"id":"r1"}]}""",
        """{"computer":{"status":"running","screens":[{"botId":"b1","screenId":"s"}]}}""", """{"status":"ok","path":"","files":[{"name":"x","size":1}]}""",
        """{"url":"/api/bots/b1/files/raw?path=x"}""", """{"url":"https://files.example/x"}""", """{"url":"//other.example/x"}""", """{"url":""}""", """{"url":"/\u0000 bad"}""",
        """{"apps":[{"slug":"gmail"}],"connectedApps":[],"categories":[],"total":1}""", """{"status":"redirect","redirectUrl":"https://x.example"}""",
        """{"callId":"c","clientSecret":"s","websocketUrl":"wss://v.example/x","session":{}}""", """{"output":"ok","taskSeq":3}""", """{"text":"hello"}""", """{"ok":true}""",
    )

    private fun Random.reply(): FakeHub.Reply {
        val status = listOf(200, 200, 200, 200, 200, 201, 204, 302, 400, 401, 403, 404, 404, 409, 413, 429, 500, 502, 503)[nextInt(19)]
        val good = goodBodies[nextInt(goodBodies.size)]
        val body = when (nextInt(12)) {
            0 -> ""
            1 -> "<html><body>502 Bad Gateway</body></html>"
            2 -> unicode(300)
            3 -> good.take(nextInt(good.length + 1))
            4 -> """{"error":${anyJson()}}"""
            5 -> """{"error":"not found"}"""
            6 -> """{"error":${JsonPrimitive(oddString())}}"""
            7 -> { var d = parse(good); repeat(nextInt(1, 4)) { val ps = d.paths(); d = d.edit(ps[nextInt(ps.size)]) { anyJson() } }; d.toString() }
            8 -> anyJson().toString()
            else -> good
        }
        return FakeHub.Reply(status, body)
    }

    private fun Random.id(): String = when (nextInt(6)) {
        0 -> listOf("", "..", "../..", "a/b", "a?b=c", "a#b", "a b", "%", "%zz", "%00", "\u0000", "é", "😀", "a\nb", "\\", "//x", ":", "@")[nextInt(18)]
        1 -> oddString().take(200)
        else -> "b${nextInt(9)}"
    }

    private val calls: List<Pair<String, suspend (HubClient, Random) -> Any?>> = listOf(
        "me" to { c, _ -> c.me() }, "signOut" to { c, _ -> c.signOut() }, "bots" to { c, _ -> c.bots() },
        "createBot" to { c, r -> c.createBot(r.oddString().take(100), r.id(), r.id()) },
        "updateBot" to { c, r -> c.updateBot(r.id(), JsonObject(mapOf("name" to r.anyJson()))) },
        "deleteBot" to { c, r -> c.deleteBot(r.id()) }, "makePrimary" to { c, r -> c.makePrimary(r.id()) }, "markRead" to { c, r -> c.markRead(r.id()) },
        "rows" to { c, r -> c.rows(r.id(), if (r.nextBoolean()) r.nextInt() else null) },
        "sendMessage" to { c, r -> c.sendMessage(r.id(), r.oddString()) }, "interrupt" to { c, r -> c.interrupt(r.id()) }, "newConversation" to { c, r -> c.newConversation(r.id()) },
        "decide" to { c, r -> c.decide(r.id(), r.id()) }, "answer" to { c, r -> c.answer(r.id(), r.nextInt(), r.oddString()) }, "dismiss" to { c, r -> c.dismiss(r.id(), r.nextInt()) },
        "routines" to { c, r -> c.routines(r.id()) }, "setRoutine" to { c, r -> c.setRoutine(r.id(), r.nextBoolean()) }, "runRoutine" to { c, r -> c.runRoutine(r.id()) },
        "computer" to { c, _ -> c.computer() }, "wakeComputer" to { c, _ -> c.wakeComputer() }, "screenshot" to { c, r -> c.screenshot(r.id()) },
        "files" to { c, r -> c.files(r.id(), r.id()) }, "fileUrl" to { c, r -> c.fileUrl(r.id(), r.id()) }, "apps" to { c, r -> c.apps(r.oddString().take(300)) },
        "connectApp" to { c, r -> c.connectApp(r.id(), if (r.nextBoolean()) r.id() else null, if (r.nextBoolean()) r.nextInt() else null, if (r.nextBoolean()) r.oddString() else null) },
        "setAppForBot" to { c, r -> c.setAppForBot(r.id(), r.id(), r.nextBoolean()) }, "disconnectApp" to { c, r -> c.disconnectApp(r.id()) },
        // These three go to the stand-in only; the real hub would pass them on to a voice provider.
        "voiceSession" to { c, r -> c.voiceSession(r.id()) },
        "voiceTool" to { c, r -> c.voiceTool(r.id(), r.id(), r.id(), JsonObject(mapOf("request" to r.anyJson()))) },
        "voiceEnd" to { c, r -> c.voiceEnd(r.id(), r.id(), List(r.nextInt(3)) { VoiceLine(r.oddString().take(50), r.oddString().take(500)) }, if (r.nextBoolean()) r.oddString() else null) },
        "transcribe" to { c, r -> c.transcribe(ByteArray(r.nextInt(0, 2000)), "note.m4a", "audio/mp4", listOf(null, "auto", "en", "", "e", "😀x")[r.nextInt(6)]) },
    )

    @Test fun `any call against a hub that answers with anything gives a result or a HubException with something to show`() {
        val client = hub.client()
        val server = Random(1)
        hub.fallback = { synchronized(server) { server.reply() } }
        val escaped = ArrayList<String>()
        var done = 0
        for ((ci, call) in calls.withIndex()) {
            val r = Random(100 + ci)
            repeat(60) {
                try {
                    runBlocking { call.second(client, r) }
                } catch (e: HubException) {
                    if (e.message.isNullOrBlank()) escaped += "${call.first}: a HubException with nothing to show (status ${e.status})"
                } catch (e: Throwable) {
                    if (escaped.size < 30) escaped += "${call.first} [seed ${100 + ci}, call $it]: $e (last request ${hub.log.lastOrNull()?.let { l -> clip(l, 120) }})"
                }
                done++
            }
        }
        println("client calls: $done, escaped: ${escaped.size}")
        assertTrue("something other than a result or a HubException came out:\n" + escaped.joinToString("\n"), escaped.isEmpty())
    }

    @Test fun `errors read as the hub wrote them, or as plain words when it wrote none`() {
        val client = hub.client()
        fun message(status: Int, body: String): String {
            hub.fallback = { FakeHub.Reply(status, body) }
            return runCatching { runBlocking { client.bots() } }.exceptionOrNull().let { (it as HubException).also { e -> assertEquals(status, e.status) }.message!! }
        }
        assertEquals("That name is taken", message(400, """{"error":"That name is taken"}"""))
        assertEquals("This device is no longer signed in.", message(401, """{"error":"whatever"}"""))
        assertEquals("The hub no longer has that.", message(404, """{"error":"not found"}"""))
        assertEquals("The hub answered with an error (500).", message(500, "<html>"))
        assertEquals("The hub answered with an error (502).", message(502, """{"error":{"code":1}}"""))
        assertEquals("The hub answered with an error (400).", message(400, """{"error":null}"""))
        assertEquals("The hub sent something this app does not understand.", message(200, "<html>"))
    }

    @Test fun `pairing takes the sign-in the hub sets, however the cookie is written, and refuses anything else`() {
        val alphabets = listOf("0123456789abcdef", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=")
        repeat(300) { n ->
            val r = Random(n)
            val alpha = alphabets[r.nextInt(alphabets.size)]
            val token = String(CharArray(r.nextInt(8, 64)) { alpha[r.nextInt(alpha.length)] })
            val written = java.net.URLEncoder.encode(token, "UTF-8").replace("+", "%20") // what encodeURIComponent writes
            val attrs = listOf("; Max-Age=2592000; Path=/; HttpOnly; SameSite=Lax", "; Path=/", "", ";Secure", " ; HttpOnly")[r.nextInt(5)]
            val headers = when (r.nextInt(6)) {
                0 -> emptyMap()
                1 -> mapOf("Set-Cookie" to "other=1; Path=/")
                2 -> mapOf("Set-Cookie" to "grokbot_session=; Max-Age=0; Path=/")
                else -> mapOf("Set-Cookie" to "grokbot_session=$written$attrs")
            }
            val status = listOf(200, 200, 200, 400, 500)[r.nextInt(5)]
            hub.routes["POST /api/pair/sign-in"] = { FakeHub.Reply(status, if (status == 200) """{"ok":true}""" else """{"error":"That code is not right."}""", headers = headers) }
            val typed = listOf(hub.url, hub.url + "/", hub.url.removePrefix("http://"), " " + hub.url.uppercase().replace("HTTP", "http") + "/pair#x ")[r.nextInt(4)]
            val got = try {
                runBlocking { HubClient.pair(typed, "abcd-2345", FakeHub.http) }
            } catch (e: HubException) {
                assertTrue("seed $n: a refusal with nothing to show", !e.message.isNullOrBlank())
                null
            } catch (e: Throwable) {
                throw AssertionError("seed $n: pair threw $e", e)
            }
            val expectAccount = status == 200 && headers["Set-Cookie"]?.startsWith("grokbot_session=$written") == true
            assertEquals("seed $n: status $status headers $headers", if (expectAccount) HubAccount(hub.url, token) else null, got)
            if (got != null) {
                // The sign-in it kept is one it can send back.
                hub.json("/api/auth/me", """{"user":{"id":"u1"}}""")
                assertEquals("u1", runBlocking { HubClient(got, FakeHub.http).me() }?.id)
            }
        }
        assertTrue("the code is sent cleaned", hub.log.all { !it.contains("sign-in") || it == "POST /api/pair/sign-in" })
    }
}
