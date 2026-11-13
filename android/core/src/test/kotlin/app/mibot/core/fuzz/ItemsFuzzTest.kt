package app.mibot.core.fuzz

import app.mibot.core.CardBody
import app.mibot.core.ChatItem
import app.mibot.core.EventBody
import app.mibot.core.MessageBody
import app.mibot.core.NoticeBody
import app.mibot.core.Row
import app.mibot.core.ToolCallBody
import app.mibot.core.UnknownBody
import app.mibot.core.buildItems
import app.mibot.core.stepsThisTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The conversation as the chat screen draws it. ChatScreen hands `buildItems(rows)` to a LazyColumn
 * with `key = { it.key }`, so two items with one key is a crash there, and a row that is in no item
 * is a message the person never sees.
 */
class ItemsFuzzTest {
    private fun drawn(row: Row) = when (val b = row.body) {
        is UnknownBody -> false
        is MessageBody -> b.text.isNotEmpty() || b.streaming
        else -> true
    }

    private fun rowsOf(item: ChatItem): List<Row> = when (item) {
        is ChatItem.Divider -> emptyList()
        is ChatItem.Group -> item.rows
        is ChatItem.Steps -> item.rows
        is ChatItem.Single -> listOf(item.row)
    }

    private fun check(rows: List<Row>, why: String, uniqueSeqs: Boolean) {
        val items = try { buildItems(rows) } catch (e: Throwable) { throw AssertionError("$why: buildItems threw $e", e) }
        assertEquals("$why: not the same the second time", items, buildItems(rows))

        // Every row that should be drawn is in exactly one item, in the order it came.
        assertEquals("$why: a row was dropped, doubled or moved", rows.filter(::drawn).map { it.seq to it.updatedSeq }, items.flatMap(::rowsOf).map { it.seq to it.updatedSeq })

        for (item in items) {
            when (item) {
                is ChatItem.Group -> {
                    assertTrue("$why: an empty group", item.rows.isNotEmpty())
                    assertTrue("$why: a group holding something that is not a message", item.rows.all { it.body is MessageBody })
                }
                is ChatItem.Steps -> {
                    assertTrue("$why: an empty run of steps", item.rows.isNotEmpty())
                    assertEquals("$why: calls", item.rows.size, item.calls.size) // throws if a row in it is not a tool call
                    val ms = item.workedMs
                    assertTrue("$why: a work time that is not believable: $ms", ms == null || ms in 1_000 until 12 * 60 * 60 * 1000L)
                }
                is ChatItem.Single -> assertTrue("$why: a single that is a message or a step", item.row.body is CardBody || item.row.body is EventBody || item.row.body is NoticeBody)
                is ChatItem.Divider -> {}
            }
        }
        if (uniqueSeqs) {
            val keys = items.map { it.key }
            assertEquals("$why: two items share a key (a crash in the list): ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}", keys.distinct(), keys)
        }
        val n = try { stepsThisTurn(rows) } catch (e: Throwable) { throw AssertionError("$why: stepsThisTurn threw $e", e) }
        assertTrue("$why: steps this turn = $n", n in 0..rows.count { it.body is ToolCallBody })
    }

    @Test fun `any conversation - never throws, drops no message, has no two items with one key, and is the same each time`() {
        repeat(6000) { n ->
            val r = Random(n)
            check(r.transcript(), "seed $n", uniqueSeqs = true)
        }
    }

    @Test fun `rows in any order, with repeats and wild times, still never throw or lose a message`() {
        repeat(4000) { n ->
            val r = Random(100_000 + n)
            val rows = List(r.nextInt(0, 40)) { Row(r.oddString(), r.nextInt(), r.nextInt(-3, 12), r.nextInt(), r.oddString(), r.body(), r.time()) }
            check(rows, "seed ${100_000 + n}", uniqueSeqs = false)
        }
    }

    @Test fun `a conversation that grows row by row keeps the items it already had`() {
        // Rows arrive one at a time while a reply streams; what was drawn stays put, only the tail changes.
        repeat(1500) { n ->
            val r = Random(200_000 + n)
            val rows = r.transcript(30, wildTimes = false)
            var prev = emptyList<ChatItem>()
            for (i in 1..rows.size) {
                val items = buildItems(rows.take(i))
                val settled = prev.dropLast(1)
                assertEquals("seed ${200_000 + n} at row $i: earlier items changed", settled, items.take(settled.size))
                prev = items
            }
        }
    }
}
