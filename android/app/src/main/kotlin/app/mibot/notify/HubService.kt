package app.mibot.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mibot.MiBotApp

/**
 * Keeps the app's process, and with it the live line to the hub, alive while the app is closed.
 * A hub on your own machines has no push service to wake the phone, so this is how a bot that
 * finishes at night still reaches you. The session itself belongs to [MiBotApp]; this only holds the door open.
 */
class HubService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MiBotApp
        if (!wanted(app)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, ID, app.notifier.connected(), type) }.onFailure { stopSelf() }
        return START_STICKY
    }

    companion object {
        private const val ID = 1

        private fun wanted(app: MiBotApp) = app.hub != null && app.store.notifications && app.notifier.allowed

        /** Start or stop the service so that it runs exactly when this phone is signed in and notifications are on. */
        fun sync(context: Context) {
            val app = context.applicationContext as MiBotApp
            val intent = Intent(app, HubService::class.java)
            // Android refuses to start this from the background at some moments; the next time the app opens tries again.
            runCatching { if (wanted(app)) ContextCompat.startForegroundService(app, intent) else app.stopService(intent) }
        }
    }
}

/** After a restart of the phone or an update of the app, pick the line to the hub up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) HubService.sync(context)
    }
}
