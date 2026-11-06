package app.tvplay.runtime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import app.tvplay.R
import app.tvplay.ui.stage.CarPlayScreen

typealias TvPlaySessionService = TvSessionForegroundService

/**
 * Foreground service that keeps TVPlay's CarPlay projection session alive while the projection
 * activity is in the background or picture-in-picture, and provides a persistent status notification
 * with a quick disconnect action.
 */
class TvSessionForegroundService : Service() {
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiPerformanceLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseKeepAliveLocks()
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        acquireKeepAliveLocks()
        val status = intent?.getStringExtra("status") ?: getString(R.string.waiting_for_iphone)
        val notification = buildNotification(status)
        val startedForeground = if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            if (
                Build.VERSION.SDK_INT >= 30 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    types,
                )
                true
            }.recoverCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                true
            }.getOrDefault(false)
        } else {
            runCatching {
                startForeground(NOTIFICATION_ID, notification)
                true
            }.getOrDefault(false)
        }
        if (!startedForeground) {
            releaseKeepAliveLocks()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseKeepAliveLocks()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        releaseKeepAliveLocks()
        CarPlayBackgroundSession.stop()
        stopSelf()
    }

    @Synchronized
    private fun acquireKeepAliveLocks() {
        if (cpuWakeLock == null) {
            cpuWakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvplay:session-wakelock")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
        }
        val wifiManager = runCatching { applicationContext.getSystemService(WifiManager::class.java) }.getOrNull()
        if (wifiPerformanceLock == null && wifiManager != null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiPerformanceLock = runCatching {
                wifiManager.createWifiLock(mode, "tvplay:session-wifilock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
        if (multicastLock == null && wifiManager != null) {
            multicastLock = runCatching {
                wifiManager.createMulticastLock("tvplay:session-multicast").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
    }

    @Synchronized
    private fun releaseKeepAliveLocks() {
        cpuWakeLock?.let { lock ->
            cpuWakeLock = null
            runCatching { if (lock.isHeld) lock.release() }
        }
        wifiPerformanceLock?.let { lock ->
            wifiPerformanceLock = null
            runCatching { if (lock.isHeld) lock.release() }
        }
        multicastLock?.let { lock ->
            multicastLock = null
            runCatching { if (lock.isHeld) lock.release() }
        }
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
            runCatching {
                val intent = Intent(context, TvSessionForegroundService::class.java)
                    .putExtra("wireless", wireless)
                    .putExtra("status", status)
                try {
                    context.startService(intent)
                } catch (_: IllegalStateException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    }
                }
            }
        }

        fun startOrUpdate(context: Context, status: String) {
            startOrUpdate(context, wireless = true, status = status)
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, TvSessionForegroundService::class.java))
            }
        }
    }
}
