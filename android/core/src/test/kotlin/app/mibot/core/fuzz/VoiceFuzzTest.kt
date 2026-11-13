package app.mibot.core.fuzz

import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import app.mibot.core.HubSession
import app.mibot.core.VoiceAudio
import app.mibot.core.VoiceCall
import app.mibot.core.VoiceState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A voice call against a stand-in on 127.0.0.1 that plays both the hub (`/api/voice/…`) and the
 * voice service's realtime socket. Nothing here reaches the real hub or a voice provider. The
 * microphone and speaker are a fake that counts what it is asked to do.
 *
 * VoiceCall runs in the app's own scope, which has no handler for stray exceptions, so one that
 * gets out of a call's coroutine takes the app down. `watched.uncaught` is where those land here.
 */
class VoiceFuzzTest {
    private class FakeAudio : VoiceAudio {
        @Volatile var failWith: Exception? = null
        @Volatile var onPcm: ((ByteArray) -> Unit)? = null
        val starts = AtomicInteger(); val stops = AtomicInteger(); val plays = AtomicInteger(); val clears = AtomicInteger()
        val playsAfterStop = AtomicInteger()
        override fun start(sampleRate: Int, onPcm: (ByteArray) -> Unit) { failWith?.let { throw it }; starts.incrementAndGet(); this.onPcm = onPcm }
        override fun play(pcm: ByteArray) { plays.incrementAndGet(); if (stops.get() > 0) playsAfterStop.incrementAndGet() }
        override fun clearPlayback() { clears.incrementAndGet() }
        @Volatile override var playing = false
        override val micLevel = 0f
        override val outLevel = 0f
        override fun stop() { stops.incrementAndGet() }
    }

    private val server = MockWebServer()
    private val watched = Watched()
    private val audio = FakeAudio()
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private val fromClient = CopyOnWriteArrayList<String>() // what the app sent down the voice socket
    private val hubCalls = CopyOnWriteArrayList<Pair<String, String>>() // (path, body) of every hub request
    @Volatile private var socket: WebSocket? = null
    private val socketsOpened = AtomicInteger()

    // What the stand-in hub answers; tests change these.
    @Volatile private var sessionReply: () -> MockResponse = { MockResponse().setBody(sessionJson()) }
    @Volatile private var toolReply: (String) -> MockResponse = { MockResponse().setBody("""{"output":"Done."}""") }
    @Volatile private var socketReply: () -> MockResponse = { MockResponse().withWebSocketUpgrade(voiceService) }
    @Volatile private var rowsJson = """{"generation":1,"rows":[]}"""
    @Volatile private var botsJson = """{"bots":[{"id":"b1","name":"Scout"}],"live":[]}"""

    private val voiceService = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { socketsOpened.incrementAndGet(); socket = webSocket }
        override fun onMessage(webSocket: WebSocket, text: String) { fromClient += text }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    private fun sessionJson(websocketUrl: String = server.url("/realtime").toString().replace("http://", "ws://"), secret: String = "sec") =
        JsonObject(mapOf("callId" to JsonPrimitive("call_1"), "clientSecret" to JsonPrimitive(secret), "websocketUrl" to JsonPrimitive(websocketUrl), "sampleRate" to JsonPrimitive(24000), "session" to JsonObject(mapOf("voice" to JsonPrimitive("eve"))))).toString()

    private lateinit var session: HubSession
    private lateinit var call: VoiceCall

    private fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (!path.startsWith("/realtime")) hubCalls += path to request.body.readUtf8()
                return when {
                    path == "/api/voice/session" -> sessionReply()
                    path == "/api/voice/tool" -> toolReply(hubCalls.last().second)
                    path == "/api/voice/end" -> MockResponse().setBody("""{"ok":true,"seq":9}""")
                    path.startsWith("/realtime") -> socketReply()
                    path == "/api/bots" -> MockResponse().setBody(botsJson)
                    path.endsWith("/rows") -> MockResponse().setBody(rowsJson)
                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                }
            }
        }
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        session = HubSession(HubClient(HubAccount(server.url("/").toString(), "tok"), http), watched.scope) // not started: no event stream is needed
        call = VoiceCall(session, "b1", audio, watched.scope, http)
    }

    @After fun tearDown() {
        runCatching { call.end() }
        watched.scope.cancel()
        runCatching { server.shutdown() }
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun ends() = hubCalls.filter { it.first == "/api/voice/end" }
    private fun count(path: String) = hubCalls.count { it.first == path }
    private fun sentTypes() = fromClient.map { runCatching { parse(it).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull() }
    private fun noCrash(why: String = "") = assertTrue("$why an exception nobody caught (a crash on the phone): ${watched.uncaught.map { it.toString() }}", watched.uncaught.isEmpty())
    private fun say(json: String) { socket!!.send(json) }

    /** Start the call and bring it to Live the way the voice service does. */
    private fun connect() {
        call.start()
        waitFor("the app to open the voice socket and send its settings") { sentTypes().contains("session.update") }
        say("""{"type":"session.updated"}""")
        waitFor("the call to go live") { call.state.value.status == VoiceState.Status.Live }
    }

    private var syncs = 0
    /** Wait until everything said so far has been handled: a caption with a fresh id is the marker. */
    private fun sync() {
        val id = "sync-${syncs++}"
        say("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","transcript":"."}""")
        waitFor("the app to catch up with the voice service ($id); state=${call.state.value.status} error=${call.state.value.error}", 15_000) { call.state.value.captions.any { it.id == id } || !call.state.value.active }
    }

    // ---------- random events ----------

    private val types = listOf(
        "session.updated", "session.created", "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped", "input_audio_buffer.committed",
        "conversation.item.added", "conversation.item.created", "conversation.item.input_audio_transcription.updated", "conversation.item.input_audio_transcription.delta",
        "conversation.item.input_audio_transcription.completed", "response.created", "response.output_item.added", "response.output_audio_transcript.delta",
        "response.audio_transcript.delta", "response.output_text.delta", "response.text.delta", "response.output_audio_transcript.done", "response.audio_transcript.done",
        "response.output_audio.delta", "response.audio.delta", "response.function_call_arguments.done", "response.done", "error", "rate_limits.updated", "zz.unknown", "",
    )

    private fun Random.arguments(): JsonElement = when (nextInt(9)) {
        0 -> JsonPrimitive("""{"request":"do it ${nextInt(99)}"}""")
        1 -> JsonPrimitive("{")
        2 -> JsonPrimitive("")
        3 -> JsonPrimitive("null")
        4 -> JsonPrimitive("[1,2]")
        5 -> JsonPrimitive("""{"request":{"a":[1]}}""")
        6 -> JsonPrimitive("""{"request":"${"x".repeat(nextInt(1, 30_000))}"}""")
        7 -> JsonObject(mapOf("request" to JsonPrimitive("an object, not text")))
        else -> anyJson()
    }

    private fun Random.toolName(): JsonElement = when (nextInt(6)) { 0, 1 -> JsonPrimitive("send_task"); 2 -> JsonPrimitive("search_history"); 3 -> JsonPrimitive(oddString().take(60)); 4 -> JsonPrimitive("zz_tool_from_a_newer_hub"); else -> anyJson() }

    private fun Random.plausible(key: String): JsonElement = when (key) {
        "item" -> JsonObject(mapOf(
            "id" to JsonPrimitive("i${nextInt(6)}"), "type" to JsonPrimitive(listOf("message", "function_call", "zz")[nextInt(3)]), "role" to JsonPrimitive(listOf("user", "assistant", "system")[nextInt(3)]),
            "content" to JsonArray(List(nextInt(3)) { JsonObject(mapOf("type" to JsonPrimitive(listOf("input_audio", "output_audio", "input_text")[nextInt(3)]), "transcript" to if (nextBoolean()) JsonPrimitive(oddString().take(200)) else anyJson())) }),
        ))
        "item_id" -> JsonPrimitive("i${nextInt(6)}")
        "delta" -> when (nextInt(5)) { 0 -> JsonPrimitive("!!not base64!!"); 1 -> JsonPrimitive(""); 2 -> JsonPrimitive(oddString().take(500)); else -> JsonPrimitive(Base64.getEncoder().encodeToString(nextBytes(nextInt(0, 4000)))) }
        "transcript" -> JsonPrimitive(oddString().take(500))
        "response" -> JsonObject(mapOf("id" to JsonPrimitive("r${nextInt(4)}"), "status" to JsonPrimitive(listOf("completed", "cancelled", "failed")[nextInt(3)]), "output" to JsonArray(List(nextInt(3)) {
            JsonObject(mapOf("type" to JsonPrimitive(if (nextBoolean()) "function_call" else "message"), "call_id" to JsonPrimitive("call${nextInt(25)}"), "name" to toolName(), "arguments" to arguments()))
        })))
        "response_id" -> JsonPrimitive("r${nextInt(4)}")
        "call_id" -> JsonPrimitive("call${nextInt(25)}")
        "name" -> toolName()
        "arguments" -> arguments()
        "error" -> JsonObject(mapOf("code" to JsonPrimitive(listOf("response_cancel_not_active", "rate_limit", "")[nextInt(3)]), "message" to JsonPrimitive(oddString().take(100))))
        else -> JsonPrimitive("x")
    }

    private fun Random.serviceEvent(): String {
        if (nextInt(25) == 0) return listOf("[]", "null", "not json", "", "{", "\"text\"", "0", """{"type":{"a":1}}""", """{"type":["response.done"]}""", "{}")[nextInt(10)]
        val fields = LinkedHashMap<String, JsonElement>()
        if (nextInt(40) != 0) fields["type"] = JsonPrimitive(types[nextInt(types.size)])
        for (key in listOf("item", "item_id", "delta", "transcript", "response", "response_id", "call_id", "name", "arguments", "error", "code")) {
            when (nextInt(5)) { 0, 1 -> fields[key] = plausible(key); 2 -> fields[key] = anyJson(); else -> {} }
        }
        return JsonObject(fields).toString()
    }

    @Test fun `anything the voice service sends - malformed, unknown, out of order - leaves the call up and nothing uncaught`() {
        setUp()
        val hubRandom = Random(99)
        toolReply = {
            synchronized(hubRandom) {
                when (hubRandom.nextInt(8)) {
                    0 -> MockResponse().setResponseCode(500).setBody("boom")
                    1 -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                    2 -> MockResponse().setBody("<html>")
                    3 -> MockResponse().setBody("""{"output":{"not":"text"},"taskSeq":"x"}""")
                    4 -> MockResponse().setBody("""{"output":"Handed off.","taskSeq":${hubRandom.nextInt(1, 50)}}""")
                    else -> MockResponse().setBody("""{"output":"ok"}""")
                }
            }
        }
        connect()
        var sent = 0
        for (seed in 1..10) {
            val r = Random(seed)
            repeat(250) {
                when (r.nextInt(30)) {
                    0 -> audio.playing = !audio.playing
                    1 -> call.setMuted(r.nextBoolean())
                    2 -> audio.onPcm?.invoke(r.nextBytes(r.nextInt(0, 2000)))
                    3 -> socket!!.send(r.nextBytes(r.nextInt(0, 200)).toByteString())
                    else -> { say(r.serviceEvent()); sent++ }
                }
            }
            audio.playing = false
            call.setMuted(false)
            sync()
            val st = call.state.value
            noCrash("seed $seed:")
            assertEquals("seed $seed: the call ended by itself: ${st.error}", VoiceState.Status.Live, st.status)
            assertTrue("seed $seed: ${st.captions.size} captions are kept", st.captions.size <= 200)
        }
        println("voice events sent: $sent, tool calls answered by the hub: ${count("/api/voice/tool")}, app->service messages: ${fromClient.size}")

        // Hang up. The hub is told once, the speaker is let go once, and nothing more goes out or is played.
        call.end()
        call.end()
        waitFor("the hub to be told the call is over") { ends().size == 1 }
        Thread.sleep(500) // what was already on its way to the voice service arrives
        val after = fromClient.size
        val played = audio.plays.get()
        runCatching { say("""{"type":"response.output_audio.delta","delta":"AAAA"}""") }
        audio.onPcm?.invoke(ByteArray(480))
        call.setMuted(true)
        Thread.sleep(400)
        assertEquals("hub told once", 1, ends().size)
        assertEquals("speaker let go once", 1, audio.stops.get())
        assertEquals("nothing sent after the end", after, fromClient.size)
        assertEquals("nothing played after the end", played, audio.plays.get())
        assertEquals(VoiceState(), call.state.value)
        noCrash()
    }

    // ---------- tool calls ----------

    @Test fun `every tool call is answered once, whatever its arguments look like`() {
        setUp()
        connect()
        val args = listOf("""{"request":"book it"}""", "{", "", "null", "[1]", "\"text\"", """{"request":5}""", """{"request":null}""", """{"request":["a"]}""", "{\"request\":\"${"y".repeat(50_000)}\"}", "😀")
        say("""{"type":"response.created","response":{"id":"r1"}}""")
        for ((i, a) in args.withIndex()) say("""{"type":"response.function_call_arguments.done","call_id":"c$i","name":"${if (i % 2 == 0) "send_task" else "search_history"}","arguments":${JsonPrimitive(a)}}""")
        say("""{"type":"response.function_call_arguments.done","call_id":"c0","name":"send_task","arguments":"{}"}""") // the same call again
        say("""{"type":"response.function_call_arguments.done","name":"send_task","arguments":"{}"}""") // no id
        say("""{"type":"response.function_call_arguments.done","call_id":"cX","arguments":"{}"}""") // no name
        say("""{"type":"response.function_call_arguments.done","call_id":"cObj","name":"send_task","arguments":{"request":"given as an object"}}""")
        say("""{"type":"response.function_call_arguments.done","call_id":{"id":1},"name":["x"],"arguments":7}""")
        // The whole answer lists them again, as the service does.
        say("""{"type":"response.done","response":{"id":"r1","output":[{"type":"function_call","call_id":"c1","name":"search_history","arguments":"{"},{"type":"function_call","call_id":"cLate","name":"search_history","arguments":"{\"query\":\"x\"}"},{"type":"message"},7,null]}}""")
        sync()
        waitFor("the answers to go back to the voice service") { sentTypes().count { it == "conversation.item.create" } >= args.size + 2 }
        Thread.sleep(300)
        val answered = fromClient.mapNotNull { runCatching { parse(it).jsonObject["item"]?.jsonObject?.takeIf { o -> o["type"]?.jsonPrimitive?.content == "function_call_output" }?.get("call_id")?.jsonPrimitive?.content }.getOrNull() }
        assertEquals("each call answered once", (args.indices.map { "c$it" } + listOf("cObj", "cLate")).sorted(), answered.sorted())
        // What the hub was asked: always an object of arguments.
        for ((_, body) in hubCalls.filter { it.first == "/api/voice/tool" }) assertTrue("args sent to the hub: ${clip(body, 200)}", parse(body).jsonObject["args"] is JsonObject)
        assertEquals(args.size + 2, count("/api/voice/tool"))
        assertEquals(VoiceState.Status.Live, call.state.value.status)
        noCrash()
    }

    private fun handOff() {
        toolReply = { MockResponse().setBody("""{"output":"Handed off.","taskSeq":5}""") }
        connect()
        say("""{"type":"response.created","response":{"id":"r1"}}""")
        say("""{"type":"response.output_audio_transcript.delta","item_id":"i1","delta":"On it."}""")
        say("""{"type":"response.function_call_arguments.done","call_id":"c1","name":"send_task","arguments":"{\"request\":\"Find the report\"}"}""")
        say("""{"type":"response.done","response":{"id":"r1"}}""")
        waitFor("the hub to take the task") { count("/api/voice/tool") == 1 }
        waitFor("the task to show as working") { call.state.value.working == listOf("Find the report") }
        Thread.sleep(100)
    }

    private fun botStartsWorking() {
        botsJson = """{"bots":[{"id":"b1","name":"Scout"}],"live":[{"botId":"b1","isRunning":true}]}"""
        runBlocking { session.loadBots() }
    }

    /** The bot's reply is in the conversation and the bot is idle again: what the app's state holds after the hub's `rows` and `live` events. */
    private fun replyLands() {
        rowsJson = """{"generation":1,"rows":[{"botId":"b1","generation":1,"seq":5,"updatedSeq":5,"body":{"type":"message","role":"user","text":"Find the report","from":{"kind":"voice","id":"call_1","name":"Voice call"}}},{"botId":"b1","generation":1,"seq":6,"updatedSeq":7,"body":{"type":"message","role":"assistant","text":"The report is in your files."}}]}"""
        botsJson = """{"bots":[{"id":"b1","name":"Scout"}],"live":[{"botId":"b1","isRunning":false}]}"""
        runBlocking { session.loadRows("b1"); session.loadBots() }
    }

    private fun notes() = fromClient.filter { it.contains("[work_landed]") }

    @Test fun `work handed to the bot is announced once when its reply lands`() {
        setUp()
        handOff()
        botStartsWorking()
        Thread.sleep(2_000) // the bot takes a couple of seconds
        replyLands()
        waitFor("the voice agent to be told the work landed", 10_000) { notes().isNotEmpty() }
        assertTrue(notes().single().contains("The report is in your files."))
        waitFor("the task to stop showing as working") { call.state.value.working.isEmpty() }
        // More of the same state arriving does not announce it again.
        runBlocking { session.loadRows("b1"); session.loadBots() }
        Thread.sleep(2_500)
        assertEquals("announced once", 1, notes().size)
        noCrash()
    }

    /**
     * The same, with a bot that is quick: its answer (or its failure) is in the conversation within a second of the hand-off.
     * The call is the only way the person hears the outcome, and "working" stays on the call screen until it is announced.
     */
    @Test fun `work that is finished within a second of being handed off is still announced`() {
        setUp()
        handOff()
        botStartsWorking()
        Thread.sleep(300)
        replyLands()
        val told = cameTrue(8_000) { notes().isNotEmpty() }
        noCrash()
        assertTrue(
            "the bot's reply landed about half a second after the hand-off and, 8 s later, the voice agent has not been told " +
                "(no [work_landed] note was sent) and the call still shows working=${call.state.value.working}",
            told,
        )
    }

    // ---------- the start ----------

    /**
     * The person taps call and then hangs up before the hub has answered `/api/voice/session` (it asks the voice
     * provider for a secret first, so that takes a moment), or the activity stops, which ends the call too.
     * By then the hub has written "Voice call started" and holds the call open; the web app tells it "Cancelled".
     */
    @Test fun `hanging up while the call is still being set up tells the hub the call is over`() {
        setUp()
        sessionReply = { MockResponse().setBody(sessionJson()).setHeadersDelay(500, TimeUnit.MILLISECONDS) }
        call.start()
        waitFor("the app to ask the hub for a voice session") { count("/api/voice/session") == 1 }
        call.end()
        assertEquals(VoiceState(), call.state.value)
        val told = cameTrue(4_000) { ends().isNotEmpty() }
        noCrash()
        assertEquals("no voice socket is opened for a call that was hung up", 0, socketsOpened.get())
        assertTrue(
            "the hub answered /api/voice/session (so it has a call open and a \"Voice call started\" row) but was never sent /api/voice/end: " +
                "hub requests were ${hubCalls.map { it.first }}. The hub closes such a call by itself only after two hours.",
            told,
        )
    }

    /** A hub of another version (one that carries the voice socket itself, say) may give an address this app's HTTP library will not take. */
    @Test fun `a voice session the app cannot connect to ends the call with a message, not a crash`() {
        setUp()
        val bad = ArrayList<String>()
        for (url in listOf("/api/voice/realtime", "", "realtime", "wss://", "xai://voice.example/realtime", "wss://exa mple/realtime")) {
            watched.uncaught.clear()
            val before = ends().size
            sessionReply = { MockResponse().setBody(sessionJson(websocketUrl = url)) }
            val c = VoiceCall(session, "b1", FakeAudio(), watched.scope, http)
            c.start()
            val failed = cameTrue(1_500) { c.state.value.status == VoiceState.Status.Error }
            Thread.sleep(200)
            if (watched.uncaught.isNotEmpty() || !failed) bad += "websocketUrl \"$url\": state=${c.state.value.status} error=${c.state.value.error} hub told=${ends().size - before} uncaught=${watched.uncaught.map { it.toString() }}"
            c.end()
        }
        watched.uncaught.clear()
        assertTrue("the call neither started nor ended, and an exception got out (a crash on the phone):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun `a hub that cannot start a voice session, or answers with nonsense, ends the call with a message`() {
        setUp()
        val replies = listOf(
            MockResponse().setResponseCode(502).setBody("""{"error":"Could not start a voice session"}""") to "Could not start a voice session",
            MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""") to "The hub no longer has that.",
            MockResponse().setBody("<html>") to "The hub sent something this app does not understand.",
            MockResponse().setBody("""{"callId":"c"}""") to "The hub sent something this app does not understand.",
            MockResponse().setBody("""{"callId":"c","clientSecret":"s","websocketUrl":"wss://v.example","session":[1]}""") to "The hub sent something this app does not understand.",
            MockResponse().setResponseCode(401).setBody("{}") to "This device is no longer signed in.",
        )
        for ((reply, message) in replies) {
            sessionReply = { reply }
            val a = FakeAudio()
            val c = VoiceCall(session, "b1", a, watched.scope, http)
            c.start()
            waitFor("the call to fail with \"$message\"") { c.state.value.status == VoiceState.Status.Error }
            assertEquals(message, c.state.value.error)
            assertEquals("the microphone is let go", 1, a.stops.get())
            c.end()
            noCrash()
        }
        assertEquals("no socket was opened", 0, socketsOpened.get())
    }

    @Test fun `no microphone - the call ends with a message and the hub is not asked for anything`() {
        setUp()
        for ((thrown, message) in listOf(IllegalStateException("The microphone is in use.") to "The microphone is in use.", SecurityException() to "The microphone could not be opened.", RuntimeException("  ") to "The microphone could not be opened.")) {
            val a = FakeAudio().apply { failWith = thrown }
            val c = VoiceCall(session, "b1", a, watched.scope, http)
            c.start()
            waitFor("the call to fail") { c.state.value.status == VoiceState.Status.Error }
            assertEquals(message, c.state.value.error)
            c.end(); c.end()
        }
        Thread.sleep(200)
        assertTrue("hub requests: $hubCalls", hubCalls.isEmpty())
        noCrash()
    }

    @Test fun `events before the service is ready, then ready - the call still goes live`() {
        setUp()
        call.start()
        waitFor("the app to open the voice socket") { sentTypes().contains("session.update") }
        val r = Random(5)
        repeat(300) { val ev = r.serviceEvent(); if (!ev.contains("\"error\"") && !ev.contains("session.updated")) say(ev) }
        Thread.sleep(300)
        assertEquals("still connecting: ${call.state.value.error}", VoiceState.Status.Connecting, call.state.value.status)
        audio.onPcm?.invoke(ByteArray(480))
        Thread.sleep(100)
        assertEquals("no microphone sound goes out before the service is ready", 0, sentTypes().count { it == "input_audio_buffer.append" })
        say("""{"type":"session.updated"}""")
        waitFor("the call to go live") { call.state.value.status == VoiceState.Status.Live }
        say("""{"type":"session.updated"}""") // twice
        sync()
        audio.onPcm?.invoke(ByteArray(480))
        waitFor("microphone sound to go out once the service is ready") { sentTypes().contains("input_audio_buffer.append") }
        assertEquals(VoiceState.Status.Live, call.state.value.status)
        noCrash()
    }

    @Test fun `an error from the service before it is ready ends the call with words, in any shape`() {
        setUp()
        val shapes = listOf(
            """{"type":"error","error":{"code":"invalid_api_key","message":"Bad secret"}}""" to "Bad secret",
            """{"type":"error","error":{"message":""}}""" to "Voice service error",
            """{"type":"error","error":"just text"}""" to "Voice service error",
            """{"type":"error","error":{"code":{"a":1},"message":["x"]}}""" to "Voice service error",
            """{"type":"error","code":7}""" to "Voice service error",
            """{"type":"error"}""" to "Voice service error",
        )
        for ((ev, message) in shapes) {
            fromClient.clear()
            val a = FakeAudio()
            val c = VoiceCall(session, "b1", a, watched.scope, http)
            c.start()
            waitFor("the app to open the voice socket") { sentTypes().contains("session.update") }
            say(ev)
            waitFor("the call to fail on $ev") { c.state.value.status == VoiceState.Status.Error }
            assertEquals(ev, message, c.state.value.error)
            assertEquals(1, a.stops.get())
            c.end()
            noCrash()
        }
        waitFor("the hub to be told about each") { ends().size == shapes.size }
    }

    // ---------- the line drops ----------

    @Test fun `a cancel error is not a failure, a line that breaks ends the call as dropped, and a refusal at the door ends it too`() {
        setUp()
        // The voice socket on a stand-in of its own, so it can be cut while the hub stays up.
        val line = MockWebServer()
        line.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().withWebSocketUpgrade(voiceService) }
        line.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        sessionReply = { MockResponse().setBody(sessionJson(websocketUrl = line.url("/realtime").toString().replace("http://", "ws://"))) }
        connect()
        // A cancel that had nothing to cancel is not a failure; nor is any other error once the call is up.
        say("""{"type":"error","error":{"code":"response_cancel_not_active","message":"no active response found"}}""")
        say("""{"type":"error","error":{"code":"server_error","message":"something"}}""")
        sync()
        assertEquals(VoiceState.Status.Live, call.state.value.status)
        line.shutdown() // the connection is cut, no goodbye
        waitFor("the call to end as dropped") { call.state.value.status == VoiceState.Status.Error }
        assertEquals("Call dropped", call.state.value.error)
        waitFor("the hub to be told") { ends().size == 1 }
        assertTrue(ends().single().second.contains("Call dropped"))
        assertEquals(1, audio.stops.get())

        // Refused at the door.
        sessionReply = { MockResponse().setBody(sessionJson()) }
        socketReply = { MockResponse().setResponseCode(403).setBody("no") }
        val a = FakeAudio()
        val c = VoiceCall(session, "b1", a, watched.scope, http)
        c.start()
        waitFor("the refused call to fail") { c.state.value.status == VoiceState.Status.Error }
        assertEquals("Couldn’t connect the call", c.state.value.error)
        waitFor("the hub to be told") { ends().size == 2 }
        c.end()
        noCrash()
    }

    /**
     * The voice service ends a call the ordinary way, with a WebSocket close (its session ran out, it is restarting).
     * The web app's `onclose` then ends the call as dropped. Here the person keeps talking for five seconds more.
     */
    @Test fun `the voice service closing the call ends it`() {
        setUp()
        connect()
        socket!!.close(1001, "session ended")
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && call.state.value.status == VoiceState.Status.Live) {
            audio.onPcm?.invoke(ByteArray(960)) // the microphone keeps sending
            Thread.sleep(40)
        }
        val st = call.state.value
        noCrash()
        assertTrue(
            "the voice service closed the call (code 1001) five seconds ago and the call still shows as ${st.status}: " +
                "speaking=${st.speaking} listening=${st.listening} error=${st.error}; the hub was told ${ends().size} time(s), the microphone was let go ${audio.stops.get()} time(s)",
            st.status == VoiceState.Status.Error,
        )
    }

    /** Not something the app does today (VoiceController makes a new VoiceCall for each call); it only has to be harmless. */
    @Test fun `start twice and end twice do not throw`() {
        setUp()
        call.start()
        call.start()
        waitFor("the app to open a voice socket") { sentTypes().contains("session.update") }
        Thread.sleep(300)
        say("""{"type":"session.updated"}""")
        Thread.sleep(300)
        call.end()
        call.end()
        Thread.sleep(500)
        println("start twice: ${count("/api/voice/session")} sessions asked for, ${socketsOpened.get()} sockets opened, ${ends().size} ends sent, microphone opened ${audio.starts.get()}x, let go ${audio.stops.get()}x")
        noCrash()
    }
}
