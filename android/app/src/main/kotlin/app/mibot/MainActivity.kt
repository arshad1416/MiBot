package app.mibot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.mibot.notify.HubService
import app.mibot.ui.Dest
import app.mibot.ui.MiBotTheme
import app.mibot.ui.Root

class MainActivity : ComponentActivity() {
    private val app get() = application as MiBotApp

    /** What a tapped notification is about: a chat, or a request waiting for an answer. Never answers anything itself. */
    private val opening = mutableStateOf<Dest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after a rotation the screen that was open stays.
        if (savedInstanceState == null) opening.value = target(intent)
        setContent { MiBotTheme { Root(app, opening) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        target(intent)?.let { opening.value = it }
    }

    private fun target(intent: Intent?): Dest? =
        intent?.getStringExtra(EXTRA_APPROVAL)?.let { Dest.Approvals(it) } ?: intent?.getStringExtra(EXTRA_BOT)?.let { Dest.Chat(it) }

    override fun onStart() {
        super.onStart()
        app.visible = true
        app.hub?.start()
        HubService.sync(this)
    }

    override fun onStop() {
        app.visible = false
        // A call needs the microphone, which Android takes away from an app that is not on screen.
        app.voice.end()
        super.onStop()
    }

    companion object {
        const val EXTRA_BOT = "app.mibot.bot"
        const val EXTRA_APPROVAL = "app.mibot.approval"
    }
}
