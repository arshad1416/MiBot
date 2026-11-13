package app.mibot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubStateTest {
    private fun event(json: String) = HubJson.decodeFromString(ServerEvent.serializer(), json)
    private fun row(json: String) = HubJson.decodeFromString(Row.serializer(), json)

    private val bot = Bot(id = "b1", name = "Scout", unread = 0, lastPreview = "Hi")

    @Test fun `reads each kind of row the hub sends`() {
        val message = row("""{"botId":"b1","generation":2,"seq":7,"updatedSeq":9,"kind":"message","createdAt":5,"body":{"type":"message","role":"assistant","text":"Done","streaming":true,"citations":[{"url":"https://a.example/x"}]}}""")
        assertEquals(MessageBody(role = "assistant", text = "Done", streaming = true, citations = listOf(Citation("https://a.example/x"))), message.body)

        val step = row("""{"botId":"b1","seq":8,"body":{"type":"tool-call","callId":"c1","name":"terminal","label":"Running a command","status":"running","args":{"command":"ls"},"later":"field"}}""")
        assertEquals("running", (step.body as ToolCallBody).status)

        val approval = row("""{"botId":"b1","seq":9,"body":{"type":"card","card":"approval","approvalId":"ap1","title":"Send the email","detail":"To Sam","tool":"GMAIL_SEND","args":{},"status":"pending"}}""")
        assertEquals("ap1", (approval.body as ApprovalCard).approvalId)

        val question = row("""{"botId":"b1","seq":10,"body":{"type":"card","card":"question","question":"Which one?","options":["A","B"],"status":"answered","answer":"B"}}""")
        assertEquals(listOf("A", "B"), (question.body as QuestionCard).options)

        val call = row("""{"botId":"b1","seq":11,"body":{"type":"event","event":"voice-call","text":"Voice chat","call":{"id":"v1","phase":"ended","durationMs":7000}}}""")
        assertEquals(7000L, (call.body as EventBody).call?.durationMs)

        assertEquals(UnknownBody, row("""{"botId":"b1","seq":12,"body":{"type":"hologram","x":1}}""").body)
        assertEquals(UnknownBody, row("""{"botId":"b1","seq":13,"body":{"type":"card","card":"poll"}}""").body)
    }

    @Test fun `a streamed row replaces the one held, an older copy does not`() {
        var s = HubState().reduce(event("""{"type":"rows","botId":"b1","generation":1,"rows":[{"botId":"b1","generation":1,"seq":1,"updatedSeq":2,"body":{"type":"message","role":"assistant","text":"Hel"}}]}"""))
        s = s.reduce(event("""{"type":"rows","botId":"b1","generation":1,"rows":[{"botId":"b1","generation":1,"seq":1,"updatedSeq":3,"body":{"type":"message","role":"assistant","text":"Hello"}}]}"""))
        s = s.reduce(event("""{"type":"rows","botId":"b1","generation":1,"rows":[{"botId":"b1","generation":1,"seq":1,"updatedSeq":1,"body":{"type":"message","role":"assistant","text":"H"}}]}"""))
        assertEquals("Hello", (s.transcripts.getValue("b1").rows.single().body as MessageBody).text)
    }

    @Test fun `rows stay in order and a new conversation drops the old rows`() {
        fun rows(gen: Int, vararg seqs: Int) = seqs.map { Row("b1", gen, it, it, "message", MessageBody(text = "m$it")) }
        var s = HubState().withRows("b1", 1, rows(1, 5, 2), loaded = true)
        s = s.withRows("b1", 1, rows(1, 3))
        assertEquals(listOf(2, 3, 5), s.transcripts.getValue("b1").rows.map { it.seq })
        assertTrue(s.transcripts.getValue("b1").loaded)

        s = s.withRows("b1", 2, rows(2, 1))
        assertEquals(listOf(1), s.transcripts.getValue("b1").rows.map { it.seq })
        // A late row of the conversation that was cleared is not let back in.
        assertEquals(s, s.withRows("b1", 1, rows(1, 9)))

        s = s.reduce(event("""{"type":"cleared","botId":"b1","generation":3}"""))
        assertEquals(Transcript(3, loaded = true), s.transcripts["b1"])
    }

    @Test fun `bot and live events update what is held`() {
        var s = HubState(bots = listOf(bot))
        s = s.reduce(event("""{"type":"bot","bot":{"id":"b1","name":"Scout","unread":2,"lastPreview":"All done"}}"""))
        assertEquals(2, s.bot("b1")?.unread)
        s = s.reduce(event("""{"type":"bot","bot":{"id":"b2","name":"New"}}"""))
        assertEquals(listOf("b1", "b2"), s.bots.map { it.id })
        s = s.reduce(event("""{"type":"live","state":{"botId":"b1","isRunning":true,"activity":{"kind":"thinking","since":1},"awaiting":null,"startedAt":1,"steps":3}}"""))
        assertEquals(3, s.live.getValue("b1").steps)
        assertEquals(s, s.reduce(event("""{"type":"ping"}""")))
        assertEquals(s, s.reduce(event("""{"type":"something-new","x":1}""")))
        assertEquals(1, s.reduce(event("""{"type":"connections"}""")).connectionsVersion)
    }

    @Test fun `a notice is raised when a bot finishes or starts waiting, not for the chat on screen`() {
        val s = HubState(bots = listOf(bot))
        val finished = event("""{"type":"bot","bot":{"id":"b1","name":"Scout","unread":1,"lastPreview":"All done"}}""")
        assertEquals("All done", (s.noticeFor(finished, null) as Notice.Finished).preview)
        assertNull(s.noticeFor(finished, "b1"))
        // The same count again (a rename, say) is not news.
        assertNull(s.reduce(finished).noticeFor(finished, null))

        val waiting = event("""{"type":"live","state":{"botId":"b1","isRunning":true,"awaiting":{"reason":"Approve sending the email","since":1}}}""")
        assertEquals("Approve sending the email", (s.noticeFor(waiting, null) as Notice.Waiting).reason)
        assertNull(s.reduce(waiting).noticeFor(waiting, null))
        assertNull(s.noticeFor(waiting, "b1"))
    }

    @Test fun `the home list puts pinned bots first, then the latest`() {
        val s = HubState(bots = listOf(
            Bot("p", "Main", isPrimary = true, lastActivityAt = 1), Bot("a", "A", lastActivityAt = 10),
            Bot("b", "B", lastActivityAt = 30), Bot("c", "C", pinned = true, lastActivityAt = 5),
        ))
        assertEquals("p", s.primary?.id)
        assertEquals(listOf("c", "b", "a"), s.others.map { it.id })
    }

    @Test fun `reads a pairing link and a typed address`() {
        assertEquals(PairLink("https://mini.example.ts.net", "ABCD2345"), HubClient.parsePairLink("https://mini.example.ts.net/pair#abcd-2345"))
        assertEquals(PairLink("http://192.168.1.20:8787", "ABCD2345"), HubClient.parsePairLink(" http://192.168.1.20:8787/pair#ABCD2345 "))
        assertNull(HubClient.parsePairLink("https://example.com/other#ABCD2345"))
        assertNull(HubClient.parsePairLink("ABCD2345"))
        assertEquals("http://mac-mini:8787", HubClient.normalizeUrl("mac-mini:8787/"))
        assertEquals("https://mini.example.ts.net", HubClient.normalizeUrl("HTTPS://mini.example.ts.net/pair#x"))
        assertNull(HubClient.normalizeUrl("  "))
        assertNotNull(HubClient.normalizeUrl("localhost:8787"))
    }
}
