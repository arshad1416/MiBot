package app.mibot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ItemsTest {
    private var seq = 0
    private fun row(body: RowBody, at: Long) = Row("b1", 1, ++seq, seq, "", body, at)
    private fun user(text: String, at: Long) = row(MessageBody(role = "user", text = text), at)
    private fun bot(text: String, at: Long, streaming: Boolean = false) = row(MessageBody(text = text, streaming = streaming), at)
    private fun step(status: String, at: Long) = row(ToolCallBody(callId = "c$at", label = "Step", status = status), at)

    private val min = 60_000L
    private val t0 = 1_000_000_000_000L

    @Test fun `messages group by sender, steps fold into one item, a long gap adds a divider`() {
        val rows = listOf(
            user("Find it", t0), user("and send it", t0 + min),
            step("done", t0 + 2 * min), step("done", t0 + 3 * min),
            bot("Found it.", t0 + 4 * min),
            row(NoticeBody("Heads up"), t0 + 5 * min),
            bot("Later.", t0 + 90 * min),
        )
        val items = buildItems(rows)
        assertEquals(listOf("Divider", "Group", "Steps", "Group", "Single", "Divider", "Group"), items.map { it::class.simpleName })
        val mine = items[1] as ChatItem.Group
        assertTrue(mine.mine)
        assertEquals(2, mine.rows.size)
        val steps = items[2] as ChatItem.Steps
        assertEquals(2, steps.calls.size)
        // From the message before the steps to the reply after them.
        assertEquals(3 * min, steps.workedMs)
    }

    @Test fun `an empty reply is not drawn until it has text or is streaming`() {
        assertTrue(buildItems(listOf(bot("", t0))).none { it is ChatItem.Group })
        assertTrue(buildItems(listOf(bot("", t0, streaming = true))).last() is ChatItem.Group)
    }

    @Test fun `a message from a routine sits on the left and starts its own group`() {
        val fromRoutine = row(MessageBody(role = "user", text = "Morning check", from = Sender("routine", "r1", "Morning")), t0)
        val items = buildItems(listOf(fromRoutine, user("thanks", t0 + 1000)))
        val groups = items.filterIsInstance<ChatItem.Group>()
        assertEquals(listOf(false, true), groups.map { it.mine })
        assertEquals("Morning", groups[0].from?.name)
    }

    @Test fun `a reply keeps the message it answers, and the chat can find that message`() {
        val row = HubJson.decodeFromString(
            Row.serializer(),
            """{"botId":"b1","seq":7,"body":{"type":"message","role":"user","text":"More on this","replyTo":{"seq":3,"role":"assistant","text":"Two ideas"}}}""",
        )
        val quote = (row.body as MessageBody).replyTo!!
        assertEquals(3, quote.seq)
        assertEquals("Doug", quote.whose("Doug"))
        assertEquals("You", quote.copy(role = "user").whose("Doug"))
        assertEquals("Ana", quote.copy(name = "Ana").whose("Doug"))
        assertNull(HubJson.decodeFromString(Row.serializer(), """{"botId":"b1","seq":8,"body":{"type":"message","text":"hi"}}""").let { (it.body as MessageBody).replyTo })

        val first = bot("Two ideas", t0)
        val items = buildItems(listOf(user("Give me two", t0 - 1000), first, user("More on this", t0 + 1000)))
        assertEquals(items.indexOfFirst { it is ChatItem.Group && !it.mine }, indexOfMessage(items, first.seq))
        assertEquals(-1, indexOfMessage(items, 999))
    }

    @Test fun `steps still running have no length yet, and steps are counted back to the person's last message`() {
        val rows = listOf(user("Go", t0), step("done", t0 + 1000), step("running", t0 + 2000))
        assertNull((buildItems(rows).last() as ChatItem.Steps).endAt)
        assertEquals(2, stepsThisTurn(rows))
        assertEquals(0, stepsThisTurn(rows + user("Stop", t0 + 3000)))
    }

    @Test fun `words for what a bot is doing`() {
        val rows = emptyList<Row>()
        assertEquals("Needs you", activityStatus(LiveState("b1", awaiting = Awaiting("Approve", 1)), rows))
        assertEquals("Working in Gmail", activityStatus(LiveState("b1", activity = Activity("connecting_service", "Gmail")), rows))
        assertEquals("Using an app", activityStatus(LiveState("b1", activity = Activity("connecting_service", "gmail_send")), rows))
        assertEquals("Mulling it over", activityPhrase(LiveState("b1", activity = Activity("thinking")), rows, 1))
        assertEquals("Waiting for a machine to take this", activityPhrase(LiveState("b1", activity = Activity("waiting_for_machine")), rows, 0))
        assertEquals("Waiting for you: Approve", previewFor(Bot("b1", lastPreview = "x"), LiveState("b1", awaiting = Awaiting("Approve", 1))))
        assertEquals("Google Calendar", appName("googlecalendar"))
        assertEquals("Gmail send email", toolName("GMAIL_SEND_EMAIL"))
    }

    @Test fun `times read the way the web app writes them`() {
        val zone = ZoneId.of("America/Toronto")
        val now = 1_794_500_000_000L // Thu 12 Nov 2026, 11:13 AM in Toronto
        assertEquals("Today 11:13 AM", TimeText.divider(now, now, zone))
        assertEquals("Yesterday 11:13 AM", TimeText.divider(now - 24 * 60 * min, now, zone))
        assertEquals("Mon, Nov 9 11:13 AM", TimeText.divider(now - 3 * 24 * 60 * min, now, zone))
        assertEquals("11:13 AM", TimeText.listTime(now, now, zone))
        assertEquals("Yesterday", TimeText.listTime(now - 24 * 60 * min, now, zone))
        assertEquals("Mon", TimeText.listTime(now - 3 * 24 * 60 * min, now, zone))
        assertEquals("1m 23s", TimeText.duration(83_000))
        assertEquals("00:07", TimeText.callLength(7_000))
    }
}
