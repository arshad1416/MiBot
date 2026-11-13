package app.mibot.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The hub said no, or could not be reached. `message` is fit to show; `status` is 0 when there was no answer at all. */
class HubException(val status: Int, message: String) : IOException(message) {
    /** The sign-in is no longer good: the device has to be added again. */
    val signedOut get() = status == 401
}

/** Where the hub is and the sign-in this device holds for it. */
data class HubAccount(val url: String, val token: String)

/** A pairing link (`https://hub/pair#CODE`), as the QR code in Settings › Machines carries it. */
data class PairLink(val hubUrl: String, val code: String)

@Serializable data class BotsResponse(val bots: List<Bot> = emptyList(), val live: List<LiveState> = emptyList())
@Serializable data class RowsResponse(val generation: Int = 0, @Serializable(with = RowListSerializer::class) val rows: List<Row> = emptyList())
@Serializable data class MeResponse(val user: User? = null)
@Serializable data class RoutinesResponse(val routines: List<Routine> = emptyList())
@Serializable data class ComputerResponse(val computer: ComputerState = ComputerState())
@Serializable data class FilesResponse(val status: String = "ok", val path: String = "", val files: List<LibraryFile> = emptyList(), val error: String? = null)
@Serializable data class AppsResponse(val apps: List<Toolkit> = emptyList(), val connectedApps: List<Toolkit> = emptyList(), val categories: List<String> = emptyList(), val total: Int = 0)
/** What starting a connection led to: a sign-in page to open, a key to ask for, or it is already done. */
@Serializable data class ConnectResult(val status: String? = null, val redirectUrl: String? = null, val keyPrompt: String? = null)
@Serializable data class BotResponse(val bot: Bot)
@Serializable data class UrlResponse(val url: String = "")
@Serializable private data class TextResponse(val text: String = "")
@Serializable data class ApprovalsResponse(val approvals: List<Approval> = emptyList(), val serverTime: Long = 0)
@Serializable private data class DecisionResponse(val approval: Approval? = null, val error: String? = null)

/** What answering an approval led to: `approval` as it now stands, and `error` when another answer, or the clock, got there first. */
data class Decision(val approval: Approval, val error: String? = null)

/** What a voice call needs to open its line to the voice service: a short-lived secret, the address, and the session settings to send first. */
@Serializable data class VoiceSession(val callId: String, val clientSecret: String, val websocketUrl: String, val sampleRate: Int = 24_000, val session: JsonObject = JsonObject(emptyMap()))
/** What one of the voice agent's tools answered. `taskSeq`: the row of the work it handed to the bot. */
@Serializable data class VoiceToolResult(val output: String = "", val taskSeq: Int? = null)

private const val COOKIE = "grokbot_session"
private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * The hub's HTTP API for one signed-in device. Every call is safe to make from the main thread: the
 * work happens on the IO dispatcher. A call that fails throws [HubException].
 */
class HubClient(val account: HubAccount, base: OkHttpClient = defaultHttp, private val userAgent: String = "MiBot-Android") {
    private val http = base
    /** The event stream is quiet for up to 20 s between pings; a longer silence means the line is dead. */
    private val streamHttp = base.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
    private val root: HttpUrl = account.url.toHttpUrlOrNull() ?: throw HubException(0, "That is not a web address.")

    /** The value of the Cookie header that signs a request in, for a web view or a download. */
    val cookie get() = "$COOKIE=${account.token}"

    fun url(path: String): String = root.resolve(path)?.toString() ?: throw HubException(0, "Bad address: $path")

    private fun request(method: String, path: String, body: JsonObject? = null): Request {
        val payload: RequestBody? = body?.toString()?.toRequestBody(JSON_TYPE) ?: if (method == "GET") null else ByteArray(0).toRequestBody(null)
        return Request.Builder().url(url("/api$path")).method(method, payload)
            .header("Cookie", cookie).header("User-Agent", userAgent).build()
    }

    private suspend fun <T> call(ser: KSerializer<T>, method: String, path: String, body: JsonObject? = null): T = withContext(Dispatchers.IO) {
        execute(http, request(method, path, body)) { HubJson.decodeFromString(ser, it.body?.string().orEmpty()) }
    }

    private suspend fun send(method: String, path: String, body: JsonObject? = null) {
        withContext(Dispatchers.IO) { execute(http, request(method, path, body)) { } }
    }

    private fun json(build: JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    suspend fun me(): User? = call(MeResponse.serializer(), "GET", "/auth/me").user
    suspend fun signOut() = send("POST", "/auth/logout")

    suspend fun bots() = call(BotsResponse.serializer(), "GET", "/bots")
    suspend fun createBot(name: String, shape: String, color: String) =
        call(BotResponse.serializer(), "POST", "/bots", json { put("name", name); put("avatarShape", shape); put("avatarColor", color); put("mode", "personal") }).bot
    suspend fun updateBot(id: String, patch: JsonObject) = call(BotResponse.serializer(), "PATCH", "/bots/$id", patch).bot
    suspend fun deleteBot(id: String) = send("DELETE", "/bots/$id")
    suspend fun makePrimary(id: String) = send("POST", "/bots/$id/primary")
    suspend fun markRead(id: String) = send("POST", "/bots/$id/read")

    suspend fun rows(botId: String, beforeSeq: Int? = null) =
        call(RowsResponse.serializer(), "GET", "/bots/$botId/rows" + (beforeSeq?.let { "?beforeSeq=$it" } ?: ""))
    suspend fun sendMessage(botId: String, text: String) = send("POST", "/bots/$botId/messages", json { put("text", text) })
    suspend fun interrupt(botId: String) = send("POST", "/bots/$botId/interrupt")
    suspend fun newConversation(botId: String) = send("POST", "/bots/$botId/fresh")

    /** Approvals waiting for an answer; with `sinceMs`, also those answered or expired since then. */
    suspend fun approvals(sinceMs: Long? = null) = call(ApprovalsResponse.serializer(), "GET", "/approvals" + (sinceMs?.let { "?since=$it" } ?: ""))

    /**
     * Answer an approval. `decision`: once | always | deny. When another device answered first, or it
     * ran out, the hub says so (409) and the result carries the answer that stood.
     */
    suspend fun decide(approvalId: String, decision: String): Decision = withContext(Dispatchers.IO) {
        val req = request("POST", "/approvals/$approvalId", json { put("decision", decision) })
        val res = try { http.newCall(req).execute() } catch (e: IOException) { throw HubException(0, unreachable(e)) }
        res.use {
            val text = it.body?.string().orEmpty()
            if (it.code != 409 && !it.isSuccessful) throw failure(it.code, text)
            val read = runCatching { HubJson.decodeFromString(DecisionResponse.serializer(), text) }.getOrNull()
            val approval = read?.approval ?: throw HubException(it.code, "The hub sent something this app does not understand.")
            Decision(approval, if (it.code == 409) read.error ?: "This request was already answered." else null)
        }
    }
    suspend fun answer(botId: String, seq: Int, answer: String) = send("POST", "/bots/$botId/cards/$seq/answer", json { put("answer", answer) })
    suspend fun dismiss(botId: String, seq: Int) = send("POST", "/bots/$botId/cards/$seq/dismiss")

    suspend fun routines(botId: String) = call(RoutinesResponse.serializer(), "GET", "/bots/$botId/routines").routines
    suspend fun setRoutine(id: String, enabled: Boolean) = send("PATCH", "/routines/$id", json { put("enabled", enabled) })
    suspend fun runRoutine(id: String) = send("POST", "/routines/$id/run")

    suspend fun computer() = call(ComputerResponse.serializer(), "GET", "/computer").computer
    suspend fun wakeComputer() = call(ComputerResponse.serializer(), "POST", "/computer/ensure").computer
    /** A picture of the bot's screen, or null when it has none right now. */
    suspend fun screenshot(botId: String): ByteArray? = withContext(Dispatchers.IO) {
        execute(http, request("GET", "/bots/$botId/screenshot")) { if (it.code == 204) null else it.body?.bytes() }
    }

    suspend fun files(botId: String, path: String = "") =
        call(FilesResponse.serializer(), "GET", "/bots/$botId/files?path=${java.net.URLEncoder.encode(path, "UTF-8")}")
    /** A link the file can be fetched from; relative links are on the hub and need [cookie]. */
    suspend fun fileUrl(botId: String, path: String): String =
        call(UrlResponse.serializer(), "POST", "/bots/$botId/files/download", json { put("path", path) }).url.let { if (it.startsWith("/")) url(it) else it }

    suspend fun apps(search: String = "") = call(AppsResponse.serializer(), "GET", "/apps?search=${java.net.URLEncoder.encode(search, "UTF-8")}")
    suspend fun connectApp(slug: String, botId: String? = null, seq: Int? = null, apiKey: String? = null) =
        call(ConnectResult.serializer(), "POST", "/apps/$slug/connect", json {
            botId?.let { put("botId", it) }; seq?.let { put("seq", it) }; apiKey?.let { put("apiKey", it) }
        })
    suspend fun setAppForBot(slug: String, botId: String, enabled: Boolean) = send("PUT", "/apps/$slug/bots/$botId", json { put("enabled", enabled) })
    suspend fun disconnectApp(slug: String) = send("DELETE", "/apps/$slug")

    suspend fun voiceSession(botId: String) = call(VoiceSession.serializer(), "POST", "/voice/session", json { put("botId", botId) })
    suspend fun voiceTool(botId: String, callId: String, name: String, args: JsonObject) =
        call(VoiceToolResult.serializer(), "POST", "/voice/tool", json { put("botId", botId); put("callId", callId); put("name", name); put("args", args) })
    suspend fun voiceEnd(botId: String, callId: String, transcript: List<VoiceLine>, error: String? = null) = send("POST", "/voice/end", json {
        put("botId", botId); put("callId", callId); error?.let { put("error", it) }
        put("transcript", HubJson.encodeToJsonElement(ListSerializer(VoiceLine.serializer()), transcript))
    })

    /** Turn a short recording into text. `language`: a two-letter code, or null to let the service tell. */
    suspend fun transcribe(audio: ByteArray, fileName: String, mime: String, language: String? = null): String = withContext(Dispatchers.IO) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (language != null && language != "auto") form.addFormDataPart("language", language.take(2))
        form.addFormDataPart("file", fileName, audio.toRequestBody(mime.toMediaType()))
        val req = Request.Builder().url(url("/api/voice/stt")).post(form.build()).header("Cookie", cookie).header("User-Agent", userAgent).build()
        execute(http, req) { HubJson.decodeFromString(TextResponse.serializer(), it.body?.string().orEmpty()).text }
    }

    /**
     * The hub's live events, until the line breaks (then the flow ends with a [HubException]). The
     * first thing it emits is `Hello`. Stopping the collection drops the connection at once.
     */
    fun events(): Flow<ServerEvent> = callbackFlow {
        val call = streamHttp.newCall(request("GET", "/events").newBuilder().header("Accept", "text/event-stream").build())
        launch(Dispatchers.IO) {
            val end: HubException = try {
                call.execute().use { res ->
                    if (!res.isSuccessful) throw failure(res)
                    val source = res.body?.source() ?: throw HubException(0, "The hub sent nothing.")
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) data.append(line.removePrefix("data:").trimStart())
                        else if (line.isEmpty() && data.isNotEmpty()) {
                            val ev = runCatching { HubJson.decodeFromString(ServerEvent.serializer(), data.toString()) }.getOrNull()
                            data.clear()
                            if (ev != null) send(ev)
                        }
                    }
                }
                HubException(0, "The hub closed the connection.")
            } catch (e: HubException) { e } catch (e: IOException) { HubException(0, unreachable(e)) }
            close(end)
        }
        awaitClose { call.cancel() }
    }

    companion object {
        val defaultHttp: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
            .build()

        /** What a typed address stands for: `mac-mini:8787` is `http://mac-mini:8787`. */
        fun normalizeUrl(text: String): String? {
            val t = text.trim()
            val m = Regex("^(https?)://(.*)$", RegexOption.IGNORE_CASE).find(t)
            val scheme = m?.groupValues?.get(1)?.lowercase() ?: "http"
            val rest = (m?.groupValues?.get(2) ?: t).trimEnd('/')
            // A slip in the scheme ("http:/mini", "http//mini", "htp://mini") would otherwise make "http" or "htp" the host.
            if (rest.isEmpty() || Regex("^[^/]*//").containsMatchIn(rest)) return null
            val hostPort = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            // A hub's address never carries a name and password ("user:pass@host").
            if (hostPort.isEmpty() || hostPort.any { it.isWhitespace() || it == '\\' || it == '@' }) return null
            val afterHost = if (hostPort.startsWith("[")) hostPort.substringAfter("]", "?") else hostPort
            if (':' in afterHost && !Regex("^[^:]*:\\d{1,5}$").matches(afterHost)) return null
            val u = "$scheme://$hostPort".toHttpUrlOrNull() ?: return null
            return u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        }

        /** Read a pairing link or the text of its QR code. Null when it is something else. */
        fun parsePairLink(text: String): PairLink? {
            val m = Regex("^(https?://[^/#?\\s]+)/pair/?#([A-Za-z0-9-]{4,20})$", RegexOption.IGNORE_CASE).find(text.trim()) ?: return null
            val hub = normalizeUrl(m.groupValues[1]) ?: return null
            return PairLink(hub, normalizeCode(m.groupValues[2]))
        }

        /** A code as typed: case and the dash in the middle do not matter. */
        fun normalizeCode(text: String) = text.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }

        /**
         * Hand in a one-time code from Settings › Machines and get this device signed in. The hub
         * answers with its session as a cookie; that is the token kept from here on.
         */
        suspend fun pair(hubUrl: String, code: String, http: OkHttpClient = defaultHttp, userAgent: String = "MiBot-Android"): HubAccount = withContext(Dispatchers.IO) {
            val base = normalizeUrl(hubUrl)?.toHttpUrlOrNull() ?: throw HubException(0, "That is not a web address.")
            val body = buildJsonObject { put("code", normalizeCode(code)) }.toString().toRequestBody(JSON_TYPE)
            val req = Request.Builder().url(base.resolve("/api/pair/sign-in")!!).post(body).header("User-Agent", userAgent).build()
            execute(http, req) { res ->
                val token = res.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                    c.substringBefore(';').trim().takeIf { it.startsWith("$COOKIE=") }?.removePrefix("$COOKIE=")
                }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                if (token.isNullOrEmpty()) throw HubException(res.code, "This does not look like a MiBot hub.")
                HubAccount(base.toString().trimEnd('/'), token)
            }
        }

        private fun unreachable(e: IOException) = when (e) {
            is java.net.UnknownHostException -> "The hub's address was not found. Is this phone on the same private network?"
            is java.net.SocketTimeoutException -> "The hub did not answer in time."
            is java.net.ConnectException -> "Could not reach the hub. Is it running, and is this phone on the same private network?"
            is javax.net.ssl.SSLException -> "The hub's secure connection could not be set up."
            else -> "Could not reach the hub."
        }

        private fun failure(res: Response): HubException = failure(res.code, runCatching { res.body?.string() }.getOrNull().orEmpty())

        private fun failure(code: Int, text: String): HubException {
            val said = runCatching { HubJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val message = when {
                code == 401 -> "This device is no longer signed in."
                !said.isNullOrBlank() && said != "not found" -> said
                code == 404 -> "The hub no longer has that."
                else -> "The hub answered with an error ($code)."
            }
            return HubException(code, message)
        }

        private fun <T> execute(http: OkHttpClient, req: Request, read: (Response) -> T): T {
            val res = try { http.newCall(req).execute() } catch (e: IOException) { throw HubException(0, unreachable(e)) }
            return res.use {
                if (!it.isSuccessful) throw failure(it)
                try { read(it) } catch (e: HubException) { throw e } catch (e: IOException) { throw HubException(0, unreachable(e)) } catch (e: IllegalArgumentException) {
                    throw HubException(it.code, "The hub sent something this app does not understand.")
                }
            }
        }
    }
}
