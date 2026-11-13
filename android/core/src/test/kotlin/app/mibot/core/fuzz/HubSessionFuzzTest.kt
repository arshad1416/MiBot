package app.mibot.core.fuzz

import app.mibot.core.HubJson
import app.mibot.core.HubSession
import app.mibot.core.HubState
import app.mibot.core.Notice
import app.mibot.core.ServerEvent
import app.mibot.core.noticeFor
import app.mibot.core.reduce
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * HubSession against a stand-in hub on 127.0.0.1 (FakeHub): the real HTTP calls and the real event
 * stream, with the hub's side scripted. The line is cut and brought back the way a phone's is when it
 * changes network or sleeps.
 */
class HubSessionFuzzTest {
    private val hub = FakeHub()
    private val watched = Watched()
    private val notices = CopyOnWriteArrayList<Notice>()

    // The hub's side of the story.
    private val bots = CopyOnWriteArrayList<String>()
    private val live = CopyOnWriteArrayList<String>()
    private val rows = ConcurrentHashMap<String, CopyOnWriteArrayList<Pair<Int, String>>>()

    private fun bot(id: String, unread: Int = 0, preview: String? = "Hi", primary: Boolean = false) =
        """{"id":"$id","name":"Bot $id","isPrimary":$primary,"unread":$unread,"lastPreview":${preview?.let { JsonPrimitive(it) } ?: "null"},"generation":1,"lastActivityAt":5}"""

    private fun row(botId: String, seq: Int, text: String = "m$seq", upd: Int = seq, gen: Int = 1) =
        """{"botId":"$botId","generation":$gen,"seq":$seq,"updatedSeq":$upd,"kind":"message","body":{"type":"message","role":"assistant","text":"$text"},"createdAt":${1_794_500_000_000L + seq}}"""

    private fun addRows(botId: String, range: IntRange) { rows.getOrPut(botId) { CopyOnWriteArrayList() } += range.map { it to row(botId, it) } }

    private fun session(): HubSession {
        hub.json("/api/auth/me", """{"user":{"id":"u1","email":"a@example.com","name":"Ari Example","avatarUrl":null}}""")
        hub.routes["GET /api/bots"] = { FakeHub.Reply(200, """{"bots":[${bots.joinToString(",")}],"live":[${live.joinToString(",")}]}""") }
        hub.json("/api/computer", """{"computer":{"status":"absent","orgoId":null,"error":null,"screens":[]}}""")
        hub.fallback = { ex ->
            val path = ex.requestURI.rawPath
            val m = Regex("^/api/bots/([^/]+)/rows$").find(path)
            when {
                m != null && ex.requestMethod == "GET" -> {
                    val before = Regex("beforeSeq=(-?\\d+)").find(ex.requestURI.rawQuery.orEmpty())?.groupValues?.get(1)?.toInt()
                    val all = rows[m.groupValues[1]].orEmpty().sortedBy { it.first }
                    val page = (if (before != null) all.filter { it.first < before } else all).takeLast(200) // the hub's page: the newest 200
                    FakeHub.Reply(200, """{"generation":1,"rows":[${page.joinToString(",") { it.second }}]}""")
                }
                ex.requestMethod == "POST" -> FakeHub.Reply(200, """{"ok":true}""")
                else -> FakeHub.Reply(404, """{"error":"not found"}""")
            }
        }
        val s = HubSession(hub.client(), watched.scope)
        watched.scope.launch { s.notices.collect { notices += it } }
        s.start()
        waitFor("the session to be online with the bot list") { s.state.value.online && s.state.value.ready && s.state.value.computer != null }
        return s
    }

    /** Cut the line and wait until the session has it back and has fetched again. */
    private fun dropAndReturn(s: HubSession, whileDown: () -> Unit) {
        val fetched = hub.count("GET /api/computer")
        hub.cut()
        waitFor("the session to notice the line dropped") { !s.state.value.online }
        whileDown()
        waitFor("the session to come back and fetch again", 15_000) { s.state.value.online && hub.count("GET /api/computer") > fetched }
        Thread.sleep(300) // let the fetches of the resync land
    }

    @After fun tearDown() {
        watched.scope.cancel()
        hub.close()
    }

    private fun noCrash() = assertTrue("an exception nobody caught (a crash on the phone): ${watched.uncaught}", watched.uncaught.isEmpty())

    // ---------- the event stream, differentially against the pure fold ----------

    private fun Random.eventJson(): String {
        val id = listOf("a", "b", "c", "ghost")[nextInt(4)]
        return when (nextInt(12)) {
            0, 1, 2, 3 -> """{"type":"rows","botId":"$id","generation":${nextInt(1, 3)},"rows":[${List(nextInt(0, 4)) { row(id, nextInt(1, 30), "t${nextInt(99)}", nextInt(1, 40)) }.joinToString(",")}]}"""
            4 -> """{"type":"cleared","botId":"$id","generation":${nextInt(1, 4)}}"""
            5, 6 -> """{"type":"live","state":{"botId":"$id","isRunning":${nextBoolean()},"activity":${if (nextBoolean()) """{"kind":"thinking","since":1}""" else "null"},"awaiting":${if (nextInt(3) == 0) """{"reason":"Approve","since":1}""" else "null"},"startedAt":null,"steps":${nextInt(9)}}}"""
            7, 8 -> """{"type":"bot","bot":${bot(id, nextInt(0, 4), listOf("Done", "", null)[nextInt(3)])}}"""
            9 -> """{"type":"connections"}"""
            10 -> listOf("""{"type":"ping"}""", """{"type":"tasks","botId":"$id"}""", """{"type":"machines"}""", """{"type":"zz-new","x":[1,2]}""", """{"type":"memories","botId":"$id"}""")[nextInt(5)]
            else -> listOf( // lines the app cannot read: they are skipped, and the stream goes on
                """{"type":"rows","botId":"$id"""", """{"type":"rows","botId":"$id","rows":[{"seq":1}]}""", """{"type":"live","state":null}""", """{"type":"bot","bot":{}}""",
                """[1,2,3]""", "not json", """{"type":{"a":1}}""", """{"type":"rows","botId":"$id","generation":1,"rows":[{"botId":"$id","seq":2,"body":{"type":"card","card":"question","options":{"a":1}}}]}""",
            )[nextInt(8)]
        }
    }

    /** One event as lines on the wire, in the ways a server or a proxy in between may write them. Returns the lines and the event's data as the SSE rules read it. */
    private fun Random.frame(json: String): String = when (nextInt(8)) {
        0 -> "data:$json\n\n"
        1 -> "event: message\ndata: $json\n\n"
        2 -> "id: ${nextInt(100)}\ndata: $json\n\n"
        3 -> ": keep-alive\n\ndata: $json\n\n"
        4 -> "data: $json\r\n\r\n"
        5 -> "retry: 3000\n\ndata: $json\n\n\n"
        else -> "data: $json\n\n"
    }

    @Test fun `a long stream of events, some unreadable, ends in the same state as folding the readable ones by hand`() {
        bots += listOf(bot("a", primary = true), bot("b"), bot("c"))
        val s = session()
        for (seed in 1..4) {
            val r = Random(seed)
            var want: HubState = s.state.value
            val wantNotices = ArrayList<Notice>()
            val seen = notices.size
            repeat(400) {
                val json = r.eventJson()
                hub.raw(r.frame(json))
                val ev = runCatching { HubJson.decodeFromString(ServerEvent.serializer(), json) }.getOrNull()
                if (ev != null && ev !is ServerEvent.Hello) {
                    want.noticeFor(ev, null)?.let { wantNotices += it }
                    want = want.reduce(ev)
                }
            }
            val mark = "end-of-seed-$seed"
            hub.emit("""{"type":"computer","state":{"status":"$mark","orgoId":null,"error":null,"screens":[]}}""")
            waitFor("seed $seed: the last event to arrive", 20_000) { s.state.value.computer?.status == mark }
            val got = s.state.value
            assertEquals("seed $seed: transcripts", want.transcripts, got.transcripts)
            assertEquals("seed $seed: bots", want.bots, got.bots)
            assertEquals("seed $seed: live", want.live, got.live)
            assertEquals("seed $seed: connections", want.connectionsVersion, got.connectionsVersion)
            assertTrue("seed $seed: the stream is still up", got.online && s.isRunning && !got.signedOut)
            noCrash()
            // Notices: none that the fold would not raise, none twice. (The flow drops notices when 16 are waiting, so fewer is allowed.)
            Thread.sleep(200)
            val raised = notices.drop(seen)
            assertTrue("seed $seed: more notices than events called for: ${raised.size} > ${wantNotices.size}", raised.size <= wantNotices.size)
            var i = 0
            for (n in raised) {
                while (i < wantNotices.size && wantNotices[i] != n) i++
                assertTrue("seed $seed: a notice the events did not call for, or out of order: $n", i < wantNotices.size)
                i++
            }
        }
    }

    // ---------- notices ----------

    @Test fun `a notice is raised once for the bot that finished, never for the chat on screen, which is kept read instead`() {
        bots += listOf(bot("a", primary = true), bot("b"))
        addRows("a", 1..2)
        val s = session()
        s.openBot("a")
        waitFor("the open chat to load") { s.state.value.transcripts["a"]?.loaded == true }

        hub.emit("""{"type":"bot","bot":${bot("a", 1, "Done in A")}}""")
        hub.emit("""{"type":"bot","bot":${bot("b", 1, "Done in B")}}""")
        hub.emit("""{"type":"bot","bot":${bot("b", 1, "Done in B")}}""") // the same line again
        hub.emit("""{"type":"live","state":{"botId":"b","isRunning":true,"awaiting":{"reason":"Approve the email","since":1}}}""")
        hub.emit("""{"type":"live","state":{"botId":"b","isRunning":true,"awaiting":{"reason":"Approve the email","since":1}}}""")
        hub.emit("""{"type":"live","state":{"botId":"a","isRunning":true,"awaiting":{"reason":"Approve in A","since":1}}}""")
        hub.emit("""{"type":"connections"}""")
        waitFor("the events to arrive") { s.state.value.connectionsVersion == 1 }
        Thread.sleep(200)

        assertEquals(listOf("Finished b Done in B", "Waiting b Approve the email"), notices.map { if (it is Notice.Finished) "Finished ${it.bot.id} ${it.preview}" else "Waiting ${it.bot.id} ${(it as Notice.Waiting).reason}" })
        assertEquals("the open chat is marked read once", 1, hub.count("POST /api/bots/a/read"))
        assertEquals(0, hub.count("POST /api/bots/b/read"))
        noCrash()
    }

    /**
     * HubService keeps the line open "so a bot that finishes at night still reaches you". A phone's line does not stay up all
     * night: it drops and comes back. What happened in between reaches the app as the fetch after `hello`, not as events.
     */
    @Test fun `a bot that finished while the line was down is still announced when the line comes back`() {
        bots += listOf(bot("a", primary = true), bot("b", unread = 0, preview = "Working on it"))
        live += """{"botId":"b","isRunning":true,"activity":{"kind":"working","since":1},"awaiting":null,"startedAt":1,"steps":2}"""
        val s = session()
        assertTrue(s.state.value.live["b"]?.isRunning == true)

        dropAndReturn(s) {
            // The bot finishes and leaves its answer unread; nobody is listening.
            bots[1] = bot("b", unread = 1, preview = "All done: the report is in your files")
            live.clear()
        }
        val st = s.state.value
        assertEquals("the app has the new count", 1, st.bot("b")?.unread)
        assertTrue("the bot is no longer shown as working", st.live["b"]?.isRunning != true)
        val told = cameTrue(1_500) { notices.any { it is Notice.Finished && it.bot.id == "b" } }
        noCrash()
        assertTrue("the bot finished while the line was down (unread 0 -> 1, \"All done…\") and the app, back online and holding the new count, raised no notice: $notices", told)
    }

    @Test fun `a bot that started waiting for an answer while the line was down is still announced`() {
        bots += listOf(bot("a", primary = true), bot("b"))
        val s = session()
        dropAndReturn(s) { live += """{"botId":"b","isRunning":true,"activity":{"kind":"waiting_for_you","since":1},"awaiting":{"reason":"Approve sending the email","since":2},"startedAt":1}""" }
        assertTrue("the app knows it is waiting", s.state.value.live["b"]?.awaiting != null)
        val told = cameTrue(1_500) { notices.any { it is Notice.Waiting && it.bot.id == "b" } }
        noCrash()
        assertTrue("the bot stopped to wait for the person while the line was down and no notice was raised: $notices", told)
    }

    // ---------- what is held after the line comes back ----------

    @Test fun `a chat that was read before the line dropped shows what was said in between when it is opened again`() {
        bots += listOf(bot("a", primary = true), bot("b"))
        addRows("a", 1..3)
        addRows("b", 1..3)
        val s = session()
        s.openBot("b")
        waitFor("chat b to load") { s.state.value.transcripts["b"]?.loaded == true }
        s.closeBot("b") // back to the home screen
        assertEquals(listOf(1, 2, 3), s.state.value.transcripts.getValue("b").rows.map { it.seq })

        dropAndReturn(s) { addRows("b", 4..5) } // the bot answers while the phone is offline

        s.openBot("b")
        val shown = cameTrue(3_000) { s.state.value.transcripts["b"]?.rows?.map { it.seq } == listOf(1, 2, 3, 4, 5) }
        noCrash()
        assertTrue(
            "chat b was opened again after the line came back and still shows rows ${s.state.value.transcripts["b"]?.rows?.map { it.seq }}; the hub has 1..5. " +
                "Rows were fetched ${hub.count("GET /api/bots/b/rows")} time(s) in all.",
            shown,
        )
    }

    @Test fun `more than a page written while the line was down leaves no hole in the open chat`() {
        bots += listOf(bot("a", primary = true))
        addRows("a", 1..100)
        val s = session()
        s.openBot("a")
        waitFor("chat a to load") { s.state.value.transcripts["a"]?.rows?.size == 100 }

        dropAndReturn(s) { addRows("a", 101..400) } // a long job: 300 rows while the phone was asleep
        waitFor("the newest rows to arrive") { s.state.value.transcripts["a"]?.rows?.lastOrNull()?.seq == 400 }

        // The chat screen asks for earlier rows for as long as the state says there are some.
        var guard = 0
        while (s.state.value.transcripts.getValue("a").hasEarlier && guard++ < 10) kotlinx.coroutines.runBlocking { s.loadEarlier("a") }
        val held = s.state.value.transcripts.getValue("a").rows.map { it.seq }
        val missing = (1..400).filter { it !in held }
        noCrash()
        assertTrue("rows ${missing.firstOrNull()}..${missing.lastOrNull()} (${missing.size} of them) are in the middle of the conversation and can never be fetched: hasEarlier=${s.state.value.transcripts.getValue("a").hasEarlier}", missing.isEmpty())
    }

    /**
     * After `hello` the session fetches the account, the bots, the computer, then the open chat. A phone is usually opened
     * to watch a bot work, so events keep arriving while those fetches run.
     */
    @Test fun `the fetch after the line comes back asks for the computer once and then catches the open chat up, even while a reply is streaming`() {
        bots += listOf(bot("a", primary = true))
        addRows("a", 1..3)
        val s = session()
        s.openBot("a")
        waitFor("chat a to load") { s.state.value.transcripts["a"]?.loaded == true }
        // The hub's GET /api/computer asks the computer provider for its status, so it is not instant.
        hub.routes["GET /api/computer"] = { FakeHub.Reply(200, """{"computer":{"status":"running","orgoId":null,"error":null,"screens":[]}}""", delayMs = 120) }

        val asked = hub.count("GET /api/computer")
        hub.cut()
        waitFor("the session to notice the line dropped") { !s.state.value.online }
        addRows("a", 4..5) // said while the phone was offline
        waitFor("the session to come back", 15_000) { s.state.value.online }

        // A reply streams: its row is rewritten about 25 times a second for four seconds (server/src/agent/loop.ts `flush`).
        var upd = 100
        val until = System.currentTimeMillis() + 4_000
        var caughtUpAt: Long? = null
        while (System.currentTimeMillis() < until) {
            hub.emit("""{"type":"rows","botId":"a","generation":1,"rows":[${row("a", 6, "streaming ${upd}", upd++)}]}""")
            Thread.sleep(40)
            if (caughtUpAt == null && s.state.value.transcripts["a"]?.rows?.any { it.seq == 4 } == true) caughtUpAt = System.currentTimeMillis() - (until - 4_000)
        }
        val times = hub.count("GET /api/computer") - asked
        noCrash()
        assertTrue(
            "GET /api/computer was asked $times times in the 4 s after one `hello` (each one makes the hub query the computer provider); " +
                "the rows said while offline ${caughtUpAt?.let { "arrived after $it ms" } ?: "had still not been fetched when the reply stopped streaming"}",
            times <= 2 && caughtUpAt != null,
        )
    }

    /**
     * The same fetch runs beside the event stream. The hub answers GET /api/bots with what was true when it was asked;
     * an event sent just after that is newer, and reaches the app first when the answer is slow to come back.
     */
    @Test fun `a bot that finishes while the bot list is being fetched is not left showing as working`() {
        bots += listOf(bot("a", primary = true), bot("b"))
        val s = session()
        // b starts working.
        live += """{"botId":"b","isRunning":true,"activity":{"kind":"working","since":1},"awaiting":null,"startedAt":1,"steps":2}"""
        hub.emit("""{"type":"live","state":${live[0]}}""")
        waitFor("b to show as working") { s.state.value.live["b"]?.isRunning == true }

        // The line drops and comes back; this time the answer to GET /api/bots takes 400 ms to arrive.
        hub.cut()
        waitFor("the session to notice the line dropped") { !s.state.value.online }
        val asked = hub.count("GET /api/bots")
        hub.routes["GET /api/bots"] = { FakeHub.Reply(200, """{"bots":[${bots.joinToString(",")}],"live":[${live.joinToString(",")}]}""", delayMs = 400) }
        waitFor("the session to come back and ask for the bots", 15_000) { s.state.value.online && hub.count("GET /api/bots") > asked }
        Thread.sleep(100)
        // b finishes right then: the hub says so on the stream, once.
        live.clear()
        hub.emit("""{"type":"live","state":{"botId":"b","isRunning":false,"activity":null,"awaiting":null,"startedAt":null,"caption":null}}""")
        hub.emit("""{"type":"bot","bot":${bot("b", 1, "All done")}}""")
        Thread.sleep(1_500) // the slow answer has landed by now

        val st = s.state.value
        noCrash()
        assertTrue(
            "the hub said b finished (live isRunning=false, unread 1, \"All done\"), and the app, on a stream that is up, shows it as " +
                "live=${st.live["b"]} unread=${st.bot("b")?.unread} preview=${st.bot("b")?.lastPreview}. Nothing more will come for b until it works again.",
            st.live["b"]?.isRunning != true && st.bot("b")?.unread == 1,
        )
    }

    // ---------- a row the app cannot read ----------

    @Test fun `a conversation with one row the app cannot read still opens`() {
        bots += listOf(bot("a", primary = true))
        addRows("a", 1..3)
        // What the hub stores when a model calls ask_user with option objects (server/src/agent/tools.ts passes `options` through unchecked).
        rows.getValue("a") += 4 to """{"botId":"a","generation":1,"seq":4,"updatedSeq":4,"kind":"card","body":{"type":"card","card":"question","question":"Which one?","options":[{"label":"Yes"},{"label":"No"}],"status":"pending"},"createdAt":1794500000004}"""
        addRows("a", 5..6)
        val s = session()
        s.openBot("a")
        val loaded = cameTrue(3_000) { s.state.value.transcripts["a"]?.loaded == true }
        noCrash()
        assertTrue(
            "the chat never loads: transcript=${s.state.value.transcripts["a"]}, rows fetched ${hub.count("GET /api/bots/a/rows")} time(s). " +
                "ChatScreen shows \"Loading the conversation…\" for as long as `loaded` is false.",
            loaded,
        )
        assertTrue(s.state.value.transcripts.getValue("a").rows.map { it.seq }.containsAll(listOf(1, 2, 3, 5, 6)))
    }

    // ---------- a bot deleted while it is open and working ----------

    @Test fun `a bot deleted while its chat is open and it is working goes away cleanly, and late events for it do no harm`() {
        bots += listOf(bot("a", primary = true), bot("b"))
        addRows("b", 1..3)
        val s = session()
        s.openBot("b")
        waitFor("chat b to load") { s.state.value.transcripts["b"]?.loaded == true }
        hub.emit("""{"type":"live","state":{"botId":"b","isRunning":true,"activity":{"kind":"working","since":1},"awaiting":null,"startedAt":1,"steps":1}}""")
        waitFor("b to show as working") { s.state.value.live["b"]?.isRunning == true }

        bots.removeAt(1) // deleted on another device
        hub.emit("""{"type":"roster"}""")
        waitFor("the bot list to be fetched again") { s.state.value.bots.map { it.id } == listOf("a") }
        assertNull("its live state went with it", s.state.value.live["b"])

        // What was already on its way when it was deleted.
        hub.emit("""{"type":"rows","botId":"b","generation":1,"rows":[${row("b", 4)}]}""")
        hub.emit("""{"type":"live","state":{"botId":"b","isRunning":false,"activity":null,"awaiting":{"reason":"Approve","since":1},"startedAt":null}}""")
        hub.emit("""{"type":"cleared","botId":"b","generation":2}""")
        hub.emit("""{"type":"routines","botId":"b"}""")
        hub.emit("""{"type":"connections"}""")
        waitFor("the late events to arrive") { s.state.value.connectionsVersion == 1 }
        Thread.sleep(200)
        assertEquals(listOf("a"), s.state.value.bots.map { it.id })
        assertTrue("no notice for a bot that is gone: $notices", notices.none { it.bot.id == "b" })
        assertTrue(s.state.value.online && s.isRunning)
        // The app can still be told to act on it; the hub says no and that is all.
        kotlinx.coroutines.runBlocking { runCatching { s.loadEarlier("b") }; runCatching { s.loadRoutines("b") } }
        s.closeBot("b")
        noCrash()
    }

    // ---------- a hub that answers badly ----------

    @Test fun `fetches that fail or come back as nonsense do not stop the session, and the next good one is taken`() {
        bots += listOf(bot("a", primary = true))
        hub.json("/api/auth/me", """{"user":{"id":"u1","name":"Ari"}}""")
        hub.json("/api/computer", """{"computer":{"status":"absent"}}""")
        val junk = listOf(FakeHub.Reply(500, "boom"), FakeHub.Reply(200, "<html>"), FakeHub.Reply(200, ""), FakeHub.Reply(200, """{"bots":[{"name":"no id"}]}"""), FakeHub.Reply(502, """{"error":{"a":1}}"""), FakeHub.Reply(200, """{"bots":"x"}"""), FakeHub.Reply(204, ""))
        var n = 0
        hub.routes["GET /api/bots"] = { junk[n++ % junk.size] }
        val s = HubSession(hub.client(), watched.scope)
        s.start()
        waitFor("the stream to open") { s.state.value.online }
        repeat(junk.size + 2) { hub.emit("""{"type":"roster"}""") }
        hub.emit("""{"type":"connections"}""")
        waitFor("the roster events to be handled") { s.state.value.connectionsVersion == 1 }
        assertFalse("nothing good was ever fetched", s.state.value.ready)
        assertTrue(s.state.value.online && s.isRunning)
        noCrash()

        hub.routes["GET /api/bots"] = { FakeHub.Reply(200, """{"bots":[${bots.joinToString(",")}],"live":[]}""") }
        hub.emit("""{"type":"roster"}""")
        waitFor("the good bot list to be taken") { s.state.value.ready && s.state.value.bots.size == 1 }
        noCrash()
    }

    @Test fun `a hub that no longer knows this phone ends the session instead of trying for ever`() {
        hub.eventsStatus = 401
        val s = HubSession(hub.client(), watched.scope)
        s.start()
        waitFor("the session to see it is signed out") { s.state.value.signedOut }
        waitFor("the stream to stop") { !s.isRunning }
        val tries = hub.count("GET /api/events")
        s.start() // the activity calls start() every time it comes on screen
        Thread.sleep(1_500)
        assertEquals("no more tries once signed out", tries, hub.count("GET /api/events"))
        assertFalse(s.state.value.online)
        noCrash()
    }

    @Test fun `start and stop in any order leave at most one stream open`() {
        bots += listOf(bot("a", primary = true))
        val s = session()
        val r = Random(7)
        repeat(60) {
            when (r.nextInt(4)) { 0 -> s.stop(); 1, 2 -> s.start(); else -> { s.openBot("a"); s.closeBot("a") } }
            Thread.sleep(r.nextLong(0, 40))
        }
        s.start()
        waitFor("the session to be online again", 15_000) { s.state.value.online }
        Thread.sleep(500)
        assertTrue("streams open at the hub: ${hub.openStreams}", hub.openStreams <= 1)
        s.stop()
        waitFor("the stream to be dropped at the hub") { !s.state.value.online }
        noCrash()
    }
}
