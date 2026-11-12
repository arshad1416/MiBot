package app.mibot.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mibot.MiBotApp
import app.mibot.core.VoiceCall
import app.mibot.core.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The one voice call the app can have at a time, and the levels its bars are drawn from. */
class VoiceController(private val app: MiBotApp) {
    private var call: VoiceCall? = null
    private var audio: PhoneAudio? = null
    private var watcher: Job? = null

    /** What the call is doing, for the screen. Idle when there is none. */
    var state by mutableStateOf(VoiceState())
        private set

    val micLevel get() = audio?.micLevel ?: 0f
    val outLevel get() = audio?.outLevel ?: 0f

    /** Call the bot. The microphone permission must already be granted. */
    fun start(botId: String) {
        val session = app.hub ?: return
        end()
        val sound = PhoneAudio(app)
        val next = VoiceCall(session, botId, sound, app.scope)
        audio = sound
        call = next
        watcher = app.scope.launch { next.state.collect { state = it } }
        next.start()
    }

    fun setMuted(muted: Boolean) {
        call?.setMuted(muted)
    }

    /** Hang up, or clear the note left by a call that failed. */
    fun end() {
        call?.end()
        watcher?.cancel()
        call = null
        audio = null
        watcher = null
        state = VoiceState()
    }
}
