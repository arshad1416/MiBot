package app.mibot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64

/*
 * A voice call with a bot, the way the web app runs one (web/src/voice/call.ts): a small voice agent
 * at the voice service speaks as the bot, and hands real work to the bot's own agent through the hub
 * (`send_task`). When that work lands in the conversation, the voice agent is told, and says so.
 *
 * This file is the call's logic. The microphone and the speaker are behind [VoiceAudio], so the same
 * code runs on a phone and in a test.
 */

/** The microphone and speaker of whatever the call runs on. Sound is 16-bit mono PCM, little-endian. */
interface VoiceAudio {
    /** Open the microphone and hand each chunk to `onPcm`. Throws when there is no microphone to open. */
    fun start(sampleRate: Int, onPcm: (ByteArray) -> Unit)
    /** Queue sound for the speaker. */
    fun play(pcm: ByteArray)
    /** Drop whatever has not been played yet: the person started talking over the bot. */
    fun clearPlayback()
    /** Sound is still queued or playing. */
    val playing: Boolean
    /** How loud the microphone and the speaker are right now, 0 to 1, for the moving bars. */
    val micLevel: Float
    val outLevel: Float
    fun stop()
}

data class Caption(val id: String, val role: String, val text: String, val final: Boolean)

data class VoiceState(
    val status: Status = Status.Idle,
    val botId: String? = null,
    val muted: Boolean = false,
    /** The bot's voice is playing. */
    val speaking: Boolean = false,
    /** The service hears the person. */
    val listening: Boolean = false,
    /** An answer is being made but nothing is audible yet. */
    val thinking: Boolean = false,
    /** Work handed to the bot's agent that has not landed yet, by its request. */
    val working: List<String> = emptyList(),
    val captions: List<Caption> = emptyList(),
    val error: String? = null,
    val startedAt: Long? = null,
) {
    enum class Status { Idle, Connecting, Live, Error }
    val active get() = status == Status.Connecting || status == Status.Live
}

private class PendingTask(val seq: Int, val request: String, val since: Long, val startedBusy: Boolean) {
    var sawRunning = false
    var awaitingNoted = false
    var doneSince: Long? = null
}

private fun clip(s: String, n: Int) = if (s.length > n) s.take(n - 1) + "…" else s
private fun JsonObject.str(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
private fun JsonObject.arr(key: String): List<JsonObject> = (get(key) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

class VoiceCall(
    private val session: HubSession,
    val botId: String,
    private val audio: VoiceAudio,
    parent: CoroutineScope,
    private val http: OkHttpClient = HubClient.defaultHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val client get() = session.client
    // Everything the call remembers is touched from one lane only, so the logic reads like the single-threaded original.
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.Default.limitedParallelism(1))
    private val _state = MutableStateFlow(VoiceState(VoiceState.Status.Connecting, botId))
    val state: StateFlow<VoiceState> = _state

    @Volatile var ended = false
        private set
    private var ws: WebSocket? = null
    private var sess: VoiceSession? = null
    private var ready = false
    private var responseActive = false
    private var responseSpoke = false
    private var responseId: String? = null
    /** The answer the person talked over; sound for it that arrives late is dropped. */
    private var interruptedId: String? = null
    private var userSpeaking = false
    private val inflight = LinkedHashMap<String, Pair<String, kotlinx.coroutines.Deferred<String>>>()
    private val handledCalls = HashSet<String>()
    private val tasks = ArrayList<PendingTask>()
    private val notes = ArrayList<String>()
    private var flushWaiting = false
    private var checkJob: Job? = null
    private val hubClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun set(change: (VoiceState) -> VoiceState) = _state.update(change)

    fun start() {
        scope.launch {
            try {
                audio.start(SAMPLE_RATE) { pcm -> sendAudio(pcm) }
            } catch (e: Exception) {
                end(e.message?.takeIf { it.isNotBlank() } ?: "The microphone could not be opened.")
                return@launch
            }
            try {
                // Hanging up while this is on its way must still close the call the hub has just opened.
                val s = withContext(NonCancellable) {
                    client.voiceSession(botId).also { sess = it; if (ended) closeOnHub("Cancelled") }
                }
                if (ended) return@launch
                connect(s)
            } catch (e: HubException) {
                end(e.message ?: "Could not start the call")
            }
        }
        // The speaker's state, for the bars and the "speaking" flag.
        scope.launch {
            while (!ended) {
                val playing = audio.playing
                if (playing != _state.value.speaking) set { it.copy(speaking = playing, thinking = if (playing) false else it.thinking) }
                delay(80)
            }
        }
    }

    private fun connect(s: VoiceSession) {
        // The secret travels the way a browser has to send it: as a sub-protocol name.
        val req = try {
            Request.Builder().url(s.websocketUrl).header("Sec-WebSocket-Protocol", "xai-client-secret.${s.clientSecret}").build()
        } catch (e: IllegalArgumentException) {
            end("Couldn’t connect the call")
            return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                send(buildJsonObject { put("type", "session.update"); put("session", s.session) })
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = runCatching { HubJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
                scope.launch { handle(ev) }
            }
            // The service closing first: answer its close, or onClosed never comes.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); dropped(reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(reason)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped("")
        })
        scope.launch {
            delay(CONNECT_MS)
            if (!ready) end("Couldn’t connect the call")
        }
    }

    private fun dropped(reason: String) {
        scope.launch { if (!ended) end(if (ready) "Call dropped" else "Couldn’t connect the call" + if (reason.isNotBlank()) " ($reason)" else "") }
    }

    private fun send(ev: JsonObject) { ws?.send(ev.toString()) }

    private fun sendAudio(pcm: ByteArray) {
        if (!ready || ended || _state.value.muted) return
        send(buildJsonObject { put("type", "input_audio_buffer.append"); put("audio", Base64.getEncoder().encodeToString(pcm)) })
    }

    fun setMuted(muted: Boolean) {
        scope.launch {
            if (muted && ready) send(buildJsonObject { put("type", "input_audio_buffer.clear") })
            set { it.copy(muted = muted, listening = if (muted) false else it.listening) }
        }
    }

    // ---------- what the voice service sends ----------

    private fun handle(ev: JsonObject) {
        if (ended) return
        when (ev.str("type")) {
            "session.updated" -> if (!ready) {
                ready = true
                set { it.copy(status = VoiceState.Status.Live, startedAt = clock()) }
                watchConversation()
                send(buildJsonObject { put("type", "response.create") }) // the bot greets first
            }

            "input_audio_buffer.speech_started" -> {
                userSpeaking = true
                set { it.copy(listening = true) }
                // The person talks over the bot: stop talking and listen.
                if (audio.playing) audio.clearPlayback()
                if (responseActive) {
                    interruptedId = responseId
                    send(buildJsonObject { put("type", "response.cancel") })
                }
            }
            "input_audio_buffer.speech_stopped", "input_audio_buffer.committed" -> {
                userSpeaking = false
                set { it.copy(listening = false) }
                flushNotes()
            }

            "conversation.item.added", "conversation.item.created" -> {
                val item = ev.obj("item") ?: return
                val content = item.arr("content")
                if (item.str("role") == "user" && content.any { it.str("type") == "input_audio" }) {
                    val t = content.firstNotNullOfOrNull { it.str("transcript")?.takeIf { s -> s.isNotEmpty() } }
                    caption(item.str("id"), "user", t.orEmpty(), t != null, append = false)
                }
            }
            // One service sends the whole line so far in `.updated`; another sends the new part in `.delta`.
            "conversation.item.input_audio_transcription.updated" -> caption(ev.str("item_id"), "user", ev.str("transcript").orEmpty(), false, append = false)
            "conversation.item.input_audio_transcription.delta" -> caption(ev.str("item_id"), "user", ev.str("delta").orEmpty(), false, append = true)
            "conversation.item.input_audio_transcription.completed" -> caption(ev.str("item_id"), "user", ev.str("transcript").orEmpty(), true, append = false)

            "response.created" -> {
                responseActive = true
                responseSpoke = false
                responseId = ev.obj("response")?.str("id")
                set { it.copy(thinking = true) }
            }
            "response.output_item.added" -> ev.obj("item")?.takeIf { it.str("type") == "message" }?.let { caption(it.str("id"), "assistant", "", false, append = false) }
            "response.output_audio_transcript.delta", "response.audio_transcript.delta", "response.output_text.delta", "response.text.delta" -> {
                responseSpoke = true
                caption(ev.str("item_id"), "assistant", ev.str("delta").orEmpty(), false, append = true)
            }
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                ev.str("transcript")?.takeIf { it.isNotEmpty() }?.let { caption(ev.str("item_id"), "assistant", it, true, append = false) }
            "response.output_audio.delta", "response.audio.delta" -> {
                val delta = ev.str("delta")
                if (!delta.isNullOrEmpty() && !(interruptedId != null && ev.str("response_id") == interruptedId)) {
                    responseSpoke = true
                    runCatching { Base64.getDecoder().decode(delta) }.getOrNull()?.let { audio.play(it) }
                }
            }

            "response.function_call_arguments.done" -> startTool(ev.str("call_id"), ev.str("name"), ev.str("arguments"))
            "response.done" -> {
                responseActive = false
                set { it.copy(thinking = false) }
                for (o in ev.obj("response")?.arr("output").orEmpty()) if (o.str("type") == "function_call") startTool(o.str("call_id"), o.str("name"), o.str("arguments"))
                if (inflight.isNotEmpty()) { val spoke = responseSpoke; scope.launch { finishTools(spoke) } } else flushNotes()
            }

            "error" -> {
                val err = ev.obj("error")
                val code = err?.str("code") ?: ev.str("code").orEmpty()
                val message = err?.str("message").orEmpty()
                if (code.contains("cancel", ignoreCase = true) || message.contains("no active response", ignoreCase = true)) return
                if (!ready) end(message.ifBlank { "Voice service error" })
            }
        }
    }

    private fun caption(id: String?, role: String, text: String, final: Boolean, append: Boolean) {
        if (id == null) return
        set { st ->
            val i = st.captions.indexOfFirst { it.id == id }
            if (i < 0) return@set st.copy(captions = (st.captions + Caption(id, role, text, final)).takeLast(200))
            val prev = st.captions[i]
            val next = prev.copy(text = if (append) prev.text + text else text.ifEmpty { prev.text }, final = final || prev.final)
            st.copy(captions = st.captions.toMutableList().also { it[i] = next })
        }
    }

    private fun transcript() = _state.value.captions.filter { it.text.isNotBlank() }.map { VoiceLine(it.role, it.text.trim()) }

    // ---------- the voice agent's tools, answered by the hub ----------

    private fun startTool(callId: String?, name: String?, rawArgs: String?) {
        if (callId == null || name == null || !handledCalls.add(callId)) return
        val args = runCatching { HubJson.parseToJsonElement(rawArgs.orEmpty()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        val request = args.str("request").orEmpty()
        val answer = scope.async {
            if (name == "end_the_call") return@async "Ending the call."
            // Show "working" at once; the task itself is tracked once the hub has answered.
            if (name == "send_task") set { it.copy(working = it.working + request) }
            try {
                val r = client.voiceTool(botId, sess!!.callId, name, args)
                if (name == "send_task") {
                    if (r.taskSeq != null) addTask(r.taskSeq, request) else syncWorking()
                }
                r.output
            } catch (e: HubException) {
                if (name == "send_task") syncWorking()
                "Tool error: ${e.message}. Tell the user it didn't go through."
            }
        }
        inflight[callId] = name to answer
    }

    private suspend fun finishTools(spoke: Boolean) {
        val batch = inflight.entries.map { it.key to it.value }
        inflight.clear()
        val outputs = batch.map { it.second.second }.awaitAll()
        if (ended) return
        // Every answer first, then one request to go on: the service wants them in that order.
        batch.forEachIndexed { i, (callId, _) ->
            send(buildJsonObject {
                put("type", "conversation.item.create")
                put("item", buildJsonObject { put("type", "function_call_output"); put("call_id", callId); put("output", outputs[i]) })
            })
        }
        if (batch.any { it.second.first == "end_the_call" }) {
            whenQuiet(8_000)
            end()
            return
        }
        // Work the bot already said it is on needs no second sentence; a tool that read something does.
        val needsReply = !spoke || batch.any { it.second.first != "send_task" }
        if (needsReply) {
            whenQuiet(8_000) // not over the sentence being spoken
            if (!ended && !responseActive && inflight.isEmpty()) send(buildJsonObject { put("type", "response.create") })
        } else flushNotes()
    }

    private suspend fun whenQuiet(maxMs: Long) {
        val until = clock() + maxMs
        while (!ended && audio.playing && clock() < until) delay(50)
    }

    // ---------- telling the voice agent when handed-off work lands ----------

    private fun addTask(seq: Int, request: String) {
        tasks += PendingTask(seq, request, clock(), startedBusy = session.state.value.live[botId]?.isRunning == true)
        syncWorking()
        scheduleCheck(800)
    }

    private fun syncWorking() = set { it.copy(working = tasks.map { t -> t.request }) }

    private fun watchConversation() {
        scope.launch {
            session.state.map { it.live[botId] to it.transcripts[botId] }.distinctUntilChanged().collect { (live, _) ->
                if (tasks.isEmpty()) return@collect
                if (live?.isRunning == true) tasks.forEach { it.sawRunning = true }
                scheduleCheck(400)
            }
        }
    }

    private fun scheduleCheck(ms: Long) {
        checkJob?.cancel()
        checkJob = scope.launch { delay(ms); checkTasks() }
    }

    private fun checkTasks() {
        if (ended || tasks.isEmpty()) return
        val st = session.state.value
        val live = st.live[botId]
        val rows = st.transcripts[botId]?.rows.orEmpty()
        val now = clock()
        var recheck = false
        for (task in tasks.toList()) {
            if (live?.isRunning == true) task.sawRunning = true
            val after = rows.filter { it.seq > task.seq }
            val awaiting = live?.awaiting
            if (awaiting != null) {
                if (!task.awaitingNoted) {
                    task.awaitingNoted = true
                    queueNote("The work you handed off (\"${clip(task.request, 160)}\") is paused and needs ${firstName()}'s OK in the chat: ${awaiting.reason}. Tell them it's waiting for their approval on screen.")
                }
                task.doneSince = null
                continue
            }
            task.awaitingNoted = false
            val replies = after.filter { r -> (r.body as? MessageBody)?.let { !it.isUser && !it.streaming } == true }
            val streaming = after.any { (it.body as? MessageBody)?.streaming == true }
            val looksDone = live?.isRunning != true && !streaming && (replies.isNotEmpty() || (task.sawRunning && !task.startedBusy))
            if (!looksDone) { task.doneSince = null; continue }
            if (now - task.since <= 1_500) { recheck = true; continue } // too soon to trust; nothing else may change to wake us

            // Stay finished for a moment: two turns queued back to back look idle in between.
            val doneSince = task.doneSince
            if (doneSince == null) { task.doneSince = now; recheck = true; continue }
            if (now - doneSince < 1_200) { recheck = true; continue }
            tasks.remove(task)
            queueNote(landedNote(task.request, replies, after))
        }
        syncWorking()
        if (recheck) scheduleCheck(700)
    }

    private fun firstName() = session.state.value.user?.name.orEmpty().trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() } ?: "the user"

    private fun queueNote(text: String) {
        notes += text
        flushNotes()
    }

    private fun flushNotes() {
        if (!ready || ended || notes.isEmpty()) return
        if (responseActive || userSpeaking || inflight.isNotEmpty()) return // tried again when the answer is done or the person stops talking
        if (audio.playing) {
            if (!flushWaiting) {
                flushWaiting = true
                scope.launch { whenQuiet(60_000); flushWaiting = false; flushNotes() }
            }
            return
        }
        val text = notes.joinToString("\n\n") { "[work_landed] $it" }
        notes.clear()
        // Sent as the person's turn: the voice model passes these on, and ignores a system turn in the middle of a call.
        send(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message"); put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "input_text"); put("text", "$text\n\n(Automatic note from your text agent, not spoken by ${firstName()}. Tell them the outcome now, briefly.)") })
                })
            })
        })
        send(buildJsonObject { put("type", "response.create") })
    }

    // ---------- the end ----------

    /** Hang up. With `error`, the call ended because something went wrong, and the state says what. */
    fun end(error: String? = null) {
        if (ended) return
        ended = true
        runCatching { ws?.close(1000, "call ended") }
        runCatching { audio.stop() }
        closeOnHub(error)
        _state.value = if (error != null) VoiceState(VoiceState.Status.Error, botId, error = error) else VoiceState()
        scope.cancel()
    }

    /** Tell the hub the call is over, with what was said. It outlives the call's own scope. */
    private fun closeOnHub(error: String?) {
        val s = sess ?: return
        if (!hubClosed.compareAndSet(false, true)) return
        val lines = transcript()
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch { withContext(NonCancellable) { runCatching { client.voiceEnd(botId, s.callId, lines, error) } } }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        private const val CONNECT_MS = 15_000L

        internal fun landedNote(request: String, replies: List<Row>, after: List<Row>): String {
            val text = replies.joinToString("\n\n") { (it.body as MessageBody).text }.trim()
            val waiting = after.mapNotNull { it.body as? CardBody }.filter { it.status == "pending" }.map { b ->
                when (b) {
                    is ApprovalCard -> b.title
                    is QuestionCard -> "a question: ${b.question}"
                    is ConnectCard -> "connecting ${b.toolkitName}"
                    is DraftCard -> "a draft to review"
                }
            }
            return "The work you handed off just finished. Request: \"${clip(request, 200)}\". " +
                (if (text.isNotEmpty()) "Your text agent replied:\n\"\"\"\n${clip(text, 1800)}\n\"\"\"" else "It finished without a written reply.") +
                (if (waiting.isNotEmpty()) "\nStill waiting on the user in the chat: ${waiting.joinToString("; ")}." else "")
        }
    }
}
