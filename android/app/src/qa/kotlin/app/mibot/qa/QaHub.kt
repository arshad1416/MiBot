package app.mibot.qa

import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

/** Opt-in QA transport. Every request terminates here; no socket or real account is used. */
object QaHub {
    @JvmStatic fun client(): HubClient = HubClient(HubAccount("http://qa.invalid", "fixture-only"),
        OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val stream = path == "/api/events"
            val fail = path.endsWith("/messages")
            android.util.Log.i("MiBotQA", "${chain.request().method} $path fixture=${if(fail) 503 else 200}")
            val json = when {
                path == "/api/auth/me" -> """{"user":{"id":"qa","name":"QA User","email":"qa@example.invalid"}}"""
                path == "/api/bots" -> """{"bots":[{"id":"qa-bot","name":"QA Bot","isPrimary":true,"lastPreview":"Offline fixture: no real agent","lastActivityAt":1794540000000},{"id":"qa-long","name":"A very long bot name for layout testing with words and symbols ★","label":"Long label / draft / waiting","lastPreview":"A long preview for layout testing and scrolling safely"}],"live":[]}"""
                path.endsWith("/rows") -> """{"generation":0,"rows":[{"botId":"qa-bot","seq":1,"kind":"message","createdAt":1794540000000,"body":{"type":"message","role":"assistant","text":"QA only. Try replying to this message. Sends deliberately fail so you can verify your draft survives."}},{"botId":"qa-bot","seq":2,"kind":"message","createdAt":1794540001000,"body":{"type":"message","role":"user","text":"Test text with emoji 🚗 and العربية and a long paragraph. No real bot is connected."}}]}"""
                path == "/api/computer" -> """{"computer":{"status":"off"}}"""
                path == "/api/approvals" -> """{"approvals":[],"serverTime":1794540000000}"""
                path.endsWith("/routines") -> """{"routines":[]}"""
                path.contains("/files") || path.contains("/library") -> """{"files":[],"path":"","status":"ok"}"""
                path.contains("/apps") -> """{"apps":[],"connectedApps":[]}"""
                fail -> """{"error":"QA simulated send failure. Your draft should remain."}"""
                else -> "{}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if(fail) 503 else 200).message("QA")
                .body(if(stream) eventBody() else json.toResponseBody("application/json".toMediaType())).build()
        }.build())

    private fun eventBody(): ResponseBody = object : ResponseBody() {
        override fun contentType() = "text/event-stream".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = object : Source {
            var first = true
            @Volatile var closed = false
            override fun read(sink: Buffer, byteCount: Long): Long {
                if(closed) return -1
                if(first) { first = false; return sink.writeUtf8("data: {\"type\":\"hello\",\"serverTime\":1794540000000}\n\n").size }
                Thread.sleep(1000)
                if(closed) return -1
                sink.writeUtf8(": ping\n\n"); return 8
            }
            override fun timeout() = Timeout.NONE
            override fun close() { closed = true }
        }.buffer()
    }
}
