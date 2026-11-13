package app.mibot.core.fuzz

import app.mibot.core.Activity
import app.mibot.core.Awaiting
import app.mibot.core.Bot
import app.mibot.core.ComputerState
import app.mibot.core.HubState
import app.mibot.core.LiveState
import app.mibot.core.MessageBody
import app.mibot.core.Notice
import app.mibot.core.Row
import app.mibot.core.ServerEvent
import app.mibot.core.ToolCallBody
import app.mibot.core.noticeFor
import app.mibot.core.reduce
import app.mibot.core.withRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * HubState under random events. Two kinds of run: events in any order at all (duplicates, stale
 * generations, bots that are gone), where the state only has to stay well-formed; and a small model
 * of the hub whose events arrive in the order it sent them, where the state has to match the hub.
 */
class HubStateFuzzTest {
    private val ids = listOf("b0", "b1", "b2", "b3", "ghost", "")

    private fun Random.row(botId: String, gen: Int, seq: Int): Row =
        Row(if (nextInt(20) == 0) "other" else botId, if (nextInt(20) == 0) gen + nextInt(-2, 3) else gen, seq, nextInt(0, 30), "", body(), time())

    private fun Random.live(botId: String) = LiveState(
        botId, isRunning = nextBoolean(),
        activity = if (nextBoolean()) Activity(listOf("thinking", "working", "messaging_named", "paused", "zz")[nextInt(5)], if (nextBoolean()) oddString() else null, time()) else null,
        awaiting = if (nextInt(3) == 0) Awaiting(if (nextBoolean()) oddString() else "Approve", time()) else null,
        startedAt = if (nextBoolean()) time() else null, caption = if (nextBoolean()) oddString() else null, steps = nextInt(-2, 50),
    )

    private fun Random.bot(id: String) = Bot(
        id, name = oddString(), isPrimary = nextInt(4) == 0, pinned = nextBoolean(), generation = nextInt(0, 5), unread = nextInt(-1, 6),
        lastPreview = when (nextInt(4)) { 0 -> null; 1 -> ""; 2 -> "  "; else -> "preview ${nextInt(100)}" }, lastActivityAt = time(),
    )

    private fun Random.event(): ServerEvent {
        val id = ids[nextInt(ids.size)]
        return when (nextInt(12)) {
            0, 1, 2, 3 -> { val g = nextInt(0, 5); ServerEvent.Rows(id, g, List(nextInt(0, 6)) { row(id, g, nextInt(-2, 40)) }) }
            4 -> ServerEvent.Cleared(id, nextInt(0, 6))
            5, 6 -> ServerEvent.Live(live(id))
            7, 8 -> ServerEvent.BotChanged(bot(id))
            9 -> ServerEvent.Computer(ComputerState(oddString()))
            10 -> listOf(ServerEvent.Roster, ServerEvent.Connections, ServerEvent.Other, ServerEvent.Hello(time()))[nextInt(4)]
            else -> ServerEvent.Routines(id)
        }
    }

    private fun wellFormed(s: HubState, why: String) {
        for ((id, t) in s.transcripts) {
            val seqs = t.rows.map { it.seq }
            assertEquals("$why: rows of $id out of order or doubled: $seqs", seqs.distinct().sorted(), seqs)
        }
        val botIds = s.bots.map { it.id }
        assertEquals("$why: a bot is listed twice: $botIds", botIds.distinct(), botIds)
        for ((id, l) in s.live) assertEquals("$why: live state filed under the wrong bot", id, l.botId)
        // What the home screen reads.
        s.primary
        assertEquals("$why: the home list lost or doubled a bot", s.bots.count { !it.isPrimary }, s.others.size)
    }

    @Test fun `events in any order - never throws, rows stay ordered and single, bots stay single`() {
        repeat(4000) { n ->
            val r = Random(n)
            var s = HubState()
            if (r.nextBoolean()) s = s.copy(bots = ids.take(r.nextInt(ids.size)).map { r.bot(it) }, ready = true)
            val open = if (r.nextBoolean()) ids[r.nextInt(ids.size)] else null
            repeat(r.nextInt(1, 60)) { step ->
                val ev = r.event()
                val before = s
                val notice = try { s.noticeFor(ev, open) } catch (e: Throwable) { throw AssertionError("seed $n step $step: noticeFor($ev) threw $e", e) }
                s = try { s.reduce(ev) } catch (e: Throwable) { throw AssertionError("seed $n step $step: reduce($ev) threw $e", e) }
                wellFormed(s, "seed $n step $step after $ev")

                if (notice != null) {
                    assertTrue("seed $n step $step: a notice for the chat that is open ($open): $notice", notice.bot.id != open)
                    assertTrue("seed $n step $step: a notice for a bot the app does not hold: $notice", before.bot(notice.bot.id) != null)
                    when (notice) {
                        is Notice.Finished -> {
                            assertTrue("seed $n: Finished with nothing to say", notice.preview.isNotBlank())
                            assertTrue("seed $n: Finished without a new unread message", notice.bot.unread > before.bot(notice.bot.id)!!.unread)
                        }
                        is Notice.Waiting -> {
                            assertTrue("seed $n: Waiting with nothing to say", notice.reason.isNotBlank())
                            assertNull("seed $n: Waiting raised again while it was already waiting", before.live[notice.bot.id]?.awaiting)
                        }
                        // This generator sends no approval events; ApprovalStateTest covers them.
                        is Notice.ApprovalNeeded, is Notice.ApprovalSettled -> throw AssertionError("seed $n: an approval notice without an approval event: $notice")
                    }
                }
                // The same event once more (a line that is delivered twice) is not news.
                assertNull("seed $n step $step: $ev raised a notice the second time too", s.noticeFor(ev, open))
                // ...and changes nothing, except the two that count or reset.
                if (ev !is ServerEvent.Connections && ev !is ServerEvent.Cleared) assertEquals("seed $n step $step: $ev twice is not the same as once", s, s.reduce(ev))

                // Generations only go forward through rows.
                if (ev is ServerEvent.Rows) {
                    val was = before.transcripts[ev.botId]?.generation
                    val now = s.transcripts[ev.botId]?.generation
                    if (was != null) assertTrue("seed $n: generation went back $was -> $now", now != null && now >= was)
                }
                // A bot that stopped is not shown as working.
                if (ev is ServerEvent.Live && !ev.state.isRunning) assertTrue("seed $n: still running after it stopped", s.live[ev.state.botId]?.isRunning != true)
            }
        }
    }

    @Test fun `withRows with any arguments keeps the newest copy of each row`() {
        repeat(4000) { n ->
            val r = Random(50_000 + n)
            var s = HubState()
            val newest = HashMap<Int, Row>() // per seq, for the generation in force
            var gen: Int? = null
            repeat(r.nextInt(1, 30)) {
                val g = (gen ?: 0) + r.nextInt(-1, 2)
                val incoming = List(r.nextInt(0, 8)) { Row("b1", g, r.nextInt(0, 25), r.nextInt(0, 10), "", MessageBody(text = "m${r.nextInt(1000)}"), 0) }
                s = s.withRows("b1", g, incoming, if (r.nextBoolean()) r.nextBoolean() else null, if (r.nextBoolean()) r.nextBoolean() else null)
                if (gen == null || g > gen!!) { gen = g; newest.clear() }
                if (g == gen) for (row in incoming) { val cur = newest[row.seq]; if (cur == null || row.updatedSeq >= cur.updatedSeq) newest[row.seq] = row }
                val t = s.transcripts.getValue("b1")
                assertEquals("seed ${50_000 + n}", gen, t.generation)
                assertEquals("seed ${50_000 + n}", newest.values.sortedBy { it.seq }, t.rows)
            }
        }
    }

    // ---------- a model of the hub ----------

    private class ModelBot(var bot: Bot, var gen: Int = 1, var nextSeq: Int = 1, var rev: Int = 0, val rows: ArrayList<Row> = ArrayList(), var live: LiveState? = null)

    private class ModelHub(val r: Random) {
        val bots = LinkedHashMap<String, ModelBot>()
        val out = ArrayList<ServerEvent>()
        private var made = 0

        fun create() {
            val id = "m${made++}"
            bots[id] = ModelBot(Bot(id, "Bot $id", isPrimary = bots.isEmpty()))
            out += ServerEvent.BotChanged(bots.getValue(id).bot)
        }

        fun step() {
            if (bots.isEmpty() || r.nextInt(40) == 0) { create(); return }
            val b = bots.values.elementAt(r.nextInt(bots.size))
            val id = b.bot.id
            when (r.nextInt(20)) {
                in 0..7 -> { // a new row, sometimes two in one event
                    val made = List(if (r.nextInt(6) == 0) 2 else 1) {
                        val body = if (r.nextBoolean()) MessageBody(text = "t${r.nextInt(100)}", streaming = r.nextBoolean()) else ToolCallBody(callId = "c${b.nextSeq}", status = "running")
                        Row(id, b.gen, b.nextSeq++, ++b.rev, "", body, 1_794_500_000_000L + b.rev).also { b.rows += it }
                    }
                    out += ServerEvent.Rows(id, b.gen, made)
                }
                in 8..12 -> if (b.rows.isNotEmpty()) { // a row rewritten while it streams
                    val i = r.nextInt(b.rows.size)
                    val old = b.rows[i]
                    val body = when (val ob = old.body) { is MessageBody -> ob.copy(text = ob.text + "+", streaming = r.nextBoolean()); is ToolCallBody -> ob.copy(status = "done"); else -> ob }
                    b.rows[i] = old.copy(updatedSeq = ++b.rev, body = body)
                    out += ServerEvent.Rows(id, b.gen, listOf(b.rows[i]))
                }
                13 -> { b.gen++; b.rows.clear(); b.nextSeq = 1; out += ServerEvent.Cleared(id, b.gen) }
                14, 15 -> {
                    b.live = LiveState(id, isRunning = r.nextBoolean(), awaiting = if (r.nextInt(3) == 0) Awaiting("Approve", 1) else null, steps = r.nextInt(9))
                    out += ServerEvent.Live(b.live!!)
                }
                16, 17 -> {
                    b.bot = b.bot.copy(unread = if (r.nextBoolean()) b.bot.unread + 1 else 0, lastPreview = "p${r.nextInt(50)}", generation = b.gen)
                    out += ServerEvent.BotChanged(b.bot)
                }
                18 -> if (bots.size > 1) { bots.remove(id); out += ServerEvent.Roster }
                else -> out += listOf(ServerEvent.Other, ServerEvent.Connections, ServerEvent.Routines(id))[r.nextInt(3)]
            }
        }

        /** What GET /bots/:id/rows answers right now: the newest 200 rows of the conversation in force. */
        fun page(id: String): Pair<Int, List<Row>>? = bots[id]?.let { it.gen to it.rows.takeLast(200) }
    }

    @Test fun `events in the order the hub sent them, with repeats and fetches landing late, leave the app holding what the hub holds`() {
        repeat(1500) { n ->
            val r = Random(90_000 + n)
            val hub = ModelHub(r)
            var s = HubState()
            var fed = 0
            val pending = ArrayList<Triple<Int, String, Pair<Int, List<Row>>>>() // (apply when `fed` reaches this, bot, page)
            val everDeleted = HashSet<String>()
            repeat(r.nextInt(20, 250)) {
                val known = hub.bots.keys.toSet()
                hub.step()
                everDeleted += known - hub.bots.keys
                // The app reads events as they come; sometimes it is a few behind.
                while (fed < hub.out.size && r.nextInt(4) != 0) {
                    val ev = hub.out[fed++]
                    s = s.reduce(ev)
                    if (r.nextInt(10) == 0) s = s.reduce(ev) // the same line twice
                    if (ev is ServerEvent.Roster) s = s.copy(bots = s.bots.filter { it.id in hub.bots.keys }, live = s.live.filterKeys { it in hub.bots.keys })
                    for (p in pending.filter { it.first <= fed }) { s = s.withRows(p.second, p.third.first, p.third.second, loaded = true, hasEarlier = p.third.second.size >= 200); pending -= p }
                }
                // A chat is opened: its rows are fetched now and land after a few more events.
                if (r.nextInt(12) == 0 && hub.bots.isNotEmpty()) {
                    val id = hub.bots.keys.elementAt(r.nextInt(hub.bots.size))
                    hub.page(id)?.let { pending += Triple(fed + r.nextInt(0, 6), id, it) }
                }
            }
            while (fed < hub.out.size) {
                val ev = hub.out[fed++]
                s = s.reduce(ev)
                if (ev is ServerEvent.Roster) s = s.copy(bots = s.bots.filter { it.id in hub.bots.keys }, live = s.live.filterKeys { it in hub.bots.keys })
            }
            for (p in pending) s = s.withRows(p.second, p.third.first, p.third.second, loaded = true, hasEarlier = p.third.second.size >= 200)

            val seed = "seed ${90_000 + n}"
            assertEquals("$seed: the bot list", hub.bots.values.map { it.bot }, s.bots)
            for (b in hub.bots.values) {
                val t = s.transcripts[b.bot.id]
                // The app heard every event from the start, so it holds the whole conversation.
                assertEquals("$seed: generation of ${b.bot.id}", if (b.gen == 1 && b.rows.isEmpty() && t == null) null else b.gen, t?.generation)
                assertEquals("$seed: rows of ${b.bot.id}", b.rows, t?.rows.orEmpty())
                assertEquals("$seed: live state of ${b.bot.id}", b.live, s.live[b.bot.id])
            }
            assertTrue("$seed: a bot that was deleted is still listed", s.bots.none { it.id in everDeleted })
        }
    }
}
