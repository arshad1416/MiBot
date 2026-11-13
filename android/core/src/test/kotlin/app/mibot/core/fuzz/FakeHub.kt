package app.mibot.core.fuzz

import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A stand-in for the hub on 127.0.0.1, for driving HubClient and HubSession without a network: it
 * answers the API paths with whatever the test set, and holds the event stream open so the test can
 * push lines down it and cut it when it likes. Nothing here reaches outside this machine.
 */
internal class FakeHub : AutoCloseable {
    class Reply(val status: Int = 200, val body: String = "{}", val delayMs: Long = 0, val headers: Map<String, String> = emptyMap())

    private val server = run {
        System.setProperty("sun.net.httpserver.nodelay", "true") // or each small answer waits 40 ms on Nagle
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "fake-hub").apply { isDaemon = true } }
    private val streams = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()

    /** `METHOD /path` (no query) -> what to answer. A function, so a test can change its mind between calls. */
    val routes = ConcurrentHashMap<String, (HttpExchange) -> Reply>()
    /** Every request that came in, as `METHOD /path?query`. */
    val log = CopyOnWriteArrayList<String>()
    /** What `/api/events` answers before it starts streaming. 200 streams; anything else is sent and closed. */
    @Volatile var eventsStatus = 200
    @Volatile var fallback: (HttpExchange) -> Reply = { Reply(404, """{"error":"not found"}""") }

    val url get() = "http://127.0.0.1:${server.address.port}"
    val openStreams get() = streams.size

    init {
        server.executor = pool
        server.createContext("/") { ex ->
            try {
                val path = ex.requestURI.rawPath
                log += "${ex.requestMethod} ${ex.requestURI}"
                ex.requestBody.readBytes()
                if (path == "/api/events") stream(ex) else {
                    val reply = (routes["${ex.requestMethod} $path"] ?: fallback)(ex)
                    if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                    val bytes = reply.body.toByteArray()
                    for ((k, v) in reply.headers) ex.responseHeaders.add(k, v)
                    if (reply.status == 204) ex.sendResponseHeaders(204, -1) else {
                        ex.sendResponseHeaders(reply.status, bytes.size.toLong().coerceAtLeast(0).let { if (it == 0L) -1 else it })
                        if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
                    }
                }
            } catch (_: Exception) {
            } finally {
                ex.close()
            }
        }
        server.start()
    }

    private fun stream(ex: HttpExchange) {
        if (eventsStatus != 200) {
            val b = """{"error":"no"}""".toByteArray()
            ex.sendResponseHeaders(eventsStatus, b.size.toLong())
            ex.responseBody.use { it.write(b) }
            return
        }
        val q = LinkedBlockingQueue<String>()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        val out: OutputStream = ex.responseBody
        out.write("data: {\"type\":\"hello\",\"serverTime\":1}\n\n".toByteArray()); out.flush()
        streams += q
        try {
            while (true) {
                // A comment line every so often, as the hub's pings do: it is also how this side learns the phone hung up.
                val frame = q.poll(100, TimeUnit.MILLISECONDS) ?: ": ping\n\n"
                if (frame === CUT) break
                out.write(frame.toByteArray()); out.flush()
            }
        } finally {
            streams -= q
            runCatching { out.close() }
        }
    }

    /** Push raw bytes down every open event stream. */
    fun raw(frame: String) { for (q in streams) q.put(frame) }
    /** Push one event the way the hub writes it. */
    fun emit(json: String) = raw("data: $json\n\n")
    /** Cut every open event stream: the line drops. */
    fun cut() { for (q in streams) q.put(CUT) }

    fun json(path: String, body: String, method: String = "GET") { routes["$method $path"] = { Reply(200, body) } }
    fun count(prefix: String) = log.count { it.startsWith(prefix) }

    fun client(): HubClient = HubClient(HubAccount(url, "tok"), http)

    override fun close() {
        cut()
        server.stop(0)
        pool.shutdownNow()
    }

    companion object {
        private val CUT = String(charArrayOf('c', 'u', 't'))
        val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }
}

/** A scope for the code under test that keeps what would have been a crash on the phone: an exception nobody caught. */
internal class Watched {
    val uncaught = CopyOnWriteArrayList<Throwable>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
}

internal fun waitFor(what: String, timeoutMs: Long = 8_000, check: () -> Boolean) {
    val until = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < until) {
        if (check()) return
        Thread.sleep(15)
    }
    throw AssertionError("timed out after ${timeoutMs}ms waiting for: $what")
}

/** True when `check` came true within the time; false (no throw) when it did not. */
internal fun cameTrue(timeoutMs: Long, check: () -> Boolean): Boolean {
    val until = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < until) {
        if (check()) return true
        Thread.sleep(15)
    }
    return false
}
