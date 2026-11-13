package app.mibot.core.fuzz

import app.mibot.core.Activity
import app.mibot.core.Awaiting
import app.mibot.core.Bot
import app.mibot.core.LiveState
import app.mibot.core.Row
import app.mibot.core.TimeText
import app.mibot.core.ToolCallBody
import app.mibot.core.activityPhrase
import app.mibot.core.activityStatus
import app.mibot.core.appName
import app.mibot.core.isOnHold
import app.mibot.core.isWaitingForYou
import app.mibot.core.previewFor
import app.mibot.core.toolName
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

/** The words and times the screens print. They are called while drawing, so a throw here is a crash. */
class TextFuzzTest {
    private val zones = listOf("UTC", "America/Toronto", "Asia/Kolkata", "Pacific/Kiritimati", "Pacific/Apia", "Europe/London", "Australia/Lord_Howe", "America/St_Johns").map(ZoneId::of)
    private val kinds = listOf(
        "thinking", "working", "searching_web", "reading_web", "browsing_web", "on_its_computer", "running_commands", "waiting_on_command", "messaging_named",
        "connecting_service", "waiting_for_you", "drafting_file", "remembering", "waiting_for_machine", "paused", "", "zz_new_kind", "THINKING",
    )

    private fun Random.ms(): Long = when (nextInt(10)) {
        0 -> 0L
        1 -> Long.MAX_VALUE
        2 -> Long.MIN_VALUE
        3 -> -nextLong(0, Long.MAX_VALUE)
        4 -> nextLong()
        5 -> nextLong(-1000, 1000)
        else -> 1_794_500_000_000L + nextLong(-800L * 86_400_000, 800L * 86_400_000)
    }

    @Test fun `times - any moment, any zone, never throws and always says something`() {
        repeat(20_000) { n ->
            val r = Random(n)
            val ms = r.ms()
            val now = r.ms()
            val zone = zones[r.nextInt(zones.size)]
            val why = "seed $n ms=$ms now=$now zone=$zone"
            try {
                assertTrue(why, TimeText.clock(ms, zone).isNotEmpty())
                assertTrue(why, TimeText.divider(ms, now, zone).isNotEmpty())
                val list = TimeText.listTime(ms, now, zone)
                assertEquals(why, ms <= 0, list.isEmpty())
                val rel = TimeText.relative(ms, now)
                assertTrue("$why relative=$rel", Regex("now|\\d+m|\\d+h|Yesterday|\\d+d").matches(rel))
                val d = TimeText.duration(ms)
                assertTrue("$why duration=$d", Regex("\\d+s|\\d+m \\d+s|\\d+h \\d+m").matches(d))
                if (ms < 1000) assertEquals(why, "0s", d)
                val call = TimeText.callLength(ms)
                assertTrue("$why callLength=$call", Regex("\\d{2,}:\\d{2}").matches(call))
                val size = TimeText.size(ms)
                assertTrue("$why size=$size", Regex("-?\\d+ B|\\d+ KB|\\d+\\.\\d MB|\\d+\\.\\d GB").matches(size))
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("$why threw $e", e)
            }
        }
    }

    @Test fun `times - today, yesterday and the days before read the same at any hour and in any zone`() {
        repeat(5000) { n ->
            val r = Random(50_000 + n)
            val zone = zones[r.nextInt(zones.size)]
            val now = 1_794_500_000_000L + r.nextLong(-800L * 86_400_000, 800L * 86_400_000)
            assertTrue(TimeText.divider(now, now, zone).startsWith("Today "))
            assertEquals(TimeText.clock(now, zone), TimeText.listTime(now, now, zone))
            // The whole of a turn (a second to an hour ago) that is still today says Today.
            val recent = now - r.nextLong(0, 3_600_000)
            val d = TimeText.divider(recent, now, zone)
            assertTrue("seed ${50_000 + n}: $d", d.startsWith("Today ") || d.startsWith("Yesterday "))
            assertEquals("5m", TimeText.relative(now - 5 * 60_000 - r.nextLong(0, 60_000), now))
            assertEquals("now", TimeText.relative(now + r.nextLong(0, 86_400_000), now)) // the hub's clock runs ahead of the phone's
        }
    }

    /**
     * The call timer and file sizes are written with `String.format`, which follows the phone's language, digits included.
     * Everything else the app prints (the clock, "12m", "1h 4m", "812 B") is fixed to Western digits, so on a phone set to
     * Arabic, Persian, Bengali or Nepali these two alone come out in another script, next to the rest.
     */
    @Test fun `the call timer and file sizes use the same digits as every other number in the app, whatever language the phone is set to`() {
        val before = Locale.getDefault()
        val wrong = ArrayList<String>()
        try {
            for (tag in listOf("en-US", "fr-FR", "de-DE", "ar-SA", "ar-EG", "fa-IR", "hi-IN", "bn-BD", "ne-NP", "my-MM")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val others = "${TimeText.clock(1_794_500_000_000L, ZoneId.of("UTC"))}, ${TimeText.duration(83_000)}, ${TimeText.size(812)}"
                val call = TimeText.callLength(7_000)
                if (call != "00:07") wrong += "$tag: callLength(7000) = $call (beside $others)"
                val size = TimeText.size(4_404_019)
                if (size.any { it.isDigit() && it !in '0'..'9' }) wrong += "$tag: size(4404019) = $size (beside $others)"
            }
        } finally {
            Locale.setDefault(before)
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    private fun Random.args() = when (nextInt(8)) {
        0 -> null
        1 -> JsonNull
        2 -> JsonPrimitive(oddString())
        3 -> JsonArray(listOf(JsonPrimitive("bot_name")))
        4 -> JsonObject(mapOf("bot_name" to JsonPrimitive(oddString())))
        5 -> JsonObject(mapOf("bot_name" to listOf(JsonNull, JsonPrimitive(3), JsonPrimitive(true), JsonArray(listOf(JsonPrimitive("Scout"), JsonPrimitive("Pip"))), JsonObject(mapOf("name" to JsonPrimitive("Scout"))))[nextInt(5)]))
        6 -> JsonObject(mapOf("message" to JsonPrimitive("hi")))
        else -> anyJson()
    }

    private fun Random.liveState() = LiveState(
        "b1", isRunning = nextBoolean(),
        activity = if (nextInt(5) == 0) null else Activity(kinds[nextInt(kinds.size)], when (nextInt(4)) { 0 -> null; 1 -> ""; 2 -> "Gmail"; else -> oddString() }, ms()),
        awaiting = if (nextInt(4) == 0) Awaiting(oddString(), ms()) else null, caption = if (nextBoolean()) oddString() else null, steps = nextInt(),
    )

    @Test fun `what a bot is doing - any state, any rows, any tick, never throws and always says something`() {
        repeat(20_000) { n ->
            val r = Random(100_000 + n)
            val live = r.liveState()
            val rows = List(r.nextInt(0, 6)) { i ->
                val body = if (r.nextInt(3) == 0) r.body() else ToolCallBody("c$i", listOf("send_to_bot", "terminal", r.oddString())[r.nextInt(3)], status = listOf("running", "done", "zz")[r.nextInt(3)], args = r.args())
                Row("b1", 1, i, i, "", body, r.ms())
            }
            val tick = listOf(0, 1, -1, Int.MAX_VALUE, Int.MIN_VALUE, r.nextInt())[r.nextInt(6)]
            val why = "seed ${100_000 + n}: live=$live rows=${clip(rows.toString(), 400)} tick=$tick"
            try {
                assertTrue(why, activityPhrase(live, rows, tick).isNotEmpty())
                assertTrue(why, activityStatus(live, rows).isNotEmpty())
                assertTrue(why, previewFor(Bot("b1", lastPreview = if (r.nextBoolean()) r.oddString() else null), if (r.nextBoolean()) live else null).isNotEmpty() || live.awaiting != null)
                live.isWaitingForYou; live.isOnHold
                (null as LiveState?).isWaitingForYou; (null as LiveState?).isOnHold
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("$why threw $e", e)
            }
        }
    }

    /**
     * The smallest case of the above. `send_to_bot`'s arguments come from the model and the hub stores them unchecked
     * (server/src/agent/loop.ts keeps `args` as parsed), so `bot_name` is whatever the model wrote. The web app
     * asks `typeof args?.bot_name === 'string'` first; the chat screen calls these two while it draws.
     */
    @Test fun `checking with another bot - a bot_name that is not text does not throw`() {
        val live = LiveState("b1", isRunning = true, activity = Activity("messaging_named", "Scout", 1))
        for (name in listOf(JsonArray(listOf(JsonPrimitive("Scout"), JsonPrimitive("Pip"))), JsonObject(mapOf("name" to JsonPrimitive("Scout"))), JsonArray(emptyList()))) {
            val rows = listOf(Row("b1", 1, 1, 1, "tool-call", ToolCallBody("c1", "send_to_bot", "Messaging", "running", JsonObject(mapOf("bot_name" to name, "message" to JsonPrimitive("hi")))), 1))
            val phrase = try { activityPhrase(live, rows, 0) } catch (e: Throwable) { throw AssertionError("activityPhrase threw $e with bot_name=$name", e) }
            val status = try { activityStatus(live, rows) } catch (e: Throwable) { throw AssertionError("activityStatus threw $e with bot_name=$name", e) }
            assertEquals("Checking with Scout", phrase)
            assertEquals("Checking with Scout", status)
        }
    }

    @Test fun `app and tool names - any text, never throws, and plain names read as written`() {
        repeat(20_000) { n ->
            val r = Random(200_000 + n)
            val s = when (r.nextInt(4)) { 0 -> r.unicode(20); 1 -> r.ascii(20); 2 -> r.oddString().take(3000); else -> listOf("gmail", "GOOGLE", "google", "microsoft", "googlecalendar", "Microsoft365", "a-b_c", "_", "-", "__--__", " ")[r.nextInt(11)] }
            try {
                appName(s)
                toolName(s)
            } catch (e: Throwable) {
                throw AssertionError("seed ${200_000 + n}: ${clip(s, 80)} threw $e", e)
            }
        }
        assertEquals("", appName(""))
        assertEquals("", toolName(""))
        assertEquals("Slack", appName("slack"))
        assertEquals("Google Calendar", appName("GoogleCalendar"))
        assertEquals("Google Maps", appName("google-maps"))
        assertEquals("One Drive", appName("one_drive"))
        assertEquals("\$1", appName("\$1"))
    }
}
