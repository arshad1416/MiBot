package app.mibot

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import app.mibot.core.HubSession
import app.mibot.notify.HubService
import app.mibot.notify.Notifier
import app.mibot.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one object that lives as long as the app's process: the sign-in, the live session with the
 * hub, the voice call if there is one. Screens read [hub]; it is null until this phone has been added.
 */
class MiBotApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: AccountStore
        private set
    lateinit var notifier: Notifier
        private set
    lateinit var voice: VoiceController
        private set

    var hub by mutableStateOf<HubSession?>(null)
        private set
    private var sessionJobs: Job? = null

    /** The app is on screen. Set by the activity. */
    var visible = false

    override fun onCreate() {
        super.onCreate()
        store = AccountStore(this)
        notifier = Notifier(this)
        voice = VoiceController(this)
        store.account?.let(::open)
    }

    private fun open(account: HubAccount) {
        val session = HubSession(HubClient(account, userAgent = USER_AGENT), scope)
        hub = session
        session.start()
        sessionJobs = scope.launch {
            launch { session.notices.collect { notifier.show(it) } }
            // The hub no longer knows this phone (signed out from another device, or the sign-in ran out).
            session.state.first { it.signedOut }
            close()
        }
        HubService.sync(this)
    }

    /** This phone was just added to a hub. */
    fun signIn(account: HubAccount) {
        store.account = account
        open(account)
    }

    fun signOut() {
        val client = hub?.client
        close()
        if (client != null) scope.launch { runCatching { client.signOut() } }
    }

    private fun close() {
        voice.end()
        hub?.stop()
        hub = null
        store.account = null
        notifier.clear()
        HubService.sync(this)
        sessionJobs?.cancel()
        sessionJobs = null
    }

    companion object {
        /** "Android" and "Mobile" let the hub name this device as a phone. */
        val USER_AGENT = "MiBot-Android/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) Mobile"
    }
}
