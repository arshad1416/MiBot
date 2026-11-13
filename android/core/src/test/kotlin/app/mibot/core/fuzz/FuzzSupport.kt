package app.mibot.core.fuzz

import app.mibot.core.ApprovalCard
import app.mibot.core.ConnectCard
import app.mibot.core.DraftCard
import app.mibot.core.EventBody
import app.mibot.core.MessageBody
import app.mibot.core.NoticeBody
import app.mibot.core.QuestionCard
import app.mibot.core.Row
import app.mibot.core.RowBody
import app.mibot.core.Sender
import app.mibot.core.ToolCallBody
import app.mibot.core.UnknownBody
import app.mibot.core.VoiceCallRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/*
 * What the fuzz tests share: odd strings, random JSON, edits to a JSON tree by path, and random rows.
 * Everything takes a kotlin.random.Random made from a fixed seed, so a failure can be run again.
 */

internal val ODD_STRINGS = listOf(
    "", " ", "\t\n\r", "\u0000", "😀", "\uD800", "\uDC00x", "á́́", "‮abc‬", "ﷺ", "﻿",
    "  ", "null", "true", "false", "0", "-1", "1e400", "NaN", "\\", "\"", "'", "{}", "[]", "</script>", "%s %d %n", "%",
    "İstanbul", "ß", "ǆ", "ı", " ", "​", "👨‍👩‍👧", "ＡＢＣ", "٠١٢٣", "data:image/png;base64,",
    "$1", "\$", "a_b-c", "GMAIL_SEND_EMAIL", "google_maps", "microsoft_teams",
)

internal fun Random.unicode(max: Int = 12): String {
    val n = nextInt(max + 1)
    val sb = StringBuilder(n)
    repeat(n) { sb.append(nextInt(0, 0x10000).toChar()) } // any UTF-16 unit, lone surrogates included
    return sb.toString()
}

internal fun Random.ascii(max: Int = 12): String {
    val n = nextInt(max + 1)
    val sb = StringBuilder(n)
    repeat(n) { sb.append(nextInt(0x20, 0x7F).toChar()) }
    return sb.toString()
}

/** A string a hub could send where it sends a string: empty, huge, odd unicode, or short text. It never names a row or event type. */
internal fun Random.oddString(): String = when (nextInt(10)) {
    0 -> ""
    1, 2 -> ODD_STRINGS[nextInt(ODD_STRINGS.size)]
    3 -> "x".repeat(nextInt(1, 20_000))
    4 -> "😀".repeat(nextInt(1, 2_000))
    5, 6 -> unicode()
    else -> "~" + ascii()
}

internal fun Random.anyJson(depth: Int = 0): JsonElement = when (nextInt(if (depth > 3) 6 else 8)) {
    0 -> JsonNull
    1 -> JsonPrimitive(nextBoolean())
    2 -> JsonPrimitive(nextInt(-5, 500))
    3 -> JsonPrimitive(nextDouble() * 1e6 - 5e5)
    4 -> JsonPrimitive(oddString())
    5 -> JsonPrimitive(nextLong())
    6 -> JsonArray(List(nextInt(4)) { anyJson(depth + 1) })
    else -> JsonObject((0 until nextInt(4)).associate { "k$it" to anyJson(depth + 1) })
}

internal fun parse(json: String): JsonElement = Json.parseToJsonElement(json)

/** Every node under this one, as the keys and indexes that lead to it. The root itself is the empty path. */
internal fun JsonElement.paths(prefix: List<Any> = emptyList()): List<List<Any>> {
    val out = ArrayList<List<Any>>()
    out.add(prefix)
    when (this) {
        is JsonObject -> for ((k, v) in this) out.addAll(v.paths(prefix + k))
        is JsonArray -> forEachIndexed { i, v -> out.addAll(v.paths(prefix + i)) }
        else -> {}
    }
    return out
}

internal fun JsonElement.at(path: List<Any>): JsonElement? {
    var cur: JsonElement? = this
    for (p in path) cur = when (cur) {
        is JsonObject -> cur[p as String]
        is JsonArray -> cur.getOrNull(p as Int)
        else -> null
    }
    return cur
}

/** This tree with the node at `path` replaced by what `f` makes of it; null from `f` takes the node out. */
internal fun JsonElement.edit(path: List<Any>, f: (JsonElement) -> JsonElement?): JsonElement {
    if (path.isEmpty()) return f(this) ?: JsonNull
    val head = path.first()
    val rest = path.drop(1)
    return when (this) {
        is JsonObject -> {
            val key = head as String
            val child = this[key] ?: return this
            if (rest.isEmpty()) {
                val next = f(child)
                JsonObject(if (next == null) this - key else this + (key to next))
            } else JsonObject(this + (key to child.edit(rest, f)))
        }
        is JsonArray -> {
            val i = head as Int
            val child = getOrNull(i) ?: return this
            if (rest.isEmpty()) {
                val next = f(child)
                JsonArray(if (next == null) filterIndexed { j, _ -> j != i } else mapIndexed { j, v -> if (j == i) next else v })
            } else JsonArray(mapIndexed { j, v -> if (j == i) child.edit(rest, f) else v })
        }
        else -> this
    }
}

/** A path with indexes blanked: `rows / * / body / type`. */
internal fun pattern(path: List<Any>): String = path.joinToString("/") { if (it is Int) "*" else it.toString() }

internal fun clip(s: String, n: Int = 300) = if (s.length > n) s.take(n) + "…(${s.length} chars)" else s

// ---------- random rows, as objects ----------

internal fun Random.sender(): Sender? = when (nextInt(6)) {
    0 -> Sender("bot", "b${nextInt(3)}", "Other")
    1 -> Sender("routine", "r${nextInt(2)}", "Morning")
    2 -> Sender("voice", "call_${nextInt(2)}", "Voice call")
    3 -> Sender(oddString(), oddString(), oddString())
    else -> null
}

internal fun Random.body(): RowBody = when (nextInt(14)) {
    0, 1, 2, 3 -> MessageBody(
        role = when (nextInt(5)) { 0, 1 -> "user"; 2, 3 -> "assistant"; else -> oddString() },
        text = when (nextInt(5)) { 0 -> ""; 1 -> oddString(); else -> "text ${nextInt(1000)}" },
        streaming = nextInt(5) == 0, from = sender(),
    )
    4, 5, 6 -> ToolCallBody(
        callId = "c${nextInt(1000)}", name = if (nextBoolean()) "terminal" else oddString(), label = oddString(),
        status = listOf("running", "done", "error", "awaiting-approval", "denied", "zz-new")[nextInt(6)],
        args = if (nextBoolean()) anyJson() else null, summary = if (nextBoolean()) oddString() else null,
    )
    7 -> ApprovalCard("ap${nextInt(100)}", oddString(), oddString(), "GMAIL_SEND", status = listOf("pending", "allowed", "always", "denied", "expired", "zz")[nextInt(6)])
    8 -> QuestionCard(oddString(), List(nextInt(4)) { oddString() }, listOf("pending", "answered", "dismissed", "zz")[nextInt(4)], if (nextBoolean()) "A" else null)
    9 -> ConnectCard("gmail", oddString(), null, oddString(), listOf("pending", "connected", "skipped", "zz")[nextInt(4)])
    10 -> DraftCard(listOf("email", "slack", "message", "zz")[nextInt(4)], oddString(), null, oddString(), "ap1", listOf("pending", "sent", "discarded", "zz")[nextInt(4)])
    11 -> EventBody(listOf("routine-run", "voice-call", "conversation-cleared", "zz")[nextInt(4)], oddString(), null, if (nextBoolean()) VoiceCallRecord("v1", "ended", nextLong()) else null)
    12 -> NoticeBody(oddString(), listOf("info", "warn", "error", "zz")[nextInt(4)])
    else -> UnknownBody
}

internal fun Random.time(): Long = when (nextInt(12)) {
    0 -> 0L
    1 -> -nextLong(1, 1_000_000_000_000L)
    2 -> Long.MAX_VALUE
    3 -> Long.MIN_VALUE
    else -> 1_794_500_000_000L + nextLong(-400L * 86_400_000, 400L * 86_400_000)
}

/** Rows the way HubState holds them: one bot, one generation, `seq` strictly rising. Times mostly rise too, with jumps. */
internal fun Random.transcript(maxRows: Int = 40, wildTimes: Boolean = true): List<Row> {
    var seq = nextInt(-3, 50)
    var at = 1_794_500_000_000L
    return List(nextInt(maxRows + 1)) {
        seq += nextInt(1, 4)
        at += when (nextInt(8)) { 0 -> 0L; 1 -> nextLong(3_600_001, 90_000_000); 2 -> nextLong(300_000, 3_600_000); else -> nextLong(0, 300_000) }
        Row("b1", 1, seq, seq + nextInt(3), "", body(), if (wildTimes && nextInt(15) == 0) time() else at)
    }
}
