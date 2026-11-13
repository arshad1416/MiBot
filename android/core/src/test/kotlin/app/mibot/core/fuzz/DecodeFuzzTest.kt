package app.mibot.core.fuzz

import app.mibot.core.AppsResponse
import app.mibot.core.Bot
import app.mibot.core.BotResponse
import app.mibot.core.BotsResponse
import app.mibot.core.ComputerResponse
import app.mibot.core.ComputerState
import app.mibot.core.ConnectResult
import app.mibot.core.FilesResponse
import app.mibot.core.HubJson
import app.mibot.core.LibraryFile
import app.mibot.core.LiveState
import app.mibot.core.MeResponse
import app.mibot.core.QuestionCard
import app.mibot.core.Routine
import app.mibot.core.RoutinesResponse
import app.mibot.core.Row
import app.mibot.core.RowsResponse
import app.mibot.core.ServerEvent
import app.mibot.core.Toolkit
import app.mibot.core.UnknownBody
import app.mibot.core.UrlResponse
import app.mibot.core.User
import app.mibot.core.VoiceOption
import app.mibot.core.VoiceSession
import app.mibot.core.VoiceToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Everything the client decodes, against a hub that is a little newer or older (keys added, keys
 * missing or null, new kinds and statuses, empty lists, huge and odd strings), and against a hub
 * that sends nonsense. The first must decode. The second may be refused, but only the way
 * HubClient expects a refusal (an IllegalArgumentException, which it turns into a HubException).
 */
class DecodeFuzzTest {
    private class Sample<T>(val name: String, val ser: KSerializer<T>, json: String, val required: Set<String> = emptySet()) {
        val json: JsonElement = parse(json)
        fun decode(el: JsonElement): T = HubJson.decodeFromString(ser, el.toString())
    }

    private val bot = """{"id":"b1","name":"Scout","label":"work","description":"d","instructions":"i","avatarShape":"blob","avatarColor":"black","mode":"personal","isPrimary":true,"kind":"agent","pinned":false,"effort":"medium","voice":"eve","voiceSpeed":1.1,"voiceLanguage":"auto","model":null,"provider":"xai","autoModel":false,"weight":"heavy","generation":3,"unread":2,"lastPreview":"Done","lastActivityAt":1794500000000,"createdAt":1794000000000}"""
    private val bodies = listOf(
        "message" to """{"type":"message","role":"user","text":"hi","streaming":false,"from":{"kind":"voice","id":"call_1","name":"Voice call"},"citations":[{"url":"https://a.example/x","title":"A"},{"url":"https://b.example"}]}""",
        "tool-call" to """{"type":"tool-call","callId":"c1","name":"terminal","label":"Running a command","status":"running","args":{"command":"ls","n":[1,2,{"a":null}]},"summary":"ok","screenshot":"data:image/png;base64,AAAA"}""",
        "card" to """{"type":"card","card":"approval","approvalId":"ap1","title":"Allow Scout to send?","detail":"To Sam","tool":"GMAIL_SEND","toolkit":"gmail","toolkitName":"Gmail","logo":"https://l.example/g.png","args":{"to":"sam@example.com"},"status":"pending"}""",
        "card" to """{"type":"card","card":"question","question":"Which one?","options":["A","B"],"status":"answered","answer":"B"}""",
        "card" to """{"type":"card","card":"connect","toolkit":"notion","toolkitName":"Notion","logo":"https://l.example/n.png","reason":"To read the page","status":"pending"}""",
        "card" to """{"type":"card","card":"draft","channel":"email","to":"sam@example.com","subject":"Hi","text":"Hello","approvalId":"ap2","status":"pending"}""",
        "event" to """{"type":"event","event":"voice-call","text":"Voice call ended","ref":"r1","call":{"id":"call_1","phase":"ended","durationMs":7000,"summary":"s","transcript":[{"role":"user","text":"hi"},{"role":"assistant","text":"hello"}],"tasks":["do it"]}}""",
        "notice" to """{"type":"notice","text":"Heads up","tone":"warn"}""",
    )
    private val rows = bodies.mapIndexed { i, (kind, body) -> """{"botId":"b1","generation":3,"seq":${i + 1},"updatedSeq":${i + 4},"kind":"$kind","body":$body,"createdAt":${1794500000000 + i * 1000}}""" }
    private val allRows = rows.joinToString(",", "[", "]")
    private val live = """{"botId":"b1","isRunning":true,"activity":{"kind":"messaging_named","detail":"Scout","since":1794500000000},"awaiting":{"reason":"Approve sending","since":1794500000001},"startedAt":1794500000000,"caption":"Looking","steps":3}"""
    private val computer = """{"status":"running","provider":"orgo","orgoId":"o1","error":null,"screens":[{"botId":"b1","screenId":"s1","width":1280,"height":800},{"botId":"b2","screenId":"s2"}],"maxScreens":4}"""
    private val routine = """{"id":"r1","botId":"b1","name":"Morning","prompt":"Check mail","schedule":"0 9 * * *","timezone":"America/Toronto","enabled":true,"nextRunAt":1794500000000,"lastRunAt":null,"description":"Every day at 9"}"""
    private val toolkit = """{"slug":"gmail","name":"Gmail","logo":"https://l.example/g.png","description":"Mail","categories":["email","google"],"managedAuth":true,"noAuth":false,"connected":true,"source":"direct","auth":"oauth","custom":false,"bots":["b1","b2"]}"""
    private val user = """{"id":"u1","email":"a@example.com","name":"Ari Example","avatarUrl":null}"""

    private fun rowReq(p: String) = setOf("$p/botId", "$p/seq", "$p/body", "$p/body/citations/*/url")

    private val samples: List<Sample<*>> = listOf(
        Sample("User", User.serializer(), user, setOf("id")),
        Sample("Bot", Bot.serializer(), bot, setOf("id")),
        Sample("LiveState", LiveState.serializer(), live, setOf("botId")),
        Sample("Routine", Routine.serializer(), routine, setOf("id")),
        Sample("LibraryFile", LibraryFile.serializer(), """{"name":"notes.md","size":812,"modifiedAt":1794500000000,"isDir":false}""", setOf("name")),
        Sample("ComputerState", ComputerState.serializer(), computer),
        Sample("Toolkit", Toolkit.serializer(), toolkit, setOf("slug")),
        Sample("VoiceOption", VoiceOption.serializer(), """{"id":"eve","name":"Eve","gender":"female"}""", setOf("id")),
        Sample("BotsResponse", BotsResponse.serializer(), """{"bots":[$bot,$bot],"live":[$live]}""", setOf("bots/*/id", "live/*/botId")),
        Sample("RowsResponse", RowsResponse.serializer(), """{"generation":3,"rows":$allRows}""", rowReq("rows/*")),
        Sample("MeResponse", MeResponse.serializer(), """{"user":$user}""", setOf("user/id")),
        Sample("RoutinesResponse", RoutinesResponse.serializer(), """{"routines":[$routine]}""", setOf("routines/*/id")),
        Sample("ComputerResponse", ComputerResponse.serializer(), """{"computer":$computer}"""),
        Sample("FilesResponse", FilesResponse.serializer(), """{"status":"ok","path":"a/b","files":[{"name":"x","size":1,"modifiedAt":1000,"isDir":true}],"error":null}""", setOf("files/*/name")),
        Sample("AppsResponse", AppsResponse.serializer(), """{"apps":[$toolkit],"connected":["gmail"],"connectedApps":[$toolkit],"categories":["email"],"total":1}""", setOf("apps/*/slug", "connectedApps/*/slug")),
        Sample("ConnectResult", ConnectResult.serializer(), """{"status":"redirect","redirectUrl":"https://x.example/auth","keyPrompt":null}"""),
        Sample("BotResponse", BotResponse.serializer(), """{"bot":$bot}""", setOf("bot", "bot/id")),
        Sample("UrlResponse", UrlResponse.serializer(), """{"url":"/api/bots/b1/files/raw?path=x"}"""),
        Sample("VoiceSession", VoiceSession.serializer(), """{"callId":"call_1","clientSecret":"sec","expiresAt":1794500000,"model":"m","websocketUrl":"wss://v.example/realtime?model=m","sampleRate":24000,"instructions":"i","voice":"eve","speed":1.0,"language":"auto","tools":[{"type":"function","name":"send_task"}],"session":{"voice":"eve","turn_detection":{"type":"server_vad"},"tools":[]}}""", setOf("callId", "clientSecret", "websocketUrl")),
        Sample("VoiceToolResult", VoiceToolResult.serializer(), """{"output":"Handed off.","taskSeq":12}"""),
        Sample("event hello", ServerEvent.serializer(), """{"type":"hello","serverTime":1794500000000}"""),
        Sample("event rows", ServerEvent.serializer(), """{"type":"rows","botId":"b1","generation":3,"rows":$allRows}""", setOf("botId") + rowReq("rows/*")),
        Sample("event cleared", ServerEvent.serializer(), """{"type":"cleared","botId":"b1","generation":4}""", setOf("botId")),
        Sample("event live", ServerEvent.serializer(), """{"type":"live","state":$live}""", setOf("state", "state/botId")),
        Sample("event roster", ServerEvent.serializer(), """{"type":"roster"}"""),
        Sample("event bot", ServerEvent.serializer(), """{"type":"bot","bot":$bot}""", setOf("bot", "bot/id")),
        Sample("event computer", ServerEvent.serializer(), """{"type":"computer","state":$computer}""", setOf("state")),
        Sample("event routines", ServerEvent.serializer(), """{"type":"routines","botId":"b1"}""", setOf("botId")),
        Sample("event tasks", ServerEvent.serializer(), """{"type":"tasks","botId":"b1"}"""),
        Sample("event memories", ServerEvent.serializer(), """{"type":"memories","botId":"b1"}"""),
        Sample("event connections", ServerEvent.serializer(), """{"type":"connections"}"""),
        Sample("event machines", ServerEvent.serializer(), """{"type":"machines"}"""),
        Sample("event ping", ServerEvent.serializer(), """{"type":"ping"}"""),
    ) + rows.mapIndexed { i, r -> Sample("Row ${bodies[i].first} #$i", Row.serializer(), r, rowReq("").map { it.removePrefix("/") }.toSet()) }

    /** Tool arguments and the voice session's settings are free-form: the app keeps them as JSON and nothing in them can be wrong. */
    private fun free(p: String) = Regex("(^|/)(args|session|tools)(/|$)").containsMatchIn(p)

    /** One change a hub of another version could make. Returns the changed document and what was done, or null when the pick does not apply. */
    private fun Random.compatible(s: Sample<*>, doc: JsonElement): Pair<JsonElement, String>? {
        val all = doc.paths()
        val path = all[nextInt(all.size)]
        val node = doc.at(path) ?: return null
        val pat = pattern(path)
        val isKey = path.lastOrNull() is String
        val inFree = free(pat)
        return when (nextInt(7)) {
            0 -> if (isKey && (inFree || pat !in s.required)) doc.edit(path) { null } to "removed $pat" else null
            1 -> if (isKey && (inFree || pat !in s.required)) doc.edit(path) { JsonNull } to "null at $pat" else null
            2 -> if (node is JsonObject) doc.edit(path) { JsonObject((it as JsonObject) + ("zzNew${nextInt(3)}" to anyJson())) } to "unknown key in /$pat" else null
            3 -> if (node is JsonPrimitive && node.isString) { val v = oddString(); doc.edit(path) { JsonPrimitive(v) } to "string at $pat = ${clip(v, 40)}" } else null
            4 -> if (node is JsonArray) doc.edit(path) { JsonArray(emptyList()) } to "empty list at $pat" else null
            5 -> if (node is JsonArray && node.isNotEmpty()) { val n = nextInt(2, 400); doc.edit(path) { a -> JsonArray(List(n) { (a as JsonArray)[it % a.size] }) } to "$n items at $pat" } else null
            // An element taken out of a list (not one of the lists inside free-form JSON, which is covered by 0).
            else -> if (!isKey && path.isNotEmpty()) doc.edit(path) { null } to "dropped item $pat" else null
        }
    }

    @Test fun `a hub of another version is still read - keys added, missing or null, new kinds, empty lists, odd strings`() {
        val failures = ArrayList<String>()
        var cases = 0
        for ((si, s) in samples.withIndex()) {
            val r = Random(1000 + si)
            repeat(1500) {
                var doc = s.json
                val did = ArrayList<String>()
                repeat(r.nextInt(1, 4)) { r.compatible(s, doc)?.let { (d, what) -> doc = d; did += what } }
                if (did.isEmpty()) return@repeat
                cases++
                try { s.decode(doc) } catch (e: Throwable) {
                    if (failures.size < 40) failures += "${s.name} [seed ${1000 + si}] ${did.joinToString("; ")} -> ${e::class.simpleName}: ${clip(e.message.orEmpty(), 160)}"
                }
            }
        }
        println("compatible decode: $cases cases, ${failures.size} failures")
        assertTrue("these should decode:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun `unknown keys change nothing about what is read`() {
        for ((si, s) in samples.withIndex()) {
            val r = Random(2000 + si)
            val want = s.decode(s.json)
            repeat(300) {
                var doc = s.json
                repeat(r.nextInt(1, 6)) {
                    val objs = doc.paths().filter { doc.at(it) is JsonObject && !free(pattern(it)) }
                    val p = objs[r.nextInt(objs.size)]
                    doc = doc.edit(p) { JsonObject((it as JsonObject) + ("zzNew${r.nextInt(5)}" to r.anyJson())) }
                }
                assertEquals("${s.name} with extra keys: ${clip(doc.toString())}", want, s.decode(doc))
            }
        }
    }

    @Test fun `the least a hub can send, and everything it may leave null, is read`() {
        for (s in samples) {
            // Take out (then null) every key that is not required, deepest first so the paths stay good.
            for (mode in listOf("removed", "null")) {
                var doc = s.json
                for (p in s.json.paths().filter { it.lastOrNull() is String }.sortedByDescending { it.size }) {
                    val pat = pattern(p)
                    if (pat in s.required || s.required.any { it.startsWith("$pat/") } || pat.endsWith("type") || pat.endsWith("card")) continue
                    if (doc.at(p) == null) continue
                    doc = doc.edit(p) { if (mode == "removed") null else JsonNull }
                }
                try { s.decode(doc) } catch (e: Throwable) {
                    throw AssertionError("${s.name}, every optional key $mode: $doc -> $e")
                }
            }
        }
    }

    @Test fun `kinds this app does not know are skipped, not refused`() {
        val r = Random(3)
        repeat(3000) {
            val type = "zz-" + r.ascii()
            val row = HubJson.decodeFromString(Row.serializer(), """{"botId":"b1","seq":1,"body":{"type":${JsonPrimitive(type)},"x":${r.anyJson()}}}""")
            assertEquals(UnknownBody, row.body)
            val card = HubJson.decodeFromString(Row.serializer(), """{"botId":"b1","seq":1,"body":{"type":"card","card":${JsonPrimitive(type)},"status":"pending","x":${r.anyJson()}}}""")
            assertEquals(UnknownBody, card.body)
            assertEquals(ServerEvent.Other, HubJson.decodeFromString(ServerEvent.serializer(), """{"type":${JsonPrimitive(type)},"x":${r.anyJson()}}"""))
        }
        // No type at all, or a null one.
        assertEquals(UnknownBody, HubJson.decodeFromString(Row.serializer(), """{"botId":"b1","seq":1,"body":{}}""").body)
        assertEquals(UnknownBody, HubJson.decodeFromString(Row.serializer(), """{"botId":"b1","seq":1,"body":{"type":null}}""").body)
        assertEquals(ServerEvent.Other, HubJson.decodeFromString(ServerEvent.serializer(), """{}"""))
    }

    /** What HubClient.execute and the event stream do with a decode: an IllegalArgumentException is a refusal, anything else gets out. */
    private fun <T> refusedOrRead(s: Sample<T>, text: String): Throwable? = try {
        HubJson.decodeFromString(s.ser, text)
        null
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: Throwable) {
        e
    }

    @Test fun `nonsense from the hub is refused cleanly - wrong types, missing ids, cut-off and random text`() {
        val escaped = ArrayList<String>()
        var cases = 0
        for ((si, s) in samples.withIndex()) {
            val r = Random(4000 + si)
            repeat(1500) {
                var doc = s.json
                repeat(r.nextInt(1, 4)) {
                    val all = doc.paths()
                    val p = all[r.nextInt(all.size)]
                    doc = when (r.nextInt(4)) {
                        0 -> doc.edit(p) { null }
                        1 -> doc.edit(p) { JsonNull }
                        else -> doc.edit(p) { r.anyJson() }
                    }
                }
                val whole = doc.toString()
                val text = when (r.nextInt(8)) {
                    0 -> whole.take(r.nextInt(whole.length + 1)) // cut off
                    1 -> r.unicode(200)
                    2 -> whole.replace(":", if (r.nextBoolean()) "=" else "::")
                    3 -> whole + whole
                    else -> whole
                }
                cases++
                refusedOrRead(s, text)?.let { if (escaped.size < 40) escaped += "${s.name} [seed ${4000 + si}] ${clip(text)} -> $it" }
            }
        }
        println("hostile decode: $cases cases, ${escaped.size} escaped")
        assertTrue("something other than a clean refusal:\n" + escaped.joinToString("\n"), escaped.isEmpty())
    }

    @Test fun `fixed odd documents are refused cleanly`() {
        val docs = listOf(
            "", " ", "null", "[]", "{}", "0", "\"x\"", "{", "}", "[", "{\"type\":", "{\"type\":{}}", "{\"type\":[]}", "{\"type\":[\"rows\"]}",
            "{\"type\":\"rows\"}", "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":{}}", "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":[null]}",
            "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":[{\"botId\":\"b\",\"seq\":1,\"body\":null}]}", "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":[{\"botId\":\"b\",\"seq\":1,\"body\":[]}]}",
            "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":[{\"botId\":\"b\",\"seq\":1,\"body\":{\"type\":{\"a\":1}}}]}", "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":[{\"botId\":\"b\",\"seq\":1,\"body\":{\"type\":\"card\",\"card\":[1]}}]}",
            "{\"type\":\"live\",\"state\":null}", "{\"type\":\"live\",\"state\":\"x\"}", "{\"type\":\"bot\",\"bot\":[]}", "{\"type\":\"bot\"}",
            "{\"seq\":99999999999999999999}", "{\"seq\":1.5}", "{\"seq\":\"1\"}", "{\"seq\":true}", "<html>502 Bad Gateway</html>", "﻿{}",
            // Nested about as deep as tool arguments ever are in practice, and then some.
            "[".repeat(200), "{\"a\":".repeat(200), "{\"type\":\"rows\",\"botId\":\"b\",\"rows\":[{\"botId\":\"b\",\"seq\":1,\"body\":{\"type\":\"tool-call\",\"args\":${"[".repeat(200)}${"]".repeat(200)}}}]}",
        )
        val escaped = ArrayList<String>()
        for (s in samples) for (d in docs) refusedOrRead(s, d)?.let { escaped += "${s.name}: ${clip(d, 80)} -> $it" }
        assertTrue("something other than a clean refusal:\n" + escaped.take(30).joinToString("\n"), escaped.isEmpty())
    }

    /**
     * Not asserted, only measured: how deep JSON can nest before reading it overflows the stack (a StackOverflowError is
     * not a refusal HubClient turns into a HubException). Only a document built to do this gets there.
     */
    @Test fun `how deep a document can nest before reading it overflows the stack (printed, not asserted)`() {
        fun row(depth: Int) = "{\"generation\":1,\"rows\":[{\"botId\":\"b\",\"seq\":1,\"body\":{\"type\":\"tool-call\",\"args\":${"[".repeat(depth)}${"]".repeat(depth)}}}]}"
        val out = StringBuilder()
        for (depth in listOf(500, 1_000, 2_000, 5_000, 10_000, 50_000)) {
            val got = try {
                val page = HubJson.decodeFromString(RowsResponse.serializer(), row(depth))
                try { page == page.copy(); page.hashCode(); page.toString().length; "read, and compared/printed" } catch (e: Throwable) { "read, but comparing or printing it threw ${e::class.simpleName}" }
            } catch (e: Throwable) { "reading it threw ${e::class.simpleName}" }
            out.append("depth $depth: $got\n")
        }
        println("tool arguments nested N deep in a page of rows:\n$out")
    }

    /**
     * The hub does not check what a model passes to `ask_user` (server/src/agent/tools.ts: `options: (a.options ?? []).slice(0, 6)`,
     * `question: a.question`), so a model that answers with option objects, or a bare string, puts that in the row as it is.
     * The app relies on one such row not costing the person the rest of the conversation.
     */
    @Test fun `one row the app cannot read does not take the rest of the page with it`() {
        val good = rows[0]
        val shapes = listOf(
            """{"type":"card","card":"question","question":"Which one?","options":[{"label":"A"},{"label":"B"}],"status":"pending"}""",
            """{"type":"card","card":"question","question":"Which one?","options":"A, B","status":"pending"}""",
            """{"type":"card","card":"question","question":{"text":"Which one?"},"options":["A"],"status":"pending"}""",
            """{"type":"card","card":"connect","toolkit":"notion","toolkitName":"Notion","reason":["a","b"],"status":"pending"}""",
        )
        val lost = ArrayList<String>()
        for (body in shapes) {
            val bad = """{"botId":"b1","generation":3,"seq":99,"updatedSeq":99,"kind":"card","body":$body,"createdAt":1794500009000}"""
            val page = runCatching { HubJson.decodeFromString(RowsResponse.serializer(), """{"generation":3,"rows":[$good,$bad]}""") }
            if (page.getOrNull()?.rows?.any { it.seq == 1 } != true) lost += "page with $body -> ${page.exceptionOrNull()?.message?.let { clip(it, 120) }}"
            val event = runCatching { HubJson.decodeFromString(ServerEvent.serializer(), """{"type":"rows","botId":"b1","generation":3,"rows":[$good,$bad]}""") }
            if ((event.getOrNull() as? ServerEvent.Rows)?.rows?.any { it.seq == 1 } != true) lost += "event with $body -> ${event.exceptionOrNull()?.message?.let { clip(it, 120) }}"
        }
        assertTrue("the readable row was lost along with the unreadable one:\n" + lost.joinToString("\n"), lost.isEmpty())
    }

    @Test fun `numbers a hub may write differently are read`() {
        // Whole numbers written the long way, and counts as text, both happen across JSON writers.
        val bot = HubJson.decodeFromString(Bot.serializer(), """{"id":"b1","unread":"3","voiceSpeed":1,"lastActivityAt":1794500000000,"generation":0}""")
        assertEquals(3, bot.unread)
        assertEquals(1.0, bot.voiceSpeed, 0.0)
        val q = HubJson.decodeFromString(Row.serializer(), """{"botId":"b1","seq":1,"body":{"type":"card","card":"question","options":[1,true,"x"]}}""")
        assertEquals(listOf("1", "true", "x"), (q.body as QuestionCard).options)
    }
}
