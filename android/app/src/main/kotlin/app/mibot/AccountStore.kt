package app.mibot

import android.content.Context
import app.mibot.core.HubAccount

/** What this phone remembers between launches: which hub it was added to, its sign-in, and the notification choices. */
class AccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("mibot", Context.MODE_PRIVATE)

    var account: HubAccount?
        get() {
            val url = prefs.getString(URL, null) ?: return null
            val token = prefs.getString(TOKEN, null) ?: return null
            return HubAccount(url, token)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) { remove(URL); remove(TOKEN) } else { putString(URL, value.url); putString(TOKEN, value.token) }
            }.apply()
        }

    /** Notifications as a whole. On until the person turns them off. */
    var notifications: Boolean
        get() = prefs.getBoolean(NOTIFY, true)
        set(value) = prefs.edit().putBoolean(NOTIFY, value).apply()

    /** The bots this phone stays quiet about. */
    private val muted: Set<String> get() = prefs.getStringSet(MUTED, emptySet()).orEmpty()

    fun notifies(botId: String) = botId !in muted

    fun setNotifies(botId: String, on: Boolean) {
        prefs.edit().putStringSet(MUTED, if (on) muted - botId else muted + botId).apply()
    }

    private companion object {
        const val URL = "hub_url"
        const val TOKEN = "hub_token"
        const val NOTIFY = "notifications"
        const val MUTED = "muted_bots"
    }
}
