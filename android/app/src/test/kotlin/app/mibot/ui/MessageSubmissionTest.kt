package app.mibot.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MessageSubmissionTest {
    @Test fun failedSendKeepsDraftAndReply() = runBlocking {
        val submission = MessageSubmission()
        var draft = "  Keep this message  "
        var reply: Int? = 42
        try {
            submission.submit(draft, { draft = ""; reply = null }) { throw IllegalStateException("offline") }
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals("  Keep this message  ", draft)
        assertEquals(42, reply)
        assertFalse(submission.busy)
    }

    @Test fun repeatedTapCannotSendTwiceAndClearsOnlyAfterAcceptance() = runBlocking {
        val submission = MessageSubmission()
        val response = CompletableDeferred<Unit>()
        var count = 0
        var draft = "  hello  "
        val job = launch {
            submission.submit(draft, { draft = "" }) {
                assertEquals("hello", it)
                count++
                response.await()
            }
        }
        yield()
        assertTrue(submission.busy)
        assertEquals("  hello  ", draft)
        assertFalse(submission.submit(draft, { fail("duplicate acceptance") }) { count++ })
        response.complete(Unit)
        job.join()
        assertEquals(1, count)
        assertEquals("", draft)
        assertFalse(submission.busy)
    }

    @Test fun cancellationKeepsDraftAndAllowsAnotherAttempt() = runBlocking {
        val submission = MessageSubmission()
        var accepted = 0
        val job = launch { submission.submit("draft", { accepted++ }) { CompletableDeferred<Unit>().await() } }
        yield()
        job.cancel()
        job.join()
        assertEquals(0, accepted)
        assertFalse(submission.busy)
        assertTrue(submission.submit("draft", { accepted++ }) {})
        assertEquals(1, accepted)
        assertFalse(submission.submit(" \n ", { fail("blank draft") }) {})
    }
}
