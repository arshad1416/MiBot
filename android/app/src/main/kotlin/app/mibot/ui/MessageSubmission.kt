package app.mibot.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A single in-flight send. Drafts belong to the composer until the hub accepts them. */
internal class MessageSubmission {
    var busy by mutableStateOf(false)
        private set

    suspend fun submit(text: String, accepted: () -> Unit, send: suspend (String) -> Unit): Boolean {
        if (busy || text.isBlank()) return false
        busy = true
        try {
            send(text.trim())
            accepted()
            return true
        } finally {
            busy = false
        }
    }
}
