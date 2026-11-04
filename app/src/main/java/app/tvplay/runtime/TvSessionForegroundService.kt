package app.tvplay.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.tvplay.R
import app.tvplay.ui.stage.CarPlayScreen

typealias TvPlaySessionService = TvSessionForegroundService

/**
 * Foreground service that keeps TVPlay's CarPlay projection session alive while the projection
 * activity is in the background or picture-in-picture, and provides a persistent status notification
 * with a quick disconnect action.
 */
class TvSessionForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val status = intent?.getStringExtra("status") ?: getString(R.string.waiting_for_iphone)
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            }.onFailure {
                runCatching { startForeground(NOTIFICATION_ID, notification) }
            }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(status: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    getString(R.string.carplay_connection),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val openScreen = PendingIntent.getActivity(
            this,
            0,
            CarPlayScreen.intent(this)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, TvSessionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(openScreen)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.disconnect),
                    disconnect,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "tvplay_connection"
        const val ACTION_STOP = "app.tvplay.DISCONNECT"

        fun startOrUpdate(context: Context, wireless: Boolean, status: String) {
            val intent = Intent(context, TvSessionForegroundService::class.java)
                .putExtra("wireless", wireless)
                .putExtra("status", status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startOrUpdate(context: Context, status: String) {
            startOrUpdate(context, wireless = true, status = status)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TvSessionForegroundService::class.java))
        }
    }
}
