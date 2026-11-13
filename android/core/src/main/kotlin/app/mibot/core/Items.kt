package app.mibot.core

/** What a conversation is drawn from: rows turned into dividers, runs of bubbles, folded steps, and single rows. */
sealed interface ChatItem {
    val key: String

    data class Divider(override val key: String, val at: Long) : ChatItem

    /** Messages in a row from one sender. `mine`: the person's own, drawn on the right. */
    data class Group(override val key: String, val mine: Boolean, val from: Sender?, val rows: List<Row>) : ChatItem

    /** A run of tool calls, shown as one line that opens. `startAt`/`endAt`: the rows just before and just after it. */
    data class Steps(override val key: String, val rows: List<Row>, val startAt: Long?, val endAt: Long?) : ChatItem {
        val calls: List<ToolCallBody> get() = rows.map { it.body as ToolCallBody }

        /** How long the run took, when it is finished and the answer is believable. */
        val workedMs: Long?
            get() {
                val start = startAt ?: return null
                val ms = (endAt ?: rows.last().createdAt) - start
                return ms.takeIf { it in 1_000 until MAX_WORK_MS }
            }
    }

    /** A card, an event line or a notice. */
    data class Single(override val key: String, val row: Row) : ChatItem
}

private const val DIVIDER_GAP = 60 * 60 * 1000L
private const val GROUP_GAP = 5 * 60 * 1000L
private const val MAX_WORK_MS = 12 * 60 * 60 * 1000L

private fun senderOf(b: MessageBody) = b.from?.let { "${it.kind}:${it.id}" } ?: b.role

fun buildItems(rows: List<Row>): List<ChatItem> {
    val items = ArrayList<ChatItem>()
    var prevAt = 0L
    for (row in rows) {
        val b = row.body
        if (b is UnknownBody) continue
        if (prevAt == 0L || row.createdAt - prevAt > DIVIDER_GAP) items += ChatItem.Divider("d${row.seq}", row.createdAt)
        val before = prevAt
        prevAt = row.createdAt
        val last = items.lastOrNull()
        if (b is ToolCallBody) {
            if (last is ChatItem.Steps) items[items.lastIndex] = last.copy(rows = last.rows + row)
            else items += ChatItem.Steps("s${row.seq}", listOf(row), before.takeIf { it > 0 }, null)
            continue
        }
        if (last is ChatItem.Steps && last.endAt == null) items[items.lastIndex] = last.copy(endAt = row.createdAt)
        if (b is MessageBody) {
            if (b.text.isEmpty() && !b.streaming) continue
            val mine = b.isUser && (b.from == null || b.from.kind == "voice")
            val tail = items.lastOrNull()
            val lastRow = (tail as? ChatItem.Group)?.rows?.last()
            if (tail is ChatItem.Group && lastRow != null && senderOf(lastRow.body as MessageBody) == senderOf(b) && row.createdAt - lastRow.createdAt < GROUP_GAP) {
                items[items.lastIndex] = tail.copy(rows = tail.rows + row)
            } else items += ChatItem.Group("g${row.seq}", mine, b.from, listOf(row))
            continue
        }
        items += ChatItem.Single("r${row.seq}", row)
    }
    return items
}

/** Where the message with this seq is drawn in `items`, for jumping to the message a reply quotes; -1 when it is not loaded. */
fun indexOfMessage(items: List<ChatItem>, seq: Int) =
    items.indexOfFirst { it is ChatItem.Group && it.rows.any { r -> r.seq == seq } }

/** Steps taken since the person last wrote: what the hub's count for the running turn is compared with. */
fun stepsThisTurn(rows: List<Row>): Int {
    var n = 0
    for (i in rows.indices.reversed()) {
        val b = rows[i].body
        if (b is MessageBody && b.isUser) break
        if (b is ToolCallBody) n++
    }
    return n
}
