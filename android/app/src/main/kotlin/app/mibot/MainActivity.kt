package app.mibot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.mibot.notify.HubService
import app.mibot.ui.MiBotTheme
import app.mibot.ui.Root

class MainActivity : ComponentActivity() {
    private val app get() = application as MiBotApp

    /** A chat to open: the one whose notification was tapped. */
    private val openBot = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openBot.value = intent?.getStringExtra(EXTRA_BOT)
        setContent { MiBotTheme { Root(app, openBot) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_BOT)?.let { openBot.value = it }
    }

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
    }
}
