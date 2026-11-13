package app.mibot.core.fuzz

import app.mibot.core.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Deterministic stale HTTP snapshots interleaved with newer stream events; localhost only. */
class RefreshRaceTest {
    private val hub = FakeHub()
    private val watched = Watched()
    private val bot = """{"id":"b","name":"Test bot"}"""
    private fun session(): HubSession {
        hub.json("/api/auth/me", """{"user":{"id":"u"}}""")
        hub.json("/api/bots", """{"bots":[$bot],"live":[]}""")
        hub.json("/api/computer", """{"computer":{"status":"absent"}}""")
        hub.json("/api/approvals", """{"approvals":[],"serverTime":1000}""")
        return HubSession(hub.client(), watched.scope).also {
            it.start()
            waitFor("initial sync") { it.state.value.ready && hub.count("GET /api/approvals") > 0 }
            Thread.sleep(100)
        }
    }

    @After fun close() { watched.scope.cancel(); hub.close() }

    @Test fun `a live event absent from an older snapshot is retained`() = runBlocking {
        val s = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        hub.routes["GET /api/bots"] = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            FakeHub.Reply(200, """{"bots":[$bot],"live":[]}""")
        }
        val fetching = watched.scope.launch { s.loadBots() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        hub.emit("""{"type":"live","state":{"botId":"b","isRunning":true,"steps":3}}""")
        waitFor("new live event") { s.state.value.live["b"]?.isRunning == true }
        release.countDown()
        fetching.join()
        assertTrue("older snapshot erased newer live state", s.state.value.live["b"]?.isRunning == true)
        assertTrue(watched.uncaught.isEmpty())
    }

    @Test fun `a newly added bot event is not lost to an older roster`() = runBlocking {
        val s = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        hub.routes["GET /api/bots"] = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            FakeHub.Reply(200, """{"bots":[$bot],"live":[]}""")
        }
        val fetching = watched.scope.launch { s.loadBots() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        hub.emit("""{"type":"bot","bot":{"id":"new","name":"New bot"}}""")
        waitFor("new bot event") { s.state.value.bot("new") != null }
        release.countDown()
        fetching.join()
        assertNotNull("older roster erased a new bot", s.state.value.bot("new"))
        assertTrue(watched.uncaught.isEmpty())
    }

    @Test fun `an approval pushed during a stale list is not expired`() = runBlocking {
        val s = session()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        hub.routes["GET /api/approvals"] = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            FakeHub.Reply(200, """{"approvals":[],"serverTime":1000}""")
        }
        val fetching = watched.scope.launch { s.loadApprovals() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        hub.emit("""{"type":"approval","approval":{"id":"new","botId":"b","status":"pending","createdAt":1001,"expiresAt":9999999999999}}""")
        waitFor("new approval event") { s.state.value.approvals["new"]?.pending == true }
        release.countDown()
        fetching.join()
        assertTrue("older list marked a new request expired", s.state.value.approvals["new"]?.pending == true)
        assertTrue(watched.uncaught.isEmpty())
    }

    @Test fun `an older listed approval cannot overwrite newer pushed details`() = runBlocking {
        val s = session()
        hub.emit("""{"type":"approval","approval":{"id":"a","botId":"b","title":"Original","status":"pending","createdAt":1,"expiresAt":9999999999999}}""")
        waitFor("original approval") { s.state.value.approvals["a"] != null }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        hub.routes["GET /api/approvals"] = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            FakeHub.Reply(200, """{"approvals":[{"id":"a","botId":"b","title":"Original","status":"pending","createdAt":1,"expiresAt":9999999999999}],"serverTime":1000}""")
        }
        val fetching = watched.scope.launch { s.loadApprovals() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        hub.emit("""{"type":"approval","approval":{"id":"a","botId":"b","title":"Updated detail","status":"pending","createdAt":1,"expiresAt":9999999999999}}""")
        waitFor("updated approval") { s.state.value.approvals["a"]?.title == "Updated detail" }
        release.countDown()
        fetching.join()
        assertEquals("Updated detail", s.state.value.approvals["a"]?.title)
        assertTrue(s.state.value.approvals["a"]?.pending == true)
        assertTrue(watched.uncaught.isEmpty())
    }

    @Test fun `late clear event cannot regress generation or erase rows in the same generation`() {
        for (seed in 1..100) {
            val random = kotlin.random.Random(seed)
            val current = random.nextInt(1, 1000)
            val row = Row("b", current, 1, 1, "message", MessageBody(text = "new conversation"), 1)
            val state = HubState().withRows("b", current, listOf(row), loaded = true)
            repeat(20) {
                val generation = random.nextInt(0, current + 1)
                assertEquals("seed $seed: stale or duplicate clear $generation", state, state.reduce(ServerEvent.Cleared("b", generation)))
            }
            val cleared = state.reduce(ServerEvent.Cleared("b", current + 1))
            assertEquals(current + 1, cleared.transcripts.getValue("b").generation)
            assertTrue(cleared.transcripts.getValue("b").rows.isEmpty())
        }
    }
}
