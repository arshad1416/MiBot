package app.mibot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalStateTest {
    private fun event(json: String) = HubJson.decodeFromString(ServerEvent.serializer(), json)
    private val bot = Bot(id = "b1", name = "Scout")
    private fun approval(id: String, status: String = "pending", by: String? = null) =
        Approval(id = id, botId = "b1", botName = "Scout", title = "Allow Scout to run a command?", detail = "command: rm -rf build", status = status, createdAt = 10, expiresAt = 1_000, resolvedBy = by)

    @Test fun `reads an approval event as the hub sends it, extra fields and all`() {
        val ev = event("""{"type":"approval","approval":{"id":"apr_1","botId":"b1","botName":"Scout","title":"Allow?","detail":"to: a@b.c","tool":"GMAIL_SEND","args":{"to":"a@b.c"},"rowSeq":4,"status":"pending","createdAt":1,"expiresAt":2,"resolvedAt":null,"resolvedBy":null,"later":true}}""")
        val a = (ev as ServerEvent.ApprovalChanged).approval
        assertEquals("apr_1", a.id)
        assertEquals(4, a.rowSeq)
        assertTrue(a.pending)
    }

    @Test fun `an answer is final, a late copy saying pending does not bring the buttons back`() {
        var s = HubState(bots = listOf(bot)).withApproval(approval("a1"))
        s = s.withApproval(approval("a1", "denied", "Mac"))
        s = s.withApproval(approval("a1"))
        assertEquals("denied", s.approvals.getValue("a1").status)
        assertTrue(s.pendingApprovals.isEmpty())
        assertEquals("Denied on Mac", s.approvals.getValue("a1").outcome)
    }

    @Test fun `after a reconnect, one the hub no longer has can no longer be answered`() {
        var s = HubState(bots = listOf(bot)).withApproval(approval("gone")).withApproval(approval("kept"))
        s = s.withApprovals(listOf(approval("kept"), approval("new")), serverTime = 5_000, localNow = 4_000)
        assertEquals("expired", s.approvals.getValue("gone").status)
        assertEquals(listOf("kept", "new"), s.pendingApprovals.map { it.id })
        assertEquals(1_000, s.clockSkewMs)
    }

    @Test fun `the time limit is read by the hub's clock`() {
        val a = approval("a1")
        assertTrue(a.answerable(nowMs = 500))
        assertFalse(a.answerable(nowMs = 500, skewMs = 600))
        assertFalse(approval("a1", "allowed").answerable(0))
    }

    @Test fun `asked for gives one notification, settled takes it away, and the bot's waiting state adds none`() {
        val asked = event("""{"type":"approval","approval":{"id":"a1","botId":"b1","botName":"Scout","status":"pending","createdAt":1,"expiresAt":9}}""")
        var s = HubState(bots = listOf(bot))
        // Even with that chat on screen: the phone may be in a pocket.
        assertTrue(s.noticeFor(asked, openBotId = "b1") is Notice.ApprovalNeeded)
        s = s.reduce(asked)
        assertNull(s.noticeFor(asked, openBotId = null))
        val waiting = event("""{"type":"live","state":{"botId":"b1","isRunning":false,"awaiting":{"reason":"Run code","since":1}}}""")
        assertNull(s.noticeFor(waiting, openBotId = null))
        val answered = event("""{"type":"approval","approval":{"id":"a1","botId":"b1","botName":"Scout","status":"allowed","resolvedBy":"Mac","createdAt":1,"expiresAt":9}}""")
        assertTrue(s.noticeFor(answered, openBotId = null) is Notice.ApprovalSettled)
        s = s.reduce(answered)
        assertNull(s.noticeFor(answered, openBotId = null))
    }
}
