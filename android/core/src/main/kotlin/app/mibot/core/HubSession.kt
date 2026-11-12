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

    private suspend fun handle(ev: ServerEvent) {
        _state.value.noticeFor(ev, openBotId)?.let { _notices.tryEmit(it) }
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
        if (_state.value.user == null) client.me()?.let { u -> _state.update { it.copy(user = u) } }
        loadBots()
        _state.update { it.copy(computer = client.computer()) }
        openBotId?.let { loadRows(it) }
    }

    suspend fun loadBots() {
        val r = client.bots()
        _state.update { it.copy(bots = r.bots, live = r.live.associateBy { l -> l.botId }, ready = true) }
    }

    suspend fun loadRows(botId: String) {
        val r = client.rows(botId)
        _state.update { it.withRows(botId, r.generation, r.rows, loaded = true, hasEarlier = r.rows.size >= PAGE) }
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
