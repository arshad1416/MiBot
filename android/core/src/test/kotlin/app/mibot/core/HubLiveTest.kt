package app.mibot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The client against a hub that is really running. It is skipped unless it is told where one is:
 *
 *   ./gradlew -PcoreOnly :core:test -PhubUrl=http://127.0.0.1:8787 -PhubCode=ABCD2345
 *
 * with a one-time code from Settings › Machines. It signs in with the code, then sends the first
 * bot a message and follows the answer over the event stream.
 */
class HubLiveTest {
    private val hubUrl: String? = System.getProperty("mibot.hubUrl")
    private val code: String? = System.getProperty("mibot.hubCode")

    @Test fun `pairs, lists bots, sends a message and sees the answer arrive`() = runBlocking {
        assumeTrue("no hub given", !hubUrl.isNullOrBlank() && !code.isNullOrBlank())

        val wrong = runCatching { HubClient.pair(hubUrl!!, "ZZZZ9999") }.exceptionOrNull() as HubException
        assertEquals(400, wrong.status)
        assertTrue(wrong.message!!.contains("not right"))

        val account = HubClient.pair(hubUrl!!, code!!)
        val client = HubClient(account)
        assertTrue(client.me()!!.email.contains("@"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = HubSession(client, scope)
        try {
            session.start()
            val ready = withTimeout(20_000) { session.state.first { it.online && it.ready } }
            val bot = ready.primary ?: ready.bots.first()

            session.openBot(bot.id)
            withTimeout(20_000) { session.state.first { it.transcripts[bot.id]?.loaded == true } }
            client.sendMessage(bot.id, "Say hello. steps=2 delay=300")

            // The bot shows as working, then the answer lands and it is idle again.
            withTimeout(30_000) { session.state.first { it.live[bot.id]?.isRunning == true } }
            val done = withTimeout(60_000) {
                session.state.first { s ->
                    val rows = s.transcripts[bot.id]?.rows.orEmpty()
                    s.live[bot.id]?.isRunning != true && rows.any { r -> (r.body as? MessageBody)?.let { !it.isUser && !it.streaming && it.text.isNotEmpty() } == true }
                }
            }
            val rows = done.transcripts.getValue(bot.id).rows
            assertTrue(rows.any { (it.body as? MessageBody)?.text == "Say hello. steps=2 delay=300" })
            assertEquals(2, rows.count { it.body is ToolCallBody })
            val items = buildItems(rows)
            assertTrue(items.any { it is ChatItem.Steps && it.calls.size == 2 })
            // The chat is on screen, so it stays read.
            withTimeout(10_000) { session.state.first { it.bot(bot.id)?.unread == 0 } }

            // A sign-in that the hub does not know ends the session instead of retrying for ever.
            val stale = HubSession(HubClient(account.copy(token = "nope")), scope)
            stale.start()
            assertTrue(withTimeout(20_000) { stale.state.first { it.signedOut } }.signedOut)

            client.signOut()
            assertFalse(runCatching { client.bots() }.isSuccess)
        } finally {
            session.stop()
            scope.cancel()
        }
    }
}
