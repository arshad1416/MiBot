package app.mibot.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mibot.MainActivity
import app.mibot.MiBotApp
import app.mibot.R
import app.mibot.core.Notice

/** Says on the phone what a bot would otherwise only say in its chat: it finished, or it is waiting for you. */
class Notifier(private val app: MiBotApp) {
    private val manager = NotificationManagerCompat.from(app)

    init {
        manager.createNotificationChannel(NotificationChannel(BOTS, "Bots", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A bot finished, or is waiting for you."
        })
        manager.createNotificationChannel(NotificationChannel(LINE, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while MiBot stays connected to your hub. Safe to hide."
            setShowBadge(false)
        })
    }

    val allowed: Boolean
        get() = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()

    private fun openChat(botId: String?): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (botId != null) putExtra(MainActivity.EXTRA_BOT, botId)
        }
        return PendingIntent.getActivity(app, botId?.hashCode() ?: 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(notice: Notice) {
        val bot = notice.bot
        if (!app.store.notifications || !app.store.notifies(bot.id) || !allowed) return
        val text = when (notice) {
            is Notice.Finished -> notice.preview
            is Notice.Waiting -> "Waiting for you: ${notice.reason}"
        }
        val n = NotificationCompat.Builder(app, BOTS)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle(bot.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(bot.id))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(bot.id.hashCode(), n) }
    }

    /** The chat came on screen: what was said about it has been seen. */
    fun seen(botId: String) = manager.cancel(botId.hashCode())

    fun clear() = manager.cancelAll()

    /** The quiet, lasting notice Android requires while the line to the hub is held open in the background. */
    fun connected(): Notification = NotificationCompat.Builder(app, LINE)
        .setSmallIcon(R.drawable.ic_stat_bot)
        .setContentTitle("Connected to your hub")
        .setContentText("Your bots can reach you here.")
        .setOngoing(true)
        .setShowWhen(false)
        .setContentIntent(openChat(null))
        .build()

    private companion object {
        const val BOTS = "bots"
        const val LINE = "connection"
    }
}
