package app.tvplay.runtime

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.tvplay.engine.session.isNonPhoneBluetoothAccessory
import java.lang.ref.WeakReference

/**
 * Prevents Android TV / projector ROMs from hijacking the screen into their built-in
 * "Bluetooth Music / 蓝牙音乐 (A2DP Sink)" player when a phone connects over Bluetooth.
 *
 * Neither Apple CarPlay (which uses Wi-Fi AirPlay for all audio after iAP2 RFCOMM bootstrap)
 * nor ICCOA CarLink (which uses BLE 0xFCFB + 5GHz Wi-Fi P2P `MEDIA 57229` / WFD) uses the TV's
 * Classic Bluetooth `A2DP_SINK` profile (`11`). When a phone connects to the TV's `A2DP_SINK`,
 * many TV ROMs automatically launch a full-screen Bluetooth Speaker activity.
 *
 * This guard:
 * 1. Actively forbids and disconnects `A2DP_SINK` (`11`), `AVRCP_CONTROLLER` (`12`), and
 *    `HEADSET_CLIENT` (`16`) on connected phones via [BluetoothProfile] proxies while preserving
 *    BLE GATT and iAP2 RFCOMM sockets.
 * 2. Claims exclusive [AudioManager.AUDIOFOCUS_GAIN] media focus so the ROM's Bluetooth Music
 *    service does not start playback.
 * 3. Detects when the ROM's Bluetooth Music activity hijacks foreground focus from TVPlay right
 *    after a Bluetooth ACL / A2DP event and immediately brings TVPlay back to the front.
 */
object TvBluetoothSinkGuard {
    const val ACTION_A2DP_SINK_STATE_CHANGED =
        "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    const val ACTION_A2DP_SINK_PLAYING_CHANGED =
        "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
    const val ACTION_AVRCP_STATE_CHANGED =
        "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED"
    const val ACTION_HFP_CLIENT_STATE_CHANGED =
        "android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED"

    private const val PROFILE_A2DP_SINK = 11
    private const val PROFILE_AVRCP_CONTROLLER = 12
    private const val PROFILE_HEADSET_CLIENT = 16
    private const val HIJACK_WINDOW_NANOS = 12_000_000_000L // 12 seconds

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastBluetoothEventNanos: Long = 0L

    @Volatile
    private var lastExternalIntentLaunchNanos: Long = 0L

    @Volatile
    private var foregroundActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var lastActiveActivityClass: Class<out Activity>? = null

    fun buildIntentFilter(): IntentFilter = IntentFilter().apply {
        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        addAction(ACTION_A2DP_SINK_STATE_CHANGED)
        addAction(ACTION_A2DP_SINK_PLAYING_CHANGED)
        addAction(ACTION_AVRCP_STATE_CHANGED)
        addAction(ACTION_HFP_CLIENT_STATE_CHANGED)
    }

    fun isSinkProfileAction(action: String?): Boolean =
        action == ACTION_A2DP_SINK_STATE_CHANGED ||
            action == ACTION_A2DP_SINK_PLAYING_CHANGED ||
            action == ACTION_AVRCP_STATE_CHANGED ||
            action == ACTION_HFP_CLIENT_STATE_CHANGED

    fun markLaunchingExternalSettings() {
        lastExternalIntentLaunchNanos = System.nanoTime()
    }

    private fun isRecentlyLaunchingExternalSettings(): Boolean {
        val ts = lastExternalIntentLaunchNanos
        return ts != 0L && (System.nanoTime() - ts) < 4_000_000_000L
    }

    fun onActivityResumed(activity: Activity) {
        foregroundActivityRef = WeakReference(activity)
        lastActiveActivityClass = activity.javaClass
        claimMediaAudioFocus(activity.applicationContext)
        suppressA2dpSink(activity.applicationContext)
    }

    fun onActivityPaused(activity: Activity) {
        if (foregroundActivityRef?.get() === activity) {
            foregroundActivityRef = null
        }
        if (activity.isFinishing || activity.isChangingConfigurations) return
        if (isRecentlyLaunchingExternalSettings()) return

        val now = System.nanoTime()
        val recentBtEvent = lastBluetoothEventNanos != 0L && (now - lastBluetoothEventNanos) < HIJACK_WINDOW_NANOS
        if (recentBtEvent) {
            val appCtx = activity.applicationContext
            val targetClass = activity.javaClass
            TvDiagnosticsLogger.record("TvBluetoothSinkGuard: detected Bluetooth Music hijack on ${targetClass.simpleName}; reclaiming foreground")
            suppressA2dpSink(appCtx)
            claimMediaAudioFocus(appCtx)
            scheduleForegroundReclaim(appCtx, targetClass)
        }
    }

    @SuppressLint("MissingPermission")
    fun onBluetoothEvent(
        context: Context,
        device: BluetoothDevice? = null,
        action: String? = null,
        intent: Intent? = null,
    ) {
        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val transport = intent?.getIntExtra(
                "android.bluetooth.device.extra.TRANSPORT",
                BluetoothDevice.TRANSPORT_AUTO,
            ) ?: BluetoothDevice.TRANSPORT_AUTO
            if (transport == BluetoothDevice.TRANSPORT_LE) {
                return
            }
            if (device != null) {
                val isUnbondedOrBle = runCatching {
                    device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                        device.bondState != BluetoothDevice.BOND_BONDED
                }.getOrDefault(false)
                if (isUnbondedOrBle) {
                    // Ignore transient BLE GATT client connections (e.g. ICCOA CarLink handshake)
                    // so we never invoke Bluetooth profile policy/disconnect reflection during GATT I/O.
                    return
                }
            }
        }
        val appCtx = context.applicationContext
        lastBluetoothEventNanos = System.nanoTime()
        suppressA2dpSink(appCtx, device)
        claimMediaAudioFocus(appCtx)
        mainHandler.postDelayed({ suppressA2dpSink(appCtx, device) }, 220L)
        mainHandler.postDelayed({ suppressA2dpSink(appCtx, device) }, 650L)
        mainHandler.postDelayed({ suppressA2dpSink(appCtx, device) }, 1_400L)

        val targetClass = foregroundActivityRef?.get()?.javaClass
            ?: lastActiveActivityClass?.takeIf { CarPlayBackgroundSession.hasSession() }
        if (targetClass != null && !isRecentlyLaunchingExternalSettings()) {
            if (isSinkProfileAction(action) || action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                scheduleForegroundReclaim(appCtx, targetClass)
            }
        }
    }

    private fun scheduleForegroundReclaim(appContext: Context, activityClass: Class<out Activity>) {
        val delays = longArrayOf(120L, 420L, 950L)
        for (delayMs in delays) {
            mainHandler.postDelayed(
                {
                    if (isRecentlyLaunchingExternalSettings()) return@postDelayed
                    if (foregroundActivityRef?.get() != null) return@postDelayed
                    runCatching {
                        val intent = Intent(appContext, activityClass).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
                            )
                        }
                        appContext.startActivity(intent)
                    }
                },
                delayMs,
            )
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun suppressA2dpSink(context: Context, specificDevice: BluetoothDevice? = null) {
        val appCtx = context.applicationContext
        val adapter = runCatching {
            appCtx.getSystemService(BluetoothManager::class.java)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }.getOrNull() ?: return
        if (!adapter.isEnabled) return

        val sinkProfiles = intArrayOf(PROFILE_A2DP_SINK, PROFILE_AVRCP_CONTROLLER, PROFILE_HEADSET_CLIENT)
        for (profileId in sinkProfiles) {
            runCatching {
                adapter.getProfileProxy(
                    appCtx,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            try {
                                val devices = LinkedHashSet<BluetoothDevice>()
                                specificDevice?.let { devices.add(it) }
                                runCatching { devices.addAll(proxy.connectedDevices.orEmpty()) }
                                runCatching { devices.addAll(adapter.bondedDevices.orEmpty()) }
                                for (device in devices) {
                                    val isLeOrUnbonded = runCatching {
                                        device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                                            device.bondState != BluetoothDevice.BOND_BONDED
                                    }.getOrDefault(false)
                                    if (isLeOrUnbonded) continue
                                    val name = runCatching { device.name }.getOrNull()
                                    val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
                                    if (isNonPhoneBluetoothAccessory(name, major)) continue
                                    disableAndDisconnectProfile(proxy, device)
                                }
                            } finally {
                                runCatching { adapter.closeProfileProxy(profile, proxy) }
                            }
                        }

                        override fun onServiceDisconnected(profile: Int) = Unit
                    },
                    profileId,
                )
            }
        }
    }

    private fun disableAndDisconnectProfile(proxy: BluetoothProfile, device: BluetoothDevice) {
        val proxyClass = proxy.javaClass
        // 1. Forbid auto-connect on Android 10+ (CONNECTION_POLICY_FORBIDDEN = 0)
        runCatching {
            val method = proxyClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(proxy, device, 0)
        }
        // 2. Forbid auto-connect on Android 7-9 (PRIORITY_OFF = 0)
        runCatching {
            val method = proxyClass.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(proxy, device, 0)
        }
        // 3. Disconnect active A2DP_SINK / AVRCP_CONTROLLER / HFP_CLIENT link
        runCatching {
            val state = proxy.getConnectionState(device)
            if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
                val disconnectMethod = proxyClass.getMethod("disconnect", BluetoothDevice::class.java)
                disconnectMethod.isAccessible = true
                disconnectMethod.invoke(proxy, device)
                TvDiagnosticsLogger.record("TvBluetoothSinkGuard: disconnected ${proxyClass.simpleName} from ${device.address}")
            }
        }
    }

    @Suppress("DEPRECATION")
    fun claimMediaAudioFocus(context: Context) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioManager.requestAudioFocus(req)
            } else {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
        }
    }
}
