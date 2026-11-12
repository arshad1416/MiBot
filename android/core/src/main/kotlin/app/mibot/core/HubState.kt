package app.mibot.core

/** One bot's conversation as far as this device has it. `rows` are in order of `seq`. */
data class Transcript(val generation: Int, val rows: List<Row> = emptyList(), val loaded: Boolean = false, val hasEarlier: Boolean = false)

/** Everything the app shows that comes from the hub. It only changes through the functions below. */
data class HubState(
    val user: User? = null,
    val bots: List<Bot> = emptyList(),
    /** False until the bot list has been fetched once. */
    val ready: Boolean = false,
    val live: Map<String, LiveState> = emptyMap(),
    val transcripts: Map<String, Transcript> = emptyMap(),
    val routines: Map<String, List<Routine>> = emptyMap(),
    val computer: ComputerState? = null,
    /** The live connection to the hub is up. */
    val online: Boolean = false,
    /** Why it is not, in words, once it has failed. */
    val problem: String? = null,
    /** Grows whenever an app is connected or disconnected, so screens that list apps can fetch again. */
    val connectionsVersion: Int = 0,
    /** The hub no longer accepts this device's sign-in. */
    val signedOut: Boolean = false,
) {
    fun bot(id: String?): Bot? = bots.firstOrNull { it.id == id }
    val primary: Bot? get() = bots.firstOrNull { it.isPrimary }

    /** The bots under the primary one on the home screen: pinned first, then whoever was active last. */
    val others: List<Bot>
        get() = bots.filter { !it.isPrimary }.sortedWith(compareByDescending<Bot> { it.pinned }.thenByDescending { it.lastActivityAt })
}

/** Rows that arrived (from a fetch or an event), merged into what is held. An older generation is a conversation that was cleared. */
fun HubState.withRows(botId: String, generation: Int, incoming: List<Row>, loaded: Boolean? = null, hasEarlier: Boolean? = null): HubState {
    val prev = transcripts[botId]
    if (prev != null && generation < prev.generation) return this
    val base = if (prev == null || generation > prev.generation) Transcript(generation, loaded = prev?.loaded ?: false) else prev
    val bySeq = sortedMapOf<Int, Row>()
    for (r in base.rows) bySeq[r.seq] = r
    for (r in incoming) {
        val cur = bySeq[r.seq]
        if (cur == null || r.updatedSeq >= cur.updatedSeq) bySeq[r.seq] = r
    }
    val next = base.copy(generation = generation, rows = bySeq.values.toList(), loaded = loaded ?: base.loaded, hasEarlier = hasEarlier ?: base.hasEarlier)
    return copy(transcripts = transcripts + (botId to next))
}

/** What one pushed event changes. Events that call for a fetch (`Roster`, `Routines`) change nothing here. */
fun HubState.reduce(ev: ServerEvent): HubState = when (ev) {
    is ServerEvent.Rows -> withRows(ev.botId, ev.generation, ev.rows)
    is ServerEvent.Cleared -> copy(transcripts = transcripts + (ev.botId to Transcript(ev.generation, loaded = true)))
    is ServerEvent.Live -> copy(live = live + (ev.state.botId to ev.state))
    is ServerEvent.BotChanged -> copy(bots = if (bots.any { it.id == ev.bot.id }) bots.map { if (it.id == ev.bot.id) ev.bot else it } else bots + ev.bot)
    is ServerEvent.Computer -> copy(computer = ev.state)
    ServerEvent.Connections -> copy(connectionsVersion = connectionsVersion + 1)
    is ServerEvent.Hello, is ServerEvent.Routines, ServerEvent.Roster, ServerEvent.Other -> this
}

/** Something worth a notification: a bot finished, or stopped to wait for the person. */
sealed interface Notice {
    val bot: Bot
    data class Finished(override val bot: Bot, val preview: String) : Notice
    data class Waiting(override val bot: Bot, val reason: String) : Notice
}

/** The notice an event calls for, given the state before it. `openBotId` is the chat on screen, which needs none. */
fun HubState.noticeFor(ev: ServerEvent, openBotId: String?): Notice? = when (ev) {
    is ServerEvent.BotChanged -> {
        val before = bot(ev.bot.id)
        if (before != null && ev.bot.id != openBotId && ev.bot.unread > before.unread && !ev.bot.lastPreview.isNullOrBlank()) Notice.Finished(ev.bot, ev.bot.lastPreview)
        else null
    }
    is ServerEvent.Live -> {
        val bot = bot(ev.state.botId)
        val waited = live[ev.state.botId]?.awaiting != null
        val awaiting = ev.state.awaiting
        if (bot != null && awaiting != null && !waited && bot.id != openBotId) Notice.Waiting(bot, awaiting.reason.ifBlank { "Waiting for your answer" }) else null
    }
    else -> null
}
