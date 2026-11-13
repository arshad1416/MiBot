package app.mibot.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.mibot.MainActivity
import app.mibot.MiBotApp
import app.mibot.R
import app.mibot.core.Notice
import app.mibot.ui.avatarBitmap
import app.mibot.ui.commandLines

/** Says on the phone what a bot would otherwise only say in its chat: it finished, or it is waiting for you. */
class Notifier(private val app: MiBotApp) {
    private val manager = NotificationManagerCompat.from(app)

    init {
        manager.createNotificationChannel(NotificationChannel(BOTS, "Bots", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "A bot finished, or is waiting for you."
        })
        manager.createNotificationChannel(NotificationChannel(APPROVALS, "Requests to approve", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A bot asks before doing something. Tapping opens the request; it is never approved from the notification."
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

    private fun openApproval(approvalId: String): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_APPROVAL, approvalId)
        }
        return PendingIntent.getActivity(app, approvalId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(notice: Notice) {
        val bot = notice.bot
        when (notice) {
            // Answered here or elsewhere, or ran out: nothing left to tap.
            is Notice.ApprovalSettled -> {
                manager.cancel(APPROVAL_TAG, notice.approval.id.hashCode())
                // A "Waiting for you" about the same bot is stale too, whichever device answered.
                if (waiting.remove(bot.id)) manager.cancel(bot.id.hashCode())
                return
            }
            // A request goes through even for a bot that is muted: it holds the bot's work until someone answers.
            is Notice.ApprovalNeeded -> return if (app.store.notifications && allowed) approval(notice) else Unit
            else -> {}
        }
        if (!app.store.notifications || !app.store.notifies(bot.id) || !allowed) return
        val text = when (notice) {
            is Notice.Finished -> notice.preview
            is Notice.Waiting -> "Waiting for you: ${notice.reason}"
            is Notice.ApprovalNeeded, is Notice.ApprovalSettled -> return
        }
        val dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val n = NotificationCompat.Builder(app, BOTS)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setLargeIcon(avatarBitmap(bot.avatarShape, bot.avatarColor, dark, LARGE_ICON_PX))
            .setContentTitle(bot.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChat(bot.id))
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(bot.id.hashCode(), n) }
        // The hub's last line for a bot held up on a request also reads "Waiting for you: …".
        if (notice is Notice.Waiting || text.startsWith("Waiting for you")) waiting += bot.id else waiting -= bot.id
    }

    /** Bots whose notification now says they are waiting for the person. */
    private val waiting = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun approval(notice: Notice.ApprovalNeeded) {
        val a = notice.approval
        val details = commandLines(a)
        val text = if (details.isBlank()) a.title else "${a.title}\n$details"
        val dark = (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val n = NotificationCompat.Builder(app, APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setLargeIcon(avatarBitmap(notice.bot.avatarShape, notice.bot.avatarColor, dark, LARGE_ICON_PX))
            .setContentTitle("${a.botName.ifBlank { notice.bot.name }} asks first")
            .setContentText(a.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Opens the request; there are no Approve or Deny buttons on the notification itself.
            .setContentIntent(openApproval(a.id))
            .setAutoCancel(true)
            .apply { if (a.expiresAt > 0) setTimeoutAfter((a.expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000)) }
            .build()
        runCatching { manager.notify(APPROVAL_TAG, a.id.hashCode(), n) }
    }

    /** The chat came on screen: what was said about it has been seen. */
    fun seen(botId: String) { waiting -= botId; manager.cancel(botId.hashCode()) }

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
        const val APPROVALS = "approvals"
        const val APPROVAL_TAG = "approval"
        const val LARGE_ICON_PX = 192
    }
}
