package app.mibot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val PAGE = 200

/**
 * A signed-in device's live picture of the hub: it keeps one event stream open, folds what arrives
 * into [state], and fetches again whenever the stream comes back. Screens read [state] and call
 * [client] to change things; the change comes back through the stream.
 */
class HubSession(val client: HubClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    /** Things worth a notification, as they happen. */
    val notices: SharedFlow<Notice> = _notices

    private var stream: Job? = null
    /** The chat on screen: it is kept read, and fetched again after a reconnect. */
    @Volatile var openBotId: String? = null
        private set

    val isRunning get() = stream?.isActive == true

    /** Open the event stream and keep it open. Calling it again while it runs does nothing. */
    fun start() {
        if (isRunning || _state.value.signedOut) return
        stream = scope.launch {
            var wait = 1_000L
            while (isActive) {
                try {
                    client.events().collect { ev ->
                        if (ev is ServerEvent.Hello) {
                            wait = 1_000L
                            _state.update { it.copy(online = true, problem = null) }
                            launch { quietly { resync() } }
                        } else handle(ev)
                    }
                } catch (e: HubException) {
                    if (e.signedOut) {
                        _state.update { it.copy(online = false, signedOut = true, problem = e.message) }
                        return@launch
                    }
                    _state.update { it.copy(online = false, problem = e.message) }
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(15_000L)
            }
        }
    }

    /** Drop the event stream. What was fetched stays, so the app can show it again at once. */
    fun stop() {
        stream?.cancel()
        stream = null
        _state.update { it.copy(online = false) }
    }

    /** Counts events, so a fetch that started before one does not undo it. */
    private val events = java.util.concurrent.atomic.AtomicLong()
    private val eventCount get() = events.get()
    /** Per bot: the count when an event last changed it. */
    private val touched = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun handle(ev: ServerEvent) {
        _state.value.noticeFor(ev, openBotId)?.let { _notices.tryEmit(it) }
        when (ev) {
            is ServerEvent.BotChanged -> touched[ev.bot.id] = events.incrementAndGet()
            is ServerEvent.Live -> touched[ev.state.botId] = events.incrementAndGet()
            else -> {}
        }
        _state.update { it.reduce(ev) }
        when (ev) {
            ServerEvent.Roster -> quietly { loadBots() }
            is ServerEvent.Routines -> if (_state.value.routines.containsKey(ev.botId)) quietly { loadRoutines(ev.botId) }
            is ServerEvent.BotChanged -> if (ev.bot.id == openBotId && ev.bot.unread > 0) quietly { client.markRead(ev.bot.id) }
            else -> {}
        }
    }

    /** A fetch whose failure is not worth telling anyone: the stream's own state says when the hub is gone. */
    private suspend fun quietly(work: suspend () -> Unit) {
        try { work() } catch (e: HubException) {
            if (e.signedOut) _state.update { it.copy(signedOut = true) }
        }
    }

    private suspend fun resync() {
        // Chats read before the line dropped may have missed rows: each is fetched again when it is next opened.
        _state.update { st -> st.copy(transcripts = st.transcripts.mapValues { (id, t) -> if (id == openBotId) t else t.copy(loaded = false) }) }
        if (_state.value.user == null) client.me()?.let { u -> _state.update { it.copy(user = u) } }
        loadBots(announce = true)
        // Fetched before the update: `update` runs its block again whenever the state changed meanwhile, as it does while a reply streams.
        val computer = client.computer()
        _state.update { it.copy(computer = computer) }
        openBotId?.let { loadRows(it) }
        loadApprovals()
    }

    /**
     * Fetch what waits for the person, and what became of the requests this device last saw waiting:
     * after a reconnect, or when the app comes back, nothing it shows is stale.
     */
    /** The hub's time at the last approvals list. */
    @Volatile private var approvalsListedAt: Long? = null

    suspend fun loadApprovals() {
        // What is waiting, and what was decided since the last list (or since the oldest request still held as waiting).
        val since = listOfNotNull(_state.value.pendingApprovals.minOfOrNull { it.createdAt }, approvalsListedAt?.minus(60_000)).minOrNull()
        val r = client.approvals(since)
        if (r.serverTime > 0) approvalsListedAt = r.serverTime
        val before = _state.value
        _state.update { it.withApprovals(r.approvals, r.serverTime, System.currentTimeMillis()) }
        // Tell the notifications what changed while the line was down, as if each change had been pushed.
        for (a in _state.value.approvals.values) {
            val held = before.approvals[a.id]
            val bot = _state.value.bot(a.botId) ?: Bot(id = a.botId, name = a.botName)
            if (a.pending && held == null) _notices.tryEmit(Notice.ApprovalNeeded(bot, a))
            else if (!a.pending && held?.pending == true) _notices.tryEmit(Notice.ApprovalSettled(bot, a))
        }
    }

    /**
     * Answer an approval. The hub takes one answer per request, from whichever device is first; the
     * result says what stood, and an error when it was not this one.
     */
    suspend fun decide(approvalId: String, decision: String): Decision {
        val d = client.decide(approvalId, decision)
        _state.update { it.withApproval(d.approval) }
        return d
    }

    /**
     * Fetch the bot list. A bot or live state that an event changed after the fetch was asked for keeps
     * what the event said: the answer was true when the hub wrote it, the event is newer. With
     * `announce`, what changed while the line was down is told as the events would have told it.
     */
    suspend fun loadBots(announce: Boolean = false) {
        val asked = eventCount
        val r = client.bots()
        val before = _state.value
        val newer = { id: String -> (touched[id] ?: 0) > asked }
        _state.update { st ->
            val bots = r.bots.map { b -> if (newer(b.id)) st.bot(b.id) ?: b else b }
            val live = r.live.associateBy { it.botId }.mapValues { (id, l) -> if (newer(id)) st.live[id] ?: l else l }
            st.copy(bots = bots, live = live, ready = true)
        }
        if (announce && before.ready) {
            for (b in r.bots) if (!newer(b.id)) before.noticeFor(ServerEvent.BotChanged(b), openBotId)?.let { _notices.tryEmit(it) }
            for (l in r.live) if (!newer(l.botId)) before.noticeFor(ServerEvent.Live(l), openBotId)?.let { _notices.tryEmit(it) }
        }
    }

    suspend fun loadRows(botId: String) {
        val r = client.rows(botId)
        _state.update { st ->
            val held = st.transcripts[botId]
            // More than a page was said since what is held: keep the new page alone, or the gap between could never be fetched.
            val gap = held != null && held.rows.isNotEmpty() && r.rows.isNotEmpty() && r.generation == held.generation && r.rows.first().seq > held.rows.last().seq + 1
            val base = if (gap) st.copy(transcripts = st.transcripts - botId) else st
            base.withRows(botId, r.generation, r.rows, loaded = true, hasEarlier = r.rows.size >= PAGE)
        }
    }

    /** Fetch the page of rows before the oldest one held. */
    suspend fun loadEarlier(botId: String) {
        val first = _state.value.transcripts[botId]?.rows?.firstOrNull() ?: return
        val r = client.rows(botId, beforeSeq = first.seq)
        _state.update { it.withRows(botId, r.generation, r.rows, hasEarlier = r.rows.size >= PAGE) }
    }

    suspend fun loadRoutines(botId: String) {
        val list = client.routines(botId)
        _state.update { it.copy(routines = it.routines + (botId to list)) }
    }

    /** A chat came on screen: fetch it if needed and mark it read. */
    fun openBot(botId: String) {
        openBotId = botId
        scope.launch {
            quietly {
                if (_state.value.transcripts[botId]?.loaded != true) loadRows(botId)
                if ((_state.value.bot(botId)?.unread ?: 0) > 0) client.markRead(botId)
            }
        }
    }

    fun closeBot(botId: String) {
        if (openBotId == botId) openBotId = null
    }
}
