package app.tvplay.engine.session

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.auth.DuplexByteStream
import app.tvplay.engine.auth.LockdownCarKitClient
import app.tvplay.engine.auth.LockdownPairRecord
import app.tvplay.engine.auth.LockdownPairingClient
import app.tvplay.engine.auth.MfiAuthProvider
import app.tvplay.engine.auth.OfflineMfiAuthProvider
import app.tvplay.engine.auth.PairingRegistry
import app.tvplay.engine.network.BluetoothLinkCheck
import app.tvplay.engine.network.BluetoothRadioCheck
import app.tvplay.engine.network.BluetoothRfcommDuplexStream
import app.tvplay.engine.network.CarPlayBonjour
import app.tvplay.engine.network.UsbTunnelVpnService
import app.tvplay.engine.network.HotspotAddressPolicy
import app.tvplay.engine.network.HotspotAddressRecovery
import app.tvplay.engine.network.Iap2UsbMuxHost
import app.tvplay.engine.network.Iap2UsbSession
import app.tvplay.engine.network.IphoneCarPlayConfiguration
import app.tvplay.engine.network.IphoneUsbException
import app.tvplay.engine.network.IphoneUsbHost
import app.tvplay.engine.network.IphoneUsbMatcher
import app.tvplay.engine.network.LanNetworkLink
import app.tvplay.engine.network.NcmFunctionDiscovery
import app.tvplay.engine.network.NcmUsbBridge
import app.tvplay.engine.network.P2pResetRequiredException
import app.tvplay.engine.network.WifiDirectGroupLink
import app.tvplay.engine.network.WirelessHotspotBackend
import app.tvplay.engine.network.WirelessHotspotInfo
import app.tvplay.engine.network.WirelessHotspotManager
import app.tvplay.engine.network.WirelessInterfaceDiagnostics
import app.tvplay.engine.network.WirelessReceiveDiagnostics
import app.tvplay.engine.network.WirelessStartupDiagnostics
import app.tvplay.engine.network.WirelessStartupException
import app.tvplay.engine.network.WirelessStartupFailure
import app.tvplay.engine.network.WirelessStartupPolicy
import app.tvplay.engine.network.diagnosticSummary
import app.tvplay.engine.protocol.iap2.Iap2ArtworkTransfer
import app.tvplay.engine.protocol.iap2.Iap2ControlMessages
import app.tvplay.engine.protocol.iap2.Iap2Frame
import app.tvplay.engine.protocol.iap2.Iap2MfiAuthenticationClient
import app.tvplay.engine.protocol.iap2.Iap2NowPlayingTracker
import app.tvplay.engine.protocol.iap2.Iap2Session
import app.tvplay.engine.protocol.iap2.Iap2WiredCarPlayEndpoint
import app.tvplay.engine.protocol.iap2.Iap2WiredControlClient
import app.tvplay.engine.protocol.iap2.Iap2WiredControlTerminal
import app.tvplay.engine.protocol.iap2.Iap2WirelessCarPlayEndpoint
import app.tvplay.engine.protocol.iap2.Iap2WirelessControlClient
import app.tvplay.engine.protocol.iap2.Iap2WirelessControlTerminal
import app.tvplay.engine.protocol.iap2.Iap2WirelessIdentification
import app.tvplay.engine.protocol.iap2.Iap2WirelessLinkRole
import app.tvplay.engine.protocol.iap2.forWirelessLink
import app.tvplay.engine.protocol.carlink.CarLinkBleAdvertiser
import app.tvplay.engine.protocol.carlink.CarLinkBleSpec
import app.tvplay.engine.protocol.carlink.CarLinkControlSession
import app.tvplay.engine.protocol.carlink.CarLinkProtoCodec
import app.tvplay.engine.protocol.carlink.CarLinkUibcDispatcher
import app.tvplay.engine.protocol.rtsp.AirPlayContact
import app.tvplay.engine.protocol.rtsp.CarPlayMediaEngine
import app.tvplay.engine.protocol.rtsp.AirPlayDeviceInfo
import app.tvplay.engine.protocol.rtsp.AirPlayKnobState
import app.tvplay.engine.protocol.rtsp.AirPlayListenerIdentity
import app.tvplay.engine.protocol.rtsp.AirPlayMediaHandler
import app.tvplay.engine.protocol.rtsp.AirPlaySession
import app.tvplay.engine.protocol.rtsp.AirPlaySessionListener
import app.tvplay.engine.protocol.rtsp.AirPlayTcpAccepted
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

typealias CarPlayController = TvSessionEngine

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
        val passphrase: String = "",
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data class IphoneNotListed(val nonHubDeviceListed: Boolean) : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(
        val message: String,
        val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null,
        val knownCause: ConnectionFailureCause? = null,
    ) : CarPlayStatus()

    fun toSessionState(): TvSessionState = when (this) {
        DiscoveringMfi -> TvSessionState.Starting("Preparing offline MFi authentication")
        WaitingForMfi -> TvSessionState.Searching("Waiting for MFi authentication")
        RequestingMfiPermission -> TvSessionState.Starting("Requesting MFi permission")
        MfiReady -> TvSessionState.Authenticated("Offline MFi authentication ready")
        StartingHotspot -> TvSessionState.Starting("Starting wireless CarPlay link")
        is HotspotReady -> TvSessionState.Starting("Wireless link ready ($backend: $ssid)")
        WaitingForPairedIphone -> TvSessionState.Searching("Waiting for paired iPhone over Bluetooth")
        ConnectingBluetooth -> TvSessionState.Starting("Connecting Bluetooth iAP2 channel")
        RunningWireless -> TvSessionState.Authenticated("Negotiating wireless CarPlay session")
        WirelessActive -> TvSessionState.Streaming("Wireless CarPlay active")
        WirelessActiveFallback -> TvSessionState.Streaming("Wireless CarPlay active (Bluetooth control fallback)")
        DiscoveringIphone -> TvSessionState.Searching("Searching for iPhone over USB")
        WaitingForIphone -> TvSessionState.Searching("Waiting for iPhone USB connection")
        is IphoneNotListed -> TvSessionState.Searching("Waiting for iPhone USB device")
        RequestingIphonePermission -> TvSessionState.Starting("Requesting USB permission for iPhone")
        WaitingForReenumeration -> TvSessionState.Starting("Waiting for iPhone CarPlay USB re-enumeration")
        SelectingConfiguration -> TvSessionState.Starting("Selecting iPhone CarPlay USB configuration")
        OpeningDataPaths -> TvSessionState.Starting("Opening iAP2 and NCM USB data paths")
        Pairing -> TvSessionState.Starting("Establishing USB Lockdown pairing")
        ConnectingControl -> TvSessionState.Starting("Opening CarKit control service")
        AttachingNetwork -> TvSessionState.Starting("Attaching CarPlay network transport")
        RunningControl -> TvSessionState.Authenticated("Running wired iAP2 control session")
        ControlEnded -> TvSessionState.Idle
        is Failed -> TvSessionState.Failed(
            step = startupFailure?.name ?: "SESSION",
            detail = message,
            cause = knownCause,
        )
    }
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
): Boolean = handoffRequested || tunnelActive || sessionActive

/**
 * Arms a single post-`0x4301` timer for the current [AirPlayListenerIdentity].
 *
 * Only an external TCP accept taken at or after the `0x4301` send timestamp disarms the timer.
 * Self-probes and stale accepts from earlier listener bindings are ignored.
 */
class FirstTcpWatchdog(
    val listener: AirPlayListenerIdentity,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val nowNanos: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = WirelessStartupPolicy.FIRST_TCP_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var startSentAtNanos: Long? = null
    private var cancelScheduled: (() -> Unit)? = null
    private var disarmed = false
    private var terminated = false

    fun startSessionSent(sentAtNanos: Long = nowNanos()) {
        var previousCancel: (() -> Unit)? = null
        val scheduled = synchronized(lock) {
            if (terminated || disarmed) return
            previousCancel = cancelScheduled
            startSentAtNanos = sentAtNanos
            schedule(timeoutMillis) { fireTimeout() }.also { cancelScheduled = it }
        }
        previousCancel?.invoke()
        if (synchronized(lock) { terminated || disarmed }) {
            scheduled()
            return
        }
        log("first-tcp watchdog armed timeoutMs=$timeoutMillis")
    }

    fun accepted(event: AirPlayTcpAccepted): Boolean {
        val cancel = synchronized(lock) {
            if (terminated || disarmed || event.listener !== listener || event.internal) {
                return false
            }
            disarmed = true
            cancelScheduled.also { cancelScheduled = null }
        }
        cancel?.invoke()
        log("first-tcp watchdog disarmed by external AirPlay TCP accept")
        return true
    }

    fun disarm() {
        val cancel = synchronized(lock) {
            if (terminated || disarmed) return
            disarmed = true
            cancelScheduled.also { cancelScheduled = null }
        }
        cancel?.invoke()
    }

    fun sessionEstablished(): Boolean = synchronized(lock) { !terminated }

    fun isDisarmed(): Boolean = synchronized(lock) { disarmed && !terminated }

    fun isArmed(): Boolean = synchronized(lock) { startSentAtNanos != null && !terminated }

    fun terminate() {
        val cancel = synchronized(lock) {
            if (terminated) return
            terminated = true
            cancelScheduled.also { cancelScheduled = null }
        }
        cancel?.invoke()
    }

    private fun fireTimeout() {
        val shouldFire = synchronized(lock) {
            if (terminated || disarmed || startSentAtNanos == null) false else {
                terminated = true
                cancelScheduled = null
                true
            }
        }
        if (!shouldFire) return
        log("first-tcp watchdog fired timeoutMs=$timeoutMillis")
        onTimeout()
    }
}

/**
 * Ends a wired attempt when the iPhone answers `carplay-start-session` by leaving the control link
 * open without ever starting AirPlay (#889, #890).
 *
 * When the USB network link is still coming up at 15 s, a second `carplay-start-session` can still
 * go out once it opens; the limit is held until [HELD_TIMEOUT_MILLIS] for that, and reset from the
 * second send if it goes out.
 */
class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val secondRequestCanStillGoOut: () -> Boolean = { false },
    private val onHold: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private val lock = Any()
    private var cancelScheduled: (() -> Unit)? = null
    private var armed = false
    private var heldForSecondRequest = false
    private var done = false

    fun startSessionSent() {
        val cancelOld: (() -> Unit)?
        val scheduled: (() -> Unit)?
        synchronized(lock) {
            if (done || armed) return
            armed = true
            cancelOld = cancelScheduled
            scheduled = schedule(timeoutMillis) { fire() }
            cancelScheduled = scheduled
        }
        cancelOld?.invoke()
        if (synchronized(lock) { done }) scheduled?.invoke()
    }

    /** Re-arms for a full [timeoutMillis] when the second `carplay-start-session` goes out. */
    fun startSessionSentAgain() {
        val cancelOld: (() -> Unit)?
        val scheduled: (() -> Unit)?
        synchronized(lock) {
            if (done) return
            armed = true
            heldForSecondRequest = true
            cancelOld = cancelScheduled
            scheduled = schedule(timeoutMillis) { fire() }
            cancelScheduled = scheduled
        }
        cancelOld?.invoke()
        if (synchronized(lock) { done }) scheduled?.invoke()
    }

    fun sessionActive() = terminate()

    fun terminate() {
        val cancel = synchronized(lock) {
            if (done) return
            done = true
            cancelScheduled.also { cancelScheduled = null }
        }
        cancel?.invoke()
    }

    private fun fire() {
        var heldSchedule: (() -> Unit)? = null
        val hold = synchronized(lock) {
            if (done || !armed) return
            if (!heldForSecondRequest && secondRequestCanStillGoOut()) {
                heldForSecondRequest = true
                heldSchedule = schedule(timeoutMillis) { fire() }
                cancelScheduled = heldSchedule
                true
            } else {
                done = true
                cancelScheduled = null
                false
            }
        }
        if (hold) {
            if (synchronized(lock) { done }) {
                heldSchedule?.invoke()
                return
            }
            onHold()
            return
        }
        onTimeout()
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val HELD_TIMEOUT_MILLIS = TIMEOUT_MILLIS * 2
    }
}

/**
 * When `carplay-start-session` goes out before the iPhone has opened the USB NCM link, the iPhone can miss it and
 * sit idle on an open link (#890). Ask it once more if no AirPlay connection has arrived [QUIET_MILLIS] after the
 * link opened.
 */
internal object WiredStartAgain {
    const val QUIET_MILLIS = 2_000L
    private const val QUIET_NANOS = QUIET_MILLIS * 1_000_000L

    fun decide(
        nowNanos: Long,
        startSentAtNanos: Long,
        linkOpenedAtNanos: Long?,
        sessionActive: Boolean,
        connectionAttemptSinceLinkOpened: Boolean,
    ): Iap2WiredControlClient.StartAgain {
        if (sessionActive || connectionAttemptSinceLinkOpened) return Iap2WiredControlClient.StartAgain.DONE
        val opened = linkOpenedAtNanos ?: return Iap2WiredControlClient.StartAgain.WAIT
        if (opened - startSentAtNanos <= 0) return Iap2WiredControlClient.StartAgain.DONE
        return if (nowNanos - opened < QUIET_NANOS) {
            Iap2WiredControlClient.StartAgain.WAIT
        } else {
            Iap2WiredControlClient.StartAgain.SEND
        }
    }
}

/**
 * What wired bring-up should do with the USB configurations an iPhone currently exposes.
 *
 * iOS 17+ already exposes USBMUX and CDC-NCM before the CarPlay vendor request, without the Apple
 * Ethernet interface (`ff/fd/1`) that CarPlay mode adds. Proceeding on that pre-transition shape
 * stops after `carplay-start-session` (#634), so the vendor request is sent first and the
 * configuration without Apple Ethernet is used only after the transition has already been tried.
 */
internal enum class WiredConfigurationAction(val diagnostic: String) {
    USE("use"),
    USE_WITHOUT_APPLE_ETHERNET("use_without_apple_ethernet"),
    REQUEST_TRANSITION("request_transition"),
    REJECT("reject");

    companion object {
        fun choose(
            found: Boolean,
            carPlayMode: Boolean,
            transitionsTried: Int,
            maximumTransitions: Int,
            sessionAlreadyStarted: Boolean = false,
        ): WiredConfigurationAction = when {
            // A fresh session on an iPhone still in the CarPlay mode of an ended session needs a new
            // mode request (#889); once requested in this bring-up, use the configuration it offers.
            carPlayMode && (!sessionAlreadyStarted || transitionsTried > 0) -> USE
            carPlayMode && transitionsTried < maximumTransitions -> REQUEST_TRANSITION
            carPlayMode -> USE
            transitionsTried < maximumTransitions -> REQUEST_TRANSITION
            found -> USE_WITHOUT_APPLE_ETHERNET
            else -> REJECT
        }

        /** Whether a rejected mode switch can fall back to the configuration the iPhone already offers. */
        fun fallbackAfterRejection(
            found: Boolean,
            carPlayMode: Boolean,
            sessionAlreadyStarted: Boolean,
        ): Boolean = found && !(carPlayMode && sessionAlreadyStarted)
    }
}

/**
 * Remembers the USB device node on which a wired session already reached `carplay-start-session` in this process.
 * A cable unplug gives the next plug a new device node; a session restart on the same plug keeps the old one (#889).
 */
internal object WiredSessionMemory {
    private val startedNodes = HashSet<String>()

    @Synchronized fun started(deviceName: String) {
        if (startedNodes.size >= 16) startedNodes.clear()
        startedNodes.add(deviceName)
    }

    @Synchronized fun startedOn(deviceName: String): Boolean = deviceName in startedNodes

    @Synchronized internal fun clear() = startedNodes.clear()
}

/**
 * Tracks whether a wireless bring-up generation has proven a live CarPlay session.
 *
 * AP creation or iAP2 progress alone must not mark a Wi-Fi Direct configuration reusable or keep
 * the RFCOMM control loop alive past its bring-up window.
 */
internal class WirelessConnectionProof<S : Any> {
    private var generation = 0
    private var activeSession: S? = null
    private var tunnelAuthenticated = false
    private var videoRendered = false
    private var confirmed = false
    private var onConfirmed: (() -> Unit)? = null

    @Synchronized
    fun begin(generation: Int, onConfirmed: () -> Unit = {}) {
        this.generation = generation
        activeSession = null
        tunnelAuthenticated = false
        videoRendered = false
        confirmed = false
        this.onConfirmed = onConfirmed
    }

    @Synchronized
    fun activate(generation: Int, session: S) {
        if (generation != this.generation) return
        if (activeSession !== session) videoRendered = false
        activeSession = session
    }

    @Synchronized
    fun authenticated(generation: Int) {
        if (generation != this.generation) return
        tunnelAuthenticated = true
        confirmIfReadyLocked()
    }

    @Synchronized
    fun rendered(generation: Int, session: S) {
        if (generation != this.generation || activeSession !== session) return
        videoRendered = true
        confirmIfReadyLocked()
    }

    @Synchronized
    fun hasActiveSession(generation: Int): Boolean =
        generation == this.generation && activeSession != null

    @Synchronized
    fun hasRenderedFrame(generation: Int): Boolean =
        generation == this.generation && activeSession != null && videoRendered

    @Synchronized
    fun end(generation: Int, session: S) {
        if (generation == this.generation && activeSession === session) {
            activeSession = null
            videoRendered = false
        }
    }

    @Synchronized
    fun clear() {
        generation = 0
        activeSession = null
        tunnelAuthenticated = false
        videoRendered = false
        confirmed = false
        onConfirmed = null
    }

    private fun confirmIfReadyLocked() {
        if (confirmed || activeSession == null || !tunnelAuthenticated || !videoRendered) return
        confirmed = true
        val callback = onConfirmed
        onConfirmed = null
        callback?.invoke()
    }
}

/**
 * Throttles per-stream read/write diagnostics so a busy link cannot flood the session log while
 * the first operations, failures, and final totals remain visible.
 */
internal class ConnectionIoDiagnostics(
    private val report: (String) -> Unit,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    enum class Operation { READ, WRITE }
    enum class Result { COMPLETED, TIMED_OUT, ENDED, FAILED }

    private val lock = Any()
    private var readCompleted = 0L
    private var readTimedOut = 0L
    private var readEnded = 0L
    private var readFailed = 0L
    private var writeCompleted = 0L
    private var writeFailed = 0L
    private var firstReadReported = false
    private var firstWriteReported = false
    private var lastSummaryNanos = clockNanos()
    private var closed = false

    fun record(operation: Operation, result: Result, elapsedMillis: Long) {
        var line: String? = null
        var summary: String? = null
        val now = clockNanos()
        synchronized(lock) {
            if (closed) return
            when (operation) {
                Operation.READ -> when (result) {
                    Result.COMPLETED -> {
                        readCompleted++
                        if (!firstReadReported) {
                            firstReadReported = true
                            line = "carkit io op=READ result=FIRST_COMPLETED elapsedMs=$elapsedMillis"
                        }
                    }
                    Result.TIMED_OUT -> readTimedOut++
                    Result.ENDED -> {
                        readEnded++
                        line = "carkit io op=READ result=ENDED elapsedMs=$elapsedMillis"
                    }
                    Result.FAILED -> {
                        readFailed++
                        line = "carkit io op=READ result=FAILED elapsedMs=$elapsedMillis"
                    }
                }
                Operation.WRITE -> when (result) {
                    Result.COMPLETED -> {
                        writeCompleted++
                        if (!firstWriteReported) {
                            firstWriteReported = true
                            line = "carkit io op=WRITE result=FIRST_COMPLETED elapsedMs=$elapsedMillis"
                        }
                    }
                    else -> {
                        writeFailed++
                        line = "carkit io op=WRITE result=${result.name} elapsedMs=$elapsedMillis"
                    }
                }
            }
            if (now - lastSummaryNanos >= SUMMARY_INTERVAL_NANOS) {
                lastSummaryNanos = now
                summary = summaryLocked("periodic")
            }
        }
        line?.let(::safeReport)
        summary?.let(::safeReport)
    }

    fun finish() {
        val summary = synchronized(lock) {
            if (closed) return
            closed = true
            summaryLocked("final")
        }
        safeReport(summary)
    }

    private fun summaryLocked(kind: String): String =
        "carkit io summary=$kind readOk=$readCompleted readTimeout=$readTimedOut " +
            "readEnded=$readEnded readFailed=$readFailed writeOk=$writeCompleted writeFailed=$writeFailed"

    private fun safeReport(message: String) {
        try {
            report(message)
        } catch (_: Exception) {
            // Optional diagnostics must never break the underlying stream.
        }
    }

    private companion object {
        const val SUMMARY_INTERVAL_NANOS = 10_000_000_000L
    }
}

/**
 * Wires the complete wired or wireless CarPlay path for TVPlay:
 * offline MFi authentication, iPhone USB or Bluetooth/Wi-Fi bring-up, iAP2 control,
 * transport setup, and the AirPlay RTSP media/input sessions.
 *
 * All blocking USB/network work runs on worker executors. Status callbacks are delivered on the
 * main thread.
 */
class TvSessionEngine(
    context: Context,
    private val config: TvSessionConfig,
    private val airPlayConfig: TvStreamServerConfig,
    private val identity: AccessoryIdentity,
    private val pairings: PairingRegistry,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val loadPairRecord: () -> LockdownPairRecord? = { null },
    private val savePairRecord: (LockdownPairRecord) -> Unit = {},
    private val clearPairRecord: () -> Unit = {},
) : Closeable {

    private enum class Phase { IDLE, MFI, WIRELESS, IPHONE, REENUMERATION, DATAPATHS, CONTROL }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)

    private fun currentBluetoothAdapter(): BluetoothAdapter? =
        runCatching { appContext.getSystemService(BluetoothManager::class.java)?.adapter }
            .getOrNull() ?: runCatching {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }.getOrNull()

    private fun awaitBluetoothEnabled(adapter: BluetoothAdapter, timeoutMillis: Long = 3_000L): Boolean {
        if (adapter.isEnabled) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = runCatching { adapter.state }.getOrNull()
            if (state == BluetoothAdapter.STATE_ON || adapter.isEnabled) return true
            if (state != BluetoothAdapter.STATE_TURNING_ON) break
            SystemClock.sleep(100)
        }
        return adapter.isEnabled
    }

    private val iphoneHost by lazy {
        IphoneUsbHost(
            appContext,
            requireUsbManager(),
            if (config.iphoneDevices.isNotEmpty()) {
                IphoneUsbMatcher(config.iphoneDevices)
            } else {
                IphoneUsbMatcher.appleVendor()
            },
            onDiagnostic = ::connectionDiagnostic,
        )
    }
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
    private val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val permissionGrant = AtomicBoolean(false)
    private val availabilityPollGeneration = AtomicInteger(0)
    private var permissionPollGeneration = 0
    private var reenumerationAttempts = 0
    @Volatile private var wiredDeviceName: String? = null
    private var lastReportedStatus: CarPlayStatus? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiProvider: MfiAuthProvider? = null
    @Volatile private var mux: Iap2UsbMuxHost? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    private val playbackStatus = Iap2NowPlayingTracker()

    private val albumReplayLock = Any()
    private var initialArtworkReplay: Iap2ArtworkTransfer? = null

    fun nowPlayingSnapshot(): TvNowPlayingMetadata =
        synchronized(playbackStatus) { playbackStatus.nowPlaying }

    /** One bounded payload, never a nearest-cover guess when a reference is absent. */
    fun matchingArtworkSnapshot(id: Int?): Iap2ArtworkTransfer? =
        synchronized(albumReplayLock) { initialArtworkReplay?.takeIf { id != null && it.id == id } }

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((TvNowPlayingMetadata) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var instantBluetoothLink: InstantBluetoothLink? = null
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    @Volatile private var wiredStartWatchdog: WiredStartWatchdog? = null
    @Volatile private var wiredSessionActive = false
    @Volatile private var wiredStartSentAtNanos: Long? = null
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    @Volatile private var wirelessAlternateAddress: InetAddress? = null
    @Volatile private var wirelessAddressRecovery: HotspotAddressRecovery? = null
    private val startupTimer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "tvplay-first-tcp-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothServerSocket: BluetoothServerSocket? = null
    @Volatile private var acceptedBluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BluetoothRfcommDuplexStream? = null
    @Volatile private var wifiPerformanceLock: WifiManager.WifiLock? = null
    private val bluetoothPeerWaitLock = Object()
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessRuntimeIdentification: TvAccessoryIdentitySpec? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var vpnService: UsbTunnelVpnService? = null
    @Volatile private var vpnBound = false
    @Volatile private var activeHotspotInfo: WirelessHotspotInfo? = null
    @Volatile private var carLinkSession: CarLinkControlSession? = null
    @Volatile private var carLinkBleAdvertiser: CarLinkBleAdvertiser? = null
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    /** Set when the handoff fell back and kept Bluetooth as the only iAP2 channel. */
    private val wirelessHandoffFellBack = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var permissionCloseable: Closeable? = null
    private var attachCloseable: Closeable? = null
    private var lastIphoneListing: String? = null
    private var iphoneWaitStartedAt: Long? = null
    private var vpnLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            vpnService = (binder as UsbTunnelVpnService.LocalBinder).service
            vpnLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            vpnService = null
            fail(IphoneUsbException.DeviceUnavailable("CarPlay VPN service disconnected"))
        }
    }

    /** The live AirPlay session, for a new host that adopts the running stream without a session-active callback. */
    fun activeAirPlaySession(): AirPlaySession? = activeSession?.takeIf { !closed && !it.isClosed }

    private val sessionListener = object : AirPlaySessionListener {
        override val wantsTrace: Boolean
            get() = uiListener?.wantsTrace == true || Log.isLoggable(TRACE_LOG_TAG, Log.VERBOSE)

        override fun onVideoFrameRendered(session: AirPlaySession) {
            if (!closed && !session.isClosed && activeSession === session) {
                uiListener?.onVideoFrameRendered(session)
            }
        }

        override fun onSessionActive(session: AirPlaySession) {
            wiredSessionActive = true
            wiredStartWatchdog?.sessionActive()
            activeSession = session
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            val currentActive = activeSession
            if (currentActive !== session) {
                debugLog("Non-active AirPlay connection ended peer=${session.host}; keeping live session")
                return
            }
            activeSession = null
            synchronized(albumReplayLock) { initialArtworkReplay = null }
            synchronized(playbackStatus) {
                val wasPlaying = playbackStatus.playing
                playbackStatus.clearAll()?.let { it to wasPlaying }
            }?.let { (cleared, wasPlaying) ->
                nowPlayingListener?.invoke(cleared)
                if (wasPlaying) playbackListener?.invoke(false)
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the host UI; delegating to stage overlay")
            uiListener?.onHostUiRequested(session)
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")}",
            )
            if (
                config.transport == TvTransportMode.WIRELESS &&
                !closed &&
                (activeSession == null || activeSession === session) &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                if (activeSession == null && !session.isClosed) {
                    activeSession = session
                }
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Nonblocking identity for remote controls: only an unclosed phone with an accepted primary stream. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && vpnService?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport} protocol=${config.projectionProtocol.key}")
        if (config.projectionProtocol.enablesCarLink) {
            startCarLinkSubsystem()
        }
        if (!config.projectionProtocol.enablesCarPlay) {
            if (config.transport == TvTransportMode.WIRELESS) {
                startStandaloneCarLinkWireless()
            } else {
                onStatus(CarPlayStatus.RunningControl)
            }
            return
        }
        if (!hasRequiredUsbService()) {
            if (config.transport == TvTransportMode.WIRELESS && config.projectionProtocol.enablesCarLink) {
                startStandaloneCarLinkWireless()
            }
            return
        }
        if (config.transport == TvTransportMode.WIRED) {
            permissionCloseable = iphoneHost.registerPermissionReceiver(::onIphonePermission)
            attachCloseable = iphoneHost.registerAttachReceiver(::onIphoneAttached)
        }
        startMfi()
    }

    @Volatile
    private var lastCarLinkBleClientNanos: Long = 0L

    private val carLinkHotspotStarting = AtomicBoolean(false)

    private fun isCarLinkBleRecentlyActive(): Boolean {
        if (carLinkSession?.isActive() == true) return true
        val last = lastCarLinkBleClientNanos
        return last != 0L && (System.nanoTime() - last) < 25_000_000_000L
    }

    private fun ensureCarLinkHotspotReady() {
        if (closed || config.transport != TvTransportMode.WIRELESS) return
        val liveHotspot = activeHotspotInfo?.takeIf { hotspot != null }
        val hotspotValidForCarLink = liveHotspot != null &&
            liveHotspot.backend == WirelessHotspotBackend.WIFI_P2P &&
            liveHotspot.ssid.toByteArray(Charsets.UTF_8).size in 9..32 &&
            liveHotspot.passphrase.isNotBlank()
        if (hotspotValidForCarLink) {
            carLinkBleAdvertiser?.notifyPendingClients()
            return
        }
        val shouldStartStandalone = synchronized(wirelessResourceLock) {
            if (activeSession != null || carLinkHotspotStarting.get()) {
                false
            } else if (hotspot != null && activeHotspotInfo == null) {
                // A Wi-Fi Direct hotspot creation is already in flight and will notify pending BLE clients when ready.
                false
            } else if (!config.projectionProtocol.enablesCarPlay || wirelessFailureReported.get()) {
                true
            } else {
                // Start standalone CarLink P2P hotspot whenever no valid P2P hotspot is active
                // and MFi is not actively mid-handshake.
                phase != Phase.MFI && (activeHotspotInfo == null || activeHotspotInfo?.backend != WirelessHotspotBackend.WIFI_P2P)
            }
        }
        if (shouldStartStandalone) {
            startStandaloneCarLinkWireless()
        }
    }

    private fun startStandaloneCarLinkWireless() {
        if (!carLinkHotspotStarting.compareAndSet(false, true)) return
        val generation = synchronized(wirelessResourceLock) {
            runCatching { hotspot?.close() }
            hotspot = null
            activeHotspotInfo = null
            phase = Phase.WIRELESS
            wirelessFailureReported.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        Thread(
            {
                try {
                    acquireWifiPerformanceLock()
                    var attempt = 0
                    while (!closed && !isStaleWirelessRun(generation) && attempt < 4) {
                        attempt++
                        try {
                            val hotspotInfo = startWirelessHotspot(
                                generation = generation,
                                modeOverride = TvWirelessMode.WIFI_DIRECT,
                            )
                            activeHotspotInfo = hotspotInfo
                            carLinkBleAdvertiser?.notifyPendingClients()
                            val hostAddr = hotspotInfo.hostAddress ?: hotspotInfo.hostAddresses.firstOrNull()
                            if (hostAddr != null) {
                                wirelessPublishedAddress = hostAddr
                            }
                            onStatus(
                                CarPlayStatus.HotspotReady(
                                    ssid = hotspotInfo.ssid,
                                    band = hotspotInfo.bandLabel,
                                    channel = hotspotInfo.channel,
                                    bssid = hotspotInfo.bssid ?: airPlayConfig.deviceId,
                                    address = hostAddr?.hostAddress ?: "",
                                    backend = hotspotInfo.backend.label,
                                    passphrase = hotspotInfo.passphrase,
                                ),
                                generation,
                            )
                            onStatus(CarPlayStatus.RunningWireless, generation)
                            return@Thread
                        } catch (e: P2pResetRequiredException) {
                            debugLog("CarLink wireless hotspot P2P reset on attempt $attempt: ${e.message}")
                            if (attempt < 4 && !closed) {
                                runCatching { Thread.sleep(1_000L) }
                            }
                        } catch (e: Exception) {
                            debugLog("CarLink standalone wireless hotspot attempt $attempt/4 note: ${e.message}")
                            if (attempt < 4 && !closed) {
                                runCatching { Thread.sleep(1_200L) }
                            }
                        }
                    }
                    onStatus(CarPlayStatus.RunningWireless, generation)
                } finally {
                    carLinkHotspotStarting.set(false)
                }
            },
            "tvplay-carlink-hotspot",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun startCarLinkSubsystem() {
        // Derive a dedicated locally-administered unicast MAC for ICCOA CarLink so that:
        // 1) BLE 0x0001 `carId` and `GetUCarConfigResponse.carBrMac` match 100% as required by ucar-sdk;
        // 2) The phone's CarLink stack does NOT auto-dial the TV's Classic Bluetooth A2DP Sink MAC
        //    (which would cause the TV ROM to switch to its built-in "Bluetooth Music" speaker app).
        val carLinkVirtualMac = config.hostMac.copyOf().also { mac ->
            if (mac.size == 6) {
                mac[0] = ((mac[0].toInt() or 0x02) and 0xFE).toByte()
                mac[5] = (mac[5].toInt() xor 0x5A).toByte()
            }
        }
        val carMacHex12 = carLinkVirtualMac.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val sink = (media as? CarPlayMediaEngine)?.sink
        val session = CarLinkControlSession(
            displaySpec = airPlayConfig.main,
            carBrMac = carLinkVirtualMac,
            pinCodeProvider = { config.carLinkPinCode },
            certBytes = config.carLinkCertBytes,
            hevcEnabled = airPlayConfig.hevc,
            mediaSink = sink,
            onSessionConnected = { phoneModel ->
                debugLog("ICCOA CarLink connected: $phoneModel")
                onStatus(CarPlayStatus.WirelessActive)
            },
            onSessionDisconnected = {
                debugLog("ICCOA CarLink disconnected")
                if (!closed && activeSession == null) {
                    onStatus(CarPlayStatus.ControlEnded)
                }
                mainHandler.post {
                    if (!closed) {
                        carLinkBleAdvertiser?.refreshPairingAdvertisement()
                    }
                }
            },
            onMusicMetadata = { music ->
                val metadata = TvNowPlayingMetadata(
                    title = music.title.takeIf { it.isNotBlank() },
                    artist = music.artistName.takeIf { it.isNotBlank() },
                    album = music.albumName.takeIf { it.isNotBlank() },
                )
                nowPlayingListener?.invoke(metadata)
                playbackListener?.invoke(music.isPlaying)
            },
            onLog = ::debugLog,
        )
        carLinkSession = session
        Thread({
            if (!closed) {
                runCatching { session.start() }
                    .onFailure { e -> debugLog("CarLink session start error: ${e.message}") }
            }
        }, "CarLink-Session-Bootstrap").apply {
            isDaemon = true
            start()
        }
        if (config.transport == TvTransportMode.WIRELESS) {
            val carDisplayName = config.label.ifBlank { "TVPlay" }
            val advertiser = CarLinkBleAdvertiser(
                context = appContext,
                carIdHex12 = carMacHex12,
                carName = carDisplayName,
                carShortName = carDisplayName,
                modelIdHex8 = config.carLinkModelId,
                vendorDataHex4 = config.carLinkVendorData,
                pinCodeProvider = { config.carLinkPinCode },
                isSessionActiveProvider = { session.isActive() },
                serverInfoProvider = serverInfo@{
                    val liveHotspot = activeHotspotInfo?.takeIf { hotspot != null } ?: return@serverInfo null
                    if (liveHotspot.backend != WirelessHotspotBackend.WIFI_P2P) {
                        return@serverInfo null
                    }
                    val ssid = liveHotspot.ssid.trim()
                    val psk = liveHotspot.passphrase.trim()
                    // ICCOA CarLink requires a valid P2P/SoftAP SSID (9..32 UTF-8 bytes, e.g. "DIRECT-vs-XXXXXX")
                    // and an 8+ char passphrase. Wait until the hotspot is ready before sending Message 102.
                    if (ssid.toByteArray(Charsets.UTF_8).size !in 9..32 || psk.isBlank()) {
                        return@serverInfo null
                    }
                    val freq = (liveHotspot.frequencyMHz?.takeIf { it > 0 })
                        ?: when (val ch = liveHotspot.channel) {
                            in 1..13 -> 2407 + ch * 5
                            14 -> 2484
                            in 36..177 -> 5000 + ch * 5
                            else -> 5180
                        }
                    CarLinkBleSpec.ServerConnectionInfo(
                        name = carDisplayName,
                        ssid = ssid,
                        psk = psk,
                        mac = CarLinkBleSpec.WIFI_DEFAULT_MAC_ADDRESS,
                        freq = freq,
                        port = 0,
                        type = CarLinkBleSpec.WIFI_TYPE_P2P,
                    )
                },
                onClientConnected = { device, info ->
                    lastCarLinkBleClientNanos = System.nanoTime()
                    debugLog("CarLink BLE client connected: ${info.name} (${info.model}) [${device.address}]")
                    ensureCarLinkHotspotReady()
                },
                onLog = ::debugLog,
            )
            carLinkBleAdvertiser = advertiser
            mainHandler.post {
                if (!closed) {
                    runCatching { advertiser.start() }
                }
            }
        }
    }

    /** Refreshes the CarLink BLE pairing advertisement and pre-warms the Wi-Fi hotspot. */
    fun refreshCarLinkAdvertisement() {
        synchronized(lifecycleLock) {
            if (closed) return
            ensureCarLinkHotspotReady()
            carLinkBleAdvertiser?.refreshPairingAdvertisement()
        }
    }

    /** Reloads the offline MFi authentication provider without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        mfiProvider = null
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open offline MFi provider. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        notifyBluetoothPeerChanged()
        if (!config.projectionProtocol.enablesCarPlay) {
            if (config.transport == TvTransportMode.WIRELESS) {
                startStandaloneCarLinkWireless()
            } else {
                onStatus(CarPlayStatus.RunningControl)
            }
            return
        }
        if (mfiProvider == null) {
            startMfi()
        } else if (config.transport == TvTransportMode.WIRELESS) {
            restartWireless()
        } else {
            startIphone()
        }
    }

    /** Wakes any pending Bluetooth pairing/RFCOMM wait loop immediately when a phone bonds or connects ACL. */
    fun notifyBluetoothPeerChanged() {
        synchronized(bluetoothPeerWaitLock) {
            bluetoothPeerWaitLock.notifyAll()
        }
        carLinkBleAdvertiser?.let { advertiser ->
            mainHandler.post {
                if (!closed) {
                    advertiser.refreshPairingAdvertisement()
                }
            }
        }
    }

    /** A queued move that a newer move of the same fingers may still overwrite. */
    private class PendingMove(val session: AirPlaySession, var contacts: List<AirPlayContact>)

    private val touchLock = Any()
    private var lastTouch: List<AirPlayContact>? = null
    private var pendingMove: PendingMove? = null

    /**
     * A move keeps the same fingers down. Behind a slow write it replaces the move still waiting in
     * the queue, so only the newest position is sent. Presses and releases are never merged.
     */
    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession
        if (session == null) {
            val cl = carLinkSession
            if (cl != null && cl.isActive() && contacts.isNotEmpty()) {
                val anyDown = contacts.any { it.down }
                val action = if (anyDown) CarLinkUibcDispatcher.ACTION_DOWN else CarLinkUibcDispatcher.ACTION_UP
                val pointers = contacts.map { c ->
                    CarLinkUibcDispatcher.TouchPointer(
                        id = c.id,
                        x = (c.x * airPlayConfig.main.widthPixels).toInt(),
                        y = (c.y * airPlayConfig.main.heightPixels).toInt(),
                    )
                }
                touchExecutor.execute { cl.sendTouch(action, pointers) }
                return true
            }
            return false
        }
        return try {
            synchronized(touchLock) {
                val isMove = isContinuousMove(lastTouch, contacts)
                lastTouch = contacts
                val pending = pendingMove
                if (isMove && pending != null && pending.session === session) {
                    pending.contacts = contacts
                    return true
                }
                if (!isMove) {
                    pendingMove = null
                    touchExecutor.execute { session.sendTouch(contacts) }
                } else {
                    val move = PendingMove(session, contacts)
                    touchExecutor.execute {
                        val latest = synchronized(touchLock) {
                            if (pendingMove === move) pendingMove = null
                            move.contacts
                        }
                        session.sendTouch(latest)
                    }
                    pendingMove = move
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isContinuousMove(
        previous: List<AirPlayContact>?,
        current: List<AirPlayContact>,
    ): Boolean {
        if (previous == null || current.isEmpty()) return false
        for (i in current.indices) {
            if (!current[i].down) return false
        }
        var downCount = 0
        for (i in previous.indices) {
            val prev = previous[i]
            if (!prev.down) continue
            downCount++
            var matched = false
            for (j in current.indices) {
                if (current[j].id == prev.id) {
                    matched = true
                    break
                }
            }
            if (!matched) return false
        }
        return downCount == current.size
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID or CarLink channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession
        if (session == null) {
            val cl = carLinkSession?.takeIf { it.isActive() } ?: return false
            return try {
                touchExecutor.execute {
                    when {
                        state.home -> cl.sendMediaButton(3) // KEYCODE_HOME
                        state.back -> cl.sendMediaButton(4) // KEYCODE_BACK
                        state.select -> cl.sendMediaButton(23) // KEYCODE_DPAD_CENTER
                        else -> cl.sendKnob(dx = state.x + state.wheel, dy = state.y)
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay rotary knob select button down/up state through the AirPlay HID or CarLink channel. */
    fun sendKnobSelect(down: Boolean): Boolean {
        if (closed) return false
        val session = activeSession
        if (session == null) {
            val cl = carLinkSession?.takeIf { it.isActive() } ?: return false
            return try {
                touchExecutor.execute { cl.sendKnobSelect(down) }
                true
            } catch (_: Exception) {
                false
            }
        }
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnobSelect(down)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [app.tvplay.engine.protocol.rtsp.AirPlayHid] media index). */
    fun sendMedia(index: Int): Boolean {
        if (closed) return false
        val session = activeSession
        if (session == null) {
            val cl = carLinkSession?.takeIf { it.isActive() } ?: return false
            val keyCode = when (index) {
                1 -> 126 // KEYCODE_MEDIA_PLAY
                2 -> 127 // KEYCODE_MEDIA_PAUSE
                3 -> 85 // KEYCODE_MEDIA_PLAY_PAUSE
                4 -> 87 // KEYCODE_MEDIA_NEXT
                5 -> 88 // KEYCODE_MEDIA_PREVIOUS
                else -> 85
            }
            return try {
                touchExecutor.execute { cl.sendMediaButton(keyCode) }
                true
            } catch (_: Exception) {
                false
            }
        }
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean = sendMedia(index)

    /** Sends one CarPlay telephony HID report (an [app.tvplay.engine.protocol.rtsp.AirPlayHid] telephony index). */
    fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession
        if (session == null) {
            val cl = carLinkSession?.takeIf { it.isActive() } ?: return false
            return try {
                touchExecutor.execute {
                    if (index == TELEPHONY_DROP) cl.endCall() else cl.answerCall()
                }
                true
            } catch (_: Exception) {
                false
            }
        }
        return try {
            touchExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone/Android phone. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one. */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    /** Opens Siri / Voice Assistant on the connected phone. */
    fun invokeSiri(): Boolean {
        if (closed) return false
        val session = activeSession
        if (session == null) {
            val cl = carLinkSession?.takeIf { it.isActive() } ?: return false
            return try {
                touchExecutor.execute { cl.invokeVoiceAssistant() }
                true
            } catch (_: Exception) {
                false
            }
        }
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun requestSiri(): Boolean = invokeSiri()

    fun currentHotspotInfo(): WirelessHotspotInfo? = activeHotspotInfo

    /** Updates CarPlay / CarLink night mode on the active control session. */
    fun setNightMode(night: Boolean): Boolean {
        if (closed) return false
        carLinkSession?.takeIf { it.isActive() }?.let { cl ->
            touchExecutor.execute { cl.sendNightMode(night) }
        }
        val session = activeSession ?: return carLinkSession?.isActive() == true
        return try {
            touchExecutor.execute { session.setNightMode(night) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends an Android key event to an active ICCOA CarLink session over UIBC / Control channel. */
    fun sendCarLinkKeyEvent(action: Int, androidKeyCode: Int, metaState: Int = 0): Boolean {
        if (closed) return false
        val cl = carLinkSession?.takeIf { it.isActive() } ?: return false
        return try {
            touchExecutor.execute { cl.sendKeyEvent(action, androidKeyCode, metaState) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(wirelessResourceLock) {
            if (closed) return
            closed = true
            wirelessGeneration.incrementAndGet()
        }
        firstTcpWatchdog?.terminate()
        wiredStartWatchdog?.terminate()
        startupTimer.shutdownNow()
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        permissionPollGeneration += 1
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = vpnService
        unbindVpn()
        Thread(
            {
                try {
                    closeBestEffort("CarLink BLE advertiser") { carLinkBleAdvertiser?.close() }
                    carLinkBleAdvertiser = null
                    closeBestEffort("CarLink session") { carLinkSession?.close() }
                    carLinkSession = null
                    if (config.transport == TvTransportMode.WIRELESS) {
                        closeBestEffort("wireless stack") { closeWirelessStack(service) }
                        closeBestEffort("AirPlay wireless service") { service?.detach() }
                    } else {
                        closeBestEffort("CSM") { csm?.close() }
                        csm = null
                    }
                    closeBestEffort("USBMUX") { mux?.close() }
                    mux = null
                    if (config.transport == TvTransportMode.WIRED) {
                        closeBestEffort("VPN/NCM") { service?.detach() }
                    }
                    mfiProvider = null
                    wirelessRuntimeIdentification = null
                    wirelessAirPlayEndpoint = null
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "tvplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Waits for USB, iAP2, MFi and VPN teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun onRouteFrame(frame: Iap2Frame) {
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    enum class InitialAlbumRefreshResult { SENT, FAILED, CLOSED, INACTIVE_SESSION, CANCELLED, CHANNEL_UNAVAILABLE }

    /** Existing metadata channel and worker; the caller owns the single-send budget. */
    fun refreshInitialNowPlaying(
        stillNeeded: () -> Boolean,
        beforeSend: () -> Boolean = { true },
        onFinished: (InitialAlbumRefreshResult) -> Unit = {},
    ) {
        var result = InitialAlbumRefreshResult.CANCELLED
        run {
            try {
                if (closed) { result = InitialAlbumRefreshResult.CLOSED; return@run }
                if (activeSession == null) { result = InitialAlbumRefreshResult.INACTIVE_SESSION; return@run }
                if (!stillNeeded()) return@run
                val channel = if (config.transport == TvTransportMode.WIRELESS) wirelessTunnelChannel else csm
                if (channel == null) {
                    result = InitialAlbumRefreshResult.CHANNEL_UNAVAILABLE
                    debugLog("album recovery skipped channel=unavailable")
                    return@run
                }
                if (closed) { result = InitialAlbumRefreshResult.CLOSED; return@run }
                if (!stillNeeded() || !beforeSend()) return@run
                result = InitialAlbumRefreshResult.FAILED
                channel.send(Iap2ControlMessages.startNowPlayingUpdates(), 250)
                result = InitialAlbumRefreshResult.SENT
                debugLog("album recovery subscription sent")
            } catch (error: Exception) {
                debugLog("album recovery subscription failed type=${error.javaClass.simpleName}")
            } finally {
                onFinished(result)
            }
        }
    }

    private fun onArtworkTransfer(transfer: Iap2ArtworkTransfer) {
        if (closed) return
        synchronized(albumReplayLock) {
            initialArtworkReplay = transfer.takeIf { it.bytes.size <= 1024 * 1024 }
        }
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        if (!hasRequiredUsbService()) return
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        debugLog("mfi discovery backend=OfflineMfiAuthProvider")
        runCatching {
            executor.execute {
                try {
                    val signatures = AtomicInteger(0)
                    val primaryDir = File(appContext.filesDir, OfflineMfiAuthProvider.DIRECTORY)
                    val fallbackDir = File(appContext.noBackupFilesDir, OfflineMfiAuthProvider.DIRECTORY)
                    val directory = if (!File(primaryDir, "identity.pk8").isFile &&
                        File(fallbackDir, "identity.pk8").isFile
                    ) {
                        fallbackDir
                    } else {
                        primaryDir
                    }
                    val client = OfflineMfiAuthProvider.load(directory) { size ->
                        debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                    }
                    if (closed || phase != Phase.MFI) return@execute
                    mfiProvider = client
                    debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
    }

    private fun startPhone() {
        if (config.transport == TvTransportMode.WIRELESS) {
            startWireless()
        } else {
            startIphone()
        }
    }

    private fun startWireless(expectedGeneration: Int? = null) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        runCatching {
            executor.execute { runWireless(generation) }
        }
    }

    private fun restartWireless() {
        val generation = synchronized(wirelessResourceLock) {
            firstTcpWatchdog?.terminate()
            wirelessGeneration.incrementAndGet()
        }
        Thread(
            {
                closeWirelessStack(generation = generation)
                startWireless(generation)
            },
            "tvplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        try {
            debugLog("wireless bring-up generation=$generation starting")
            closeWirelessStack(generation = generation)
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            val listenerIdentity = AirPlayListenerIdentity(generation)
            val watchdog = FirstTcpWatchdog(
                listener = listenerIdentity,
                schedule = { delay, action ->
                    val future = runCatching {
                        startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    }.getOrNull()
                    val cancel: () -> Unit = { future?.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (
                        !closed &&
                        generation == wirelessGeneration.get() &&
                        !isWirelessSessionOrHandoffActive(generation)
                    ) {
                        flipWirelessAddressFamily(generation)
                        fail(
                            WirelessStartupException(
                                WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                                "No AirPlay TCP after CarPlay StartSession",
                            ),
                            generation,
                        )
                        Thread({ closeWirelessStack(generation = generation) }, "tvplay-startup-cleanup")
                            .apply { isDaemon = true; start() }
                    }
                },
                log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
            )
            firstTcpWatchdog = watchdog
            val mfi = mfiProvider
                ?: throw IOException("Offline MFi authentication provider is unavailable")
            val initialAdapter = currentBluetoothAdapter()
            if (initialAdapter != null && initialAdapter.state == BluetoothAdapter.STATE_TURNING_ON) {
                awaitBluetoothEnabled(initialAdapter, timeoutMillis = 3_000L)
            }
            val activeAdapter = currentBluetoothAdapter()
            BluetoothRadioCheck.blockerForState(
                adapterPresent = activeAdapter != null,
                state = activeAdapter?.state,
                pausedByDiPlay = false,
            )?.let { throw BluetoothRadioCheck.failure(it) }
            val hotspotInfo = startWirelessHotspot(generation)
            if (isStaleWirelessRun(generation)) {
                return
            }
            activeHotspotInfo = hotspotInfo
            carLinkBleAdvertiser?.notifyPendingClients()
            val startedHotspot = hotspot
            wirelessConnectionProof.begin(generation) {
                if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
            }
            val wirelessPrefs = appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
            val preferredFamily = if (config.wirelessHotspotMode == TvWirelessMode.LAN_WIFI) {
                HotspotAddressPolicy.FAMILY_IPV4
            } else {
                HotspotAddressPolicy.parseFamily(
                    wirelessPrefs.getString(HOTSPOT_FAMILY_PREFERENCE_KEY, null),
                )
            }
            val addressRecovery = HotspotAddressRecovery(
                sample = { hotspotCandidates(hotspotInfo, fallbackToSnapshot = true) },
                cancelled = { isStaleWirelessRun(generation) },
                pause = Thread::sleep,
                log = { if (!isStaleWirelessRun(generation)) debugLog(it) },
            )
            val candidates = addressRecovery.awaitCandidates(hotspotCandidates(hotspotInfo), preferredFamily)
            val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily)
                ?: hotspotInfo.hostAddress
                ?: throw IOException(
                    "Wireless hotspot did not provide a usable host address",
                )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                wirelessPublishedAddress = hostAddress
                wirelessAlternateAddress = HotspotAddressPolicy.alternateFamilyCandidate(candidates, hostAddress)
                wirelessAddressRecovery = addressRecovery
            }
            if (
                hostAddress is Inet6Address &&
                (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
            ) {
                throw IOException(
                    "Wireless hotspot link-local IPv6 address is not scoped",
                )
            }
            val hostAddressText = hostAddressText(hostAddress)
            val allBindAddresses = (listOf(hostAddress) + candidates + hotspotInfo.hostAddresses)
                .distinctBy { it.address.toList() }
                .filter { it !is Inet6Address || (it.isLinkLocalAddress && it.scopeId != 0) }
            val additionalBindAddresses = allBindAddresses.filter { !it.address.contentEquals(hostAddress.address) }
            val endpointAddressTexts = allBindAddresses.map(::hostAddressText).distinct()
            val liveBssid = hotspotInfo.bssid
                ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
            if (liveBssid != null) {
                wirelessPrefs.edit().putString(WIRELESS_DEVICE_ID_PREFERENCE_KEY, liveBssid).apply()
            }
            val deviceIdentifier = liveBssid
                ?: wirelessPrefs.getString(WIRELESS_DEVICE_ID_PREFERENCE_KEY, null)
                    ?.takeIf { it.isNotBlank() && !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
                ?: airPlayConfig.deviceId
            debugLog(
                "wireless hotspot backend=${hotspotInfo.backend.label} " +
                    "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "preference=${preferredFamily ?: "default"} " +
                    "alternate=${wirelessAlternateAddress != null} " +
                    "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                    "host=$hostAddressText " +
                    "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                    "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
            )
            var startedBonjour: CarPlayBonjour? = null
            val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
            val diagnostics = WirelessStartupDiagnostics(
                sample = {
                    "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                        "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                        (startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started") + "\n" +
                        receiveDiagnostics.snapshot()
                },
                log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
            )
            wirelessDiagnostics = diagnostics
            acquireWifiPerformanceLock()
            onStatus(
                CarPlayStatus.HotspotReady(
                    ssid = hotspotInfo.ssid,
                    band = hotspotInfo.bandLabel,
                    channel = hotspotInfo.channel,
                    bssid = deviceIdentifier,
                    address = hostAddressText,
                    backend = hotspotInfo.backend.label,
                    passphrase = hotspotInfo.passphrase,
                ),
            )

            val adapter = currentBluetoothAdapter()
                ?: throw BluetoothRadioCheck.failure(BluetoothRadioCheck.NO_ADAPTER)
            if (adapter.state == BluetoothAdapter.STATE_TURNING_ON) {
                awaitBluetoothEnabled(adapter, timeoutMillis = 3_000L)
            }
            if (!adapter.isEnabled) throw IOException(BluetoothRadioCheck.RADIO_OFF)
            val hostBluetoothMac = accessoryBluetoothMac(adapter)
            val wirelessAirPlayConfig = airPlayConfig.copy(
                deviceId = deviceIdentifier,
                btMac = hostBluetoothMac,
            )

            onStatus(CarPlayStatus.AttachingNetwork)
            val service = awaitVpnService()
                ?: throw IOException("Could not bind the CarPlay AirPlay service")
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                startedHotspot?.validateReady()
                when (
                    val result = service.attachWireless(
                        bindAddress = hostAddress,
                        config = wirelessAirPlayConfig,
                        identity = identity,
                        pairings = pairings,
                        mfi = mfi,
                        listener = wirelessSessionListener(generation, watchdog),
                        listenerIdentity = watchdog.listener,
                        media = media,
                        additionalBindAddresses = additionalBindAddresses,
                    )
                ) {
                    UsbTunnelVpnService.AttachResult.Started -> Unit
                    UsbTunnelVpnService.AttachResult.AlreadyStarted ->
                        throw IOException("Wireless AirPlay transport is already attached")
                    is UsbTunnelVpnService.AttachResult.Failed -> {
                        debugLog("wireless AirPlay listener attach failed detail=${service.lastAttachFailure}")
                        throw IOException(result.message)
                    }
                }
            }
            val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
            val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
            debugLog(
                "wireless AirPlay listener attached bind=$hostAddressText " +
                    "port=$listenerPort" +
                    (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            val bonjourClient = CarPlayBonjour(
                context = appContext,
                config = advertisedAirPlayConfig,
                identity = identity,
                advertisedHost = hostAddress.hostAddress,
                useInterfaceMdns = true,
                onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
                additionalAddresses = additionalBindAddresses,
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                bonjour = bonjourClient
                startedHotspot?.validateReady()
            }
            bonjourClient.start()
            if (isStaleWirelessRun(generation)) {
                runCatching { bonjourClient.close() }
                return
            }
            startedBonjour = bonjourClient
            diagnostics.start()
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                return
            }

            onStatus(CarPlayStatus.WaitingForPairedIphone)
            val connectedLink = establishWirelessBluetoothLink(adapter, startedHotspot, generation, hostBluetoothMac)
            val device = connectedLink.device
            val socket = connectedLink.socket
            val bluetoothConnectMillis = connectedLink.connectMillis
            debugLog("wireless RFCOMM connected address=${device.address}")
            logBluetoothConnectionSnapshot(device, "after-connect")
            if (isStaleWirelessRun(generation)) {
                runCatching { socket.close() }
                return
            }
            val stream = synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) return
                try {
                    BluetoothRfcommDuplexStream(socket, ::connectionDiagnostic).also { bluetoothStream = it }
                } finally {
                    if (bluetoothSocket === socket) bluetoothSocket = null
                }
            }
            if (BluetoothLinkCheck.looksUnreal(bluetoothConnectMillis, 0L)) {
                InstantBluetoothLink(generation, device, stream, bluetoothConnectMillis).also {
                    instantBluetoothLink = it
                    watchForUnrealBluetooth(it)
                }
            }
            val channel = Iap2Session.openWireless(
                stream,
                traceContext = "wireless-rfcomm",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            synchronized(wirelessResourceLock) {
                if (isStaleWirelessRun(generation)) {
                    channel.close()
                    return
                }
                csm = channel
            }
            debugLog("wireless iAP2 CSM channel opened over RFCOMM")
            if (isStaleWirelessRun(generation)) {
                return
            }
            val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
            val bootstrapIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
                wirelessIdentification,
            )
            val runtimeIdentification = config.identification.forWirelessLink(
                Iap2WirelessLinkRole.RUNTIME_TUNNEL,
                wirelessIdentification,
            )
            val endpoint = Iap2WirelessCarPlayEndpoint(
                ssid = hotspotInfo.ssid,
                passphrase = hotspotInfo.passphrase,
                channel = hotspotInfo.channel,
                security = hotspotInfo.security,
                ipAddresses = endpointAddressTexts,
                airPlayPort = listenerPort,
                deviceIdentifier = deviceIdentifier,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                accessPointBssid = hotspotInfo.accessPointBssid,
            )
            wirelessRuntimeIdentification = runtimeIdentification
            wirelessAirPlayEndpoint = endpoint
            debugLog(
                "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                    "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                    "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
            )
            media.setIapTunnelHandler(::startWirelessTunnelControl)

            onStatus(CarPlayStatus.RunningWireless)
            debugLog(
                "wireless Bluetooth iAP2 bootstrap starting " +
                    "location=false vehicleStatus=false",
            )
            startedHotspot?.validateReady()
            val result = Iap2WirelessControlClient(
                session = channel,
                mfi = Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = bootstrapIdentification,
                endpoint = endpoint,
                timeoutMillis = controlLoopTimeoutMillis(),
                keepAliveAfterDeadline = { keepBluetoothControlAlive(generation) },
                suppressSessionBootstrap = {
                    activeSession != null || wirelessConnectionProof.hasRenderedFrame(generation)
                },
                beforeStartSession = { startedHotspot?.validateReady() },
                onStartSessionSent = {
                    watchdog.startSessionSent(it.sentAtNanos)
                    startedBonjour?.onStartSessionSent()
                },
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    diagnostics.controlProgress(message)
                    debugLog(message)
                },
            )
            if (isStaleWirelessRun(generation)) {
                return
            }
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessSessionOrHandoffActive(generation) ||
                            result.carPlayStartSessionsSent > 0 ||
                            watchdog.isDisarmed() ||
                            watchdog.isArmed()
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                        onWirelessBootstrapEndedWithActiveSession(generation)
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessSessionOrHandoffActive(generation) ||
                            result.carPlayStartSessionsSent > 0 ||
                            watchdog.isDisarmed() ||
                            watchdog.isArmed()
                        if (handoffInProgress) {
                            debugLog(
                                "wireless Bluetooth bootstrap timed out while Wi-Fi AirPlay session is active; " +
                                    "keeping Wi-Fi session alive",
                            )
                            onWirelessBootstrapEndedWithActiveSession(generation)
                        } else {
                            onStatus(CarPlayStatus.ControlEnded)
                        }
                    }
            }
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            val handoffInProgress = isWirelessSessionOrHandoffActive(generation) ||
                firstTcpWatchdog?.isDisarmed() == true ||
                firstTcpWatchdog?.isArmed() == true
            if (handoffInProgress && error !is Error) {
                debugLog("wireless RFCOMM control ended while Wi-Fi AirPlay session is active: ${error.message}")
                onWirelessBootstrapEndedWithActiveSession(generation)
            } else if (config.projectionProtocol.enablesCarLink && error !is Error) {
                if (activeHotspotInfo != null) {
                    debugLog("wireless CarPlay Bluetooth bring-up ended (${error.message}); keeping Wi-Fi Direct hotspot alive for CarLink")
                    onWirelessBootstrapEndedWithActiveSession(generation)
                    onStatus(CarPlayStatus.RunningWireless, generation)
                } else {
                    debugLog("wireless CarPlay bring-up failed (${error.message}); falling back to standalone CarLink Wi-Fi Direct hotspot")
                    closeWirelessStack(generation = generation)
                    startStandaloneCarLinkWireless()
                }
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                val unreal = instantBluetoothLink?.takeIf { it.generation == generation }
                    ?.let { unrealBluetoothFailure(it, "failure") }
                fail(unreal ?: error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    private fun isWirelessSessionOrHandoffActive(generation: Int): Boolean =
        wirelessActiveReported.get() ||
            isWirelessHandoffInProgress(
                handoffRequested = wirelessHandoffRequested.get(),
                tunnelActive = wirelessTunnelChannel != null,
                sessionActive = activeSession != null,
            ) ||
            wirelessConnectionProof.hasRenderedFrame(generation)

    private fun onWirelessBootstrapEndedWithActiveSession(generation: Int) {
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) return
            closeBluetoothBootstrapTransport()
        }
        val hasActiveWifiConnection = activeSession != null ||
            wirelessTunnelChannel != null ||
            wirelessConnectionProof.hasRenderedFrame(generation)
        if (wirelessHandoffRequested.compareAndSet(false, true) && hasActiveWifiConnection) {
            armWirelessHandoffWatchdog(generation)
        }
        maybeCompleteWirelessHandoff()
        if (
            !wirelessActiveReported.get() &&
            !wirelessTunnelReady.get() &&
            (activeSession != null || wirelessConnectionProof.hasRenderedFrame(generation))
        ) {
            if (wirelessActiveReported.compareAndSet(false, true)) {
                wirelessHandoffFellBack.set(true)
                onStatus(CarPlayStatus.WirelessActiveFallback, generation)
            }
        }
    }

    private fun startWirelessTunnelControl(stream: DuplexByteStream): Boolean {
        if (closed || config.transport != TvTransportMode.WIRELESS) return false
        val identification = wirelessRuntimeIdentification ?: return false
        val endpoint = wirelessAirPlayEndpoint ?: return false
        val mfi = mfiProvider ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLog("Could not open the tunneled iAP2 link", error)
            return false
        }
        val previousChannel = wirelessTunnelChannel
        wirelessTunnelChannel = channel
        if (previousChannel != null && previousChannel !== channel) {
            closeBestEffort("previous tunneled iAP2 link") { previousChannel.close() }
        }
        val generation = wirelessGeneration.get()
        debugLog("wireless iAP2 runtime tunnel control starting")
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        suppressSessionBootstrap = { true },
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = ::onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed || generation != wirelessGeneration.get() || wirelessTunnelChannel !== channel) return@execute
                    if (activeSession != null || wirelessConnectionProof.hasRenderedFrame(generation)) {
                        debugLog("tunneled iAP2 control ended (${result.terminal}) while AirPlay session is active; preserving session")
                        if (wirelessActiveReported.compareAndSet(false, true)) {
                            wirelessHandoffFellBack.set(true)
                            onStatus(CarPlayStatus.WirelessActiveFallback, generation)
                        }
                        return@execute
                    }
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed && generation == wirelessGeneration.get() && wirelessTunnelChannel === channel) {
                        if (activeSession != null || wirelessConnectionProof.hasRenderedFrame(generation)) {
                            debugLog("tunneled iAP2 control failed while AirPlay session is active; preserving session: ${error.message}")
                            if (wirelessActiveReported.compareAndSet(false, true)) {
                                wirelessHandoffFellBack.set(true)
                                onStatus(CarPlayStatus.WirelessActiveFallback, generation)
                            }
                        } else {
                            debugLog("tunneled iAP2 control failed", error)
                            onStatus(
                                CarPlayStatus.Failed(
                                    error.message ?: error.javaClass.simpleName,
                                ),
                            )
                        }
                    }
                } finally {
                    closeBestEffort("tunneled iAP2 link") { channel.close() }
                    if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
                }
            }
            true
        } catch (error: Throwable) {
            if (wirelessTunnelChannel === channel) wirelessTunnelChannel = null
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLog("iAP2 tunnel executor rejected the link", error)
            false
        }
    }

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            private var reportedFrameSession: AirPlaySession? = null

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (isStaleWirelessRun(generation)) return
                if (watchdog.accepted(event)) wirelessDiagnostics?.connectionAccepted()
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                if (activeSession == null && !wirelessConnectionProof.hasRenderedFrame(generation)) {
                    watchdog.terminate()
                }
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || !watchdog.sessionEstablished()) return
                watchdog.disarm()
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                sessionListener.onSessionActive(session)
                if (wirelessHandoffRequested.get() && !wirelessActiveReported.get()) {
                    armWirelessHandoffWatchdog(generation)
                    maybeCompleteWirelessHandoff()
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                val wasActive = activeSession === session
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
                if (wasActive && !isStaleWirelessRun(generation) && wirelessActiveReported.get()) {
                    onStatus(CarPlayStatus.ControlEnded, generation)
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || session.isClosed) return
                if (activeSession == null) {
                    onSessionActive(session)
                }
                if (activeSession !== session) return
                if (!watchdog.sessionEstablished()) return
                watchdog.disarm()
                wirelessConnectionProof.rendered(generation, session)
                val firstFrame = synchronized(this) {
                    if (reportedFrameSession === session) false else {
                        reportedFrameSession = session
                        true
                    }
                }
                if (firstFrame) uiListener?.onVideoFrameRendered(session)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(message)
            }
        }

    private fun onWirelessTunnelReady(generation: Int) {
        synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                wirelessFailureReported.get()) return
            wirelessTunnelReady.set(true)
            wirelessHandoffRequested.compareAndSet(false, true)
        }
        wirelessConnectionProof.authenticated(generation)
        debugLog("wireless iAP2 tunnel ready; handoffRequested=${wirelessHandoffRequested.get()}")
        maybeCompleteWirelessHandoff()
    }

    /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
    private fun maybeCompleteWirelessHandoff() {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || phase != Phase.WIRELESS || wirelessFailureReported.get() ||
                !wirelessHandoffRequested.get() || !wirelessTunnelReady.get()) return
            val firstHandoff = wirelessActiveReported.compareAndSet(false, true)
            val promotesFallback = wirelessHandoffFellBack.compareAndSet(true, false)
            if (!firstHandoff && !promotesFallback) return
            wirelessGeneration.get()
        }
        Thread(
            {
                synchronized(wirelessResourceLock) {
                    if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() ||
                        wirelessFailureReported.get() || !wirelessTunnelReady.get()) return@Thread
                    debugLog("wireless handoff ready; closing Bluetooth bootstrap transport")
                    closeBluetoothBootstrapTransport()
                    onStatus(CarPlayStatus.WirelessActive, generation)
                }
            },
            "tvplay-wireless-handoff",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun keepBluetoothControlAlive(generation: Int): Boolean = synchronized(wirelessResourceLock) {
        !closed && phase == Phase.WIRELESS && generation == wirelessGeneration.get() &&
            !wirelessFailureReported.get() &&
            (activeSession != null || wirelessConnectionProof.hasRenderedFrame(generation))
    }

    private fun armWirelessHandoffWatchdog(generation: Int) {
        mainHandler.postDelayed(
            {
                if (
                    closed ||
                    phase != Phase.WIRELESS ||
                    generation != wirelessGeneration.get() ||
                    !wirelessHandoffRequested.get() ||
                    wirelessActiveReported.get()
                ) {
                    return@postDelayed
                }
                debugLog("wireless handoff timed out waiting for tunnel iAP2 readiness")
                Thread(
                    { handleWirelessHandoffTimeout(generation) },
                    "tvplay-wireless-handoff-timeout",
                ).apply {
                    isDaemon = true
                    start()
                }
            },
            WIRELESS_HANDOFF_TIMEOUT_MILLIS,
        )
    }

    private fun handleWirelessHandoffTimeout(generation: Int) = synchronized(wirelessResourceLock) {
        if (closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get() ||
            !wirelessHandoffRequested.get() || wirelessActiveReported.get() || wirelessTunnelReady.get()) {
            return@synchronized
        }
        if (activeSession != null || wirelessConnectionProof.hasRenderedFrame(generation)) {
            if (!wirelessActiveReported.compareAndSet(false, true)) return@synchronized
            wirelessHandoffFellBack.set(true)
            debugLog(
                "wireless handoff fallback; tunnel iAP2 unavailable; " +
                    "preserving AirPlay and keeping the Bluetooth bootstrap for iAP2 control",
            )
            onStatus(CarPlayStatus.WirelessActiveFallback, generation)
        } else {
            closeWirelessStack(generation = generation)
            fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), generation)
        }
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeServer = bluetoothServerSocket
        bluetoothServerSocket = null
        if (activeServer != null) closeBestEffort("wireless RFCOMM server") { activeServer.close() }

        val activeAccepted = acceptedBluetoothSocket
        acceptedBluetoothSocket = null
        if (activeAccepted != null) closeBestEffort("wireless accepted Bluetooth socket") { activeAccepted.close() }

        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun startIphone() {
        diagnosticRun.incrementAndGet()
        availabilityPollGeneration.incrementAndGet()
        permissionPollGeneration++
        val activeCsm = this.csm
        this.csm = null
        val activeMux = this.mux
        this.mux = null
        if (activeCsm != null || activeMux != null) {
            executor.execute {
                if (activeCsm != null) closeBestEffort("wired iAP2 CSM") { activeCsm.close() }
                if (activeMux != null) closeBestEffort("wired USBMUX") { activeMux.close() }
            }
        }
        phase = Phase.IPHONE
        reenumerationAttempts = 0
        iphoneWaitStartedAt = null
        onStatus(CarPlayStatus.DiscoveringIphone)
        checkIphoneAvailability()
    }

    private fun checkIphoneAvailability() {
        if (closed || phase != Phase.IPHONE) return
        val device = iphoneHost.discover().firstOrNull()
        if (device == null) {
            reportIphoneMissing()
            val now = SystemClock.elapsedRealtime()
            val waitStarted = iphoneWaitStartedAt ?: now.also { iphoneWaitStartedAt = it }
            onStatus(
                if (now - waitStarted < IPHONE_NOT_LISTED_AFTER_MILLIS) CarPlayStatus.WaitingForIphone
                else CarPlayStatus.IphoneNotListed(iphoneHost.listsNonHubDevice()),
            )
            scheduleAvailabilityPoll(Phase.IPHONE, ::checkIphoneAvailability)
        } else {
            lastIphoneListing = null
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            availabilityPollGeneration.incrementAndGet()
            requestIphonePermission(device)
        }
    }

    private fun reportIphoneMissing() {
        val listed = runCatching { iphoneHost.listedDevices().joinToString(", ") }.getOrElse { "unavailable" }
        if (listed == lastIphoneListing) return
        lastIphoneListing = listed
        debugLog("wired iPhone not listed by Android; USB devices=[$listed]")
    }

    private fun requestIphonePermission(device: UsbDevice) {
        mainHandler.post { doRequestIphonePermission(device) }
    }

    private fun doRequestIphonePermission(device: UsbDevice, allowAdbFallback: Boolean = true) {
        if (closed) return
        try {
            when (val request = iphoneHost.requestPermission(device, allowAdbFallback)) {
                is IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    permissionGrant.set(false)
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(request.device))
                }
                is IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog(
                        "wired iPhone USB permission requested " +
                            "afterMissingUiAdbFailure=${request.afterMissingUiAdbFailure}",
                    )
                    permissionGrant.set(false)
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollIphonePermission(device, request.afterMissingUiAdbFailure)
                }
                is IphoneUsbHost.PermissionRequest.AdbRequired -> {
                    doRequestIphonePermission(device, allowAdbFallback = false)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun onIphonePermission(result: IphoneUsbHost.PermissionResult) {
        when (result) {
            is IphoneUsbHost.PermissionResult.Granted -> {
                if (!permissionGrant.compareAndSet(false, true)) return
                debugLog("wired iPhone USB permission granted")
                permissionPollGeneration++
                when (phase) {
                    Phase.REENUMERATION, Phase.IPHONE -> {
                        val configuration = IphoneCarPlayConfiguration.find(result.device)
                        val carPlayMode = configuration?.let(IphoneCarPlayConfiguration::isCarPlayMode) == true
                        val sessionAlreadyStarted = WiredSessionMemory.startedOn(result.device.deviceName)
                        val action = WiredConfigurationAction.choose(
                            found = configuration != null,
                            carPlayMode = carPlayMode,
                            transitionsTried = reenumerationAttempts,
                            maximumTransitions = MAXIMUM_REENUMERATION_ATTEMPTS,
                            sessionAlreadyStarted = sessionAlreadyStarted,
                        )
                        connectionDiagnostic(
                            "USB configuration ready=${configuration != null} " +
                                "configurationId=${configuration?.id ?: "none"} " +
                                "reenumerationAttempts=$reenumerationAttempts " +
                                (if (sessionAlreadyStarted && carPlayMode) "previousSessionOnThisEnumeration=true " else "") +
                                "action=${action.diagnostic}",
                        )
                        when (action) {
                            WiredConfigurationAction.USE,
                            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET -> openDataPaths(result.device)
                            WiredConfigurationAction.REQUEST_TRANSITION -> beginReenumeration(
                                result.device,
                                WiredConfigurationAction.fallbackAfterRejection(
                                    configuration != null,
                                    carPlayMode,
                                    sessionAlreadyStarted,
                                ),
                            )
                            WiredConfigurationAction.REJECT -> fail(
                                IphoneUsbException.Protocol(
                                    "iPhone did not expose a complete CarPlay USB configuration",
                                ),
                            )
                        }
                    }
                    else -> Unit
                }
            }
            is IphoneUsbHost.PermissionResult.Denied -> {
                permissionGrant.set(true)
                onStatus(CarPlayStatus.Failed("iPhone USB permission was denied"))
            }
        }
    }

    private fun pollIphonePermission(device: UsbDevice, afterMissingUiAdbFailure: Boolean = false) {
        val generation = ++permissionPollGeneration
        val deadlineNanos = System.nanoTime() + PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                if (closed || generation != permissionPollGeneration) return
                if (requireUsbManager().hasPermission(device)) {
                    onIphonePermission(IphoneUsbHost.PermissionResult.Granted(device))
                    return
                }
                if (System.nanoTime() >= deadlineNanos) {
                    if (permissionGrant.compareAndSet(false, true)) {
                        onStatus(
                            CarPlayStatus.Failed(
                                if (afterMissingUiAdbFailure) {
                                    "The device's standard USB authorization page is unavailable, and neither " +
                                        "approved ADB nor its system permission request granted iPhone access"
                                } else {
                                    "iPhone USB permission was not granted; tap Reconnect iPhone to retry"
                                },
                            ),
                        )
                    }
                    return
                }
                mainHandler.postDelayed(this, PERMISSION_POLL_INTERVAL_MILLIS)
            }
        }
        mainHandler.postDelayed(check, PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun beginReenumeration(device: UsbDevice, fallbackAfterRejection: Boolean = false) {
        phase = Phase.REENUMERATION
        reenumerationAttempts += 1
        connectionDiagnostic("USB transition requested count=$reenumerationAttempts")
        onStatus(CarPlayStatus.SelectingConfiguration)
        iphoneHost.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    mainHandler.post { watchReenumeration(device) }
                }
                is IphoneUsbHost.TransitionResult.Failed -> if (fallbackAfterRejection) {
                    connectionDiagnostic(
                        "USB transition rejected; using the configuration the iPhone offers " +
                            "error=${transition.error.message}",
                    )
                    mainHandler.post {
                        if (!closed && phase == Phase.REENUMERATION) openDataPaths(device)
                    }
                } else {
                    fail(transition.error)
                }
            }
        }
    }

    private fun watchReenumeration(device: UsbDevice) {
        val attempt = reenumerationAttempts
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed({
            if (closed || phase != Phase.REENUMERATION || attempt != reenumerationAttempts ||
                generation != availabilityPollGeneration.get()) return@postDelayed
            val listed = iphoneHost.discover().firstOrNull { it.deviceName == device.deviceName } ?: return@postDelayed
            connectionDiagnostic(
                "USB transition unanswered after ${REENUMERATION_WAIT_MILLIS}ms count=$attempt; checking the iPhone again",
            )
            requestIphonePermission(listed)
        }, REENUMERATION_WAIT_MILLIS)
    }

    private fun onIphoneAttached(device: UsbDevice) {
        when (phase) {
            Phase.REENUMERATION, Phase.IPHONE -> {
                availabilityPollGeneration.incrementAndGet()
                requestIphonePermission(device)
            }
            else -> Unit
        }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS,
        )
    }

    private fun openDataPaths(device: UsbDevice) {
        phase = Phase.DATAPATHS
        wiredDeviceName = device.deviceName
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        iphoneHost.openIap2UsbSessionAsync(device, executor) { result ->
            when (result) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> {
                    try {
                        val ncm = openNcm(device)
                        runStack(result.session, ncm)
                    } catch (error: Throwable) {
                        result.session.close()
                        fail(error)
                    }
                }
                is IphoneUsbHost.Iap2SessionResult.Failed -> fail(result.error)
            }
        }
    }

    private fun openNcm(device: UsbDevice): NcmUsbBridge {
        val configuration = IphoneCarPlayConfiguration.find(device)
            ?: throw IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = NcmFunctionDiscovery.find(configuration)
            ?: throw IphoneUsbException.Protocol("iPhone configuration does not expose an NCM function")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/${function.control.alternateSetting}" +
                " data=${function.data.id}/${function.data.alternateSetting}" +
                " status=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}",
        )
        val connection = requireUsbManager().openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("Could not open the iPhone NCM connection")
        return NcmUsbBridge.open(connection, function, onDiagnostic = ::connectionDiagnostic)
    }

    private fun runStack(usbSession: Iap2UsbSession, ncm: NcmUsbBridge) {
        phase = Phase.CONTROL
        var ncmOwnedLocally = true
        try {
            if (closed) return
            val mux = Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            this.mux = mux
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = LockdownPairingClient(mux::connect)
            val savedPairRecord = loadPairRecord()
            var pairRecord = savedPairRecord ?: pairNewRecord(pairingClient)
            debugLog(
                if (savedPairRecord != null) {
                    "wired using saved Lockdown pair record"
                } else {
                    "wired created a new Lockdown pair record"
                },
            )
            onStatus(CarPlayStatus.ConnectingControl)
            val carKitClient = LockdownCarKitClient(mux::connect)
            var syslogRelay: DuplexByteStream? = null
            if (sessionListener.wantsTrace) {
                try {
                    val relay = carKitClient.openService(pairRecord, config.label, "com.apple.syslog_relay")
                    syslogRelay = relay
                    Thread({
                        try {
                            relay.use {
                                val deadline = System.nanoTime() + 120_000_000_000L
                                val pending = StringBuilder()
                                while (!closed && System.nanoTime() < deadline) {
                                    val bytes = relay.recv(8192, 1000) ?: continue
                                    if (bytes.isEmpty()) break
                                    pending.append(bytes.toString(Charsets.UTF_8).replace('\u0000', '\n'))
                                    while (true) {
                                        val end = pending.indexOf("\n")
                                        if (end < 0) break
                                        val line = pending.substring(0, end)
                                        pending.delete(0, end + 1)
                                        if (SYSLOG_RELEVANT_REGEX.containsMatchIn(line)) debugLog("PHONE ${line.take(2000)}")
                                    }
                                    if (pending.length > 65536) pending.clear()
                                }
                            }
                            debugLog("phone authentication diagnostic capture ended")
                        } catch (error: Exception) {
                            debugLog("phone authentication diagnostic capture ended: ${error.javaClass.simpleName}")
                        }
                    }, "carplay-lab-phone-diagnostics").apply { isDaemon = true; start() }
                    debugLog("phone authentication diagnostic capture started")
                } catch (error: Exception) {
                    debugLog("phone authentication diagnostics unavailable: ${error.message}")
                }
            }
            val carkit = try {
                carKitClient.open(pairRecord, config.label)
            } catch (error: Throwable) {
                val rejection = rejectedPairRecordError(error)
                if (savedPairRecord == null || rejection == null) throw error
                debugLog("saved Lockdown pair record rejected by Lockdown error=$rejection; clearing and pairing again")
                clearPairRecord()
                pairRecord = pairNewRecord(pairingClient)
                carKitClient.open(pairRecord, config.label)
            }
            debugLog("wired com.apple.carkit.service stream opened")
            fun wireSummary(bytes: ByteArray): String {
                if (bytes.size < 9 || bytes[0].toInt() and 0xff != 0xff ||
                    bytes[1].toInt() and 0xff != 0x5a) return "bytes=${bytes.size}"
                fun value(index: Int) = bytes[index].toInt() and 0xff
                return "bytes=${bytes.size} length=${(value(2) shl 8) or value(3)} " +
                    "flags=${value(4)} seq=${value(5)} ack=${value(6)} session=${value(7)}"
            }
            val tracedCarkit = object : DuplexByteStream {
                private val io = ConnectionIoDiagnostics(::connectionDiagnostic)
                override fun send(data: ByteArray) {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        if (sessionListener.wantsTrace) debugLog("wired link TX begin ${wireSummary(data)}")
                        carkit.send(data)
                        if (sessionListener.wantsTrace) debugLog("wired link TX completed bytes=${data.size}")
                        result = ConnectionIoDiagnostics.Result.COMPLETED
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.WRITE, result, elapsedMillis(started))
                    }
                }
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                    val started = System.nanoTime()
                    var result = ConnectionIoDiagnostics.Result.FAILED
                    try {
                        return carkit.recv(maxBytes, timeoutMillis).also { bytes ->
                            result = when {
                                bytes == null -> ConnectionIoDiagnostics.Result.TIMED_OUT
                                bytes.isEmpty() -> ConnectionIoDiagnostics.Result.ENDED
                                else -> ConnectionIoDiagnostics.Result.COMPLETED
                            }
                            if (bytes != null && sessionListener.wantsTrace) debugLog("wired link RX ${wireSummary(bytes)}")
                        }
                    } finally {
                        io.record(ConnectionIoDiagnostics.Operation.READ, result, elapsedMillis(started))
                    }
                }
                override fun close() {
                    try {
                        carkit.close()
                    } finally {
                        runCatching { syslogRelay?.close() }
                        io.finish()
                    }
                }
            }
            val csm = Iap2Session.open(
                tracedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            this.csm = csm
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            debugLog("ncm using hostMac=${ncmHostMac.macString()}")
            if (!attachVpn(ncm, ncmHostMac)) {
                throw IphoneUsbException.DeviceUnavailable("Could not attach the NCM/VPN AirPlay transport")
            }
            ncmOwnedLocally = false
            debugLog("wired NCM/VPN AirPlay transport attached")
            if (closed) {
                vpnService?.detach()
                return
            }

            val mfi = mfiProvider
                ?: throw IphoneUsbException.DeviceUnavailable("Offline MFi authentication provider is unavailable")
            val endpoint = Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = vpnService?.boundPort() ?: airPlayConfig.port,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.macString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            wiredSessionActive = false
            wiredStartSentAtNanos = null
            fun startAgain(): Iap2WiredControlClient.StartAgain {
                val opened = ncm.linkOpenedAtNanos
                return WiredStartAgain.decide(
                    nowNanos = System.nanoTime(),
                    startSentAtNanos = wiredStartSentAtNanos ?: System.nanoTime(),
                    linkOpenedAtNanos = opened,
                    sessionActive = wiredSessionActive,
                    connectionAttemptSinceLinkOpened =
                        opened != null && ncm.connectionAttemptSince(endpoint.airPlayPort, opened),
                )
            }
            lateinit var startWatchdog: WiredStartWatchdog
            startWatchdog = WiredStartWatchdog(
                schedule = { delay, action ->
                    val future = runCatching {
                        startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                    }.getOrNull()
                    val cancel: () -> Unit = { future?.cancel(false); Unit }
                    cancel
                },
                onTimeout = {
                    if (!closed && wiredStartWatchdog === startWatchdog) {
                        val waitedSeconds = wiredStartSentAtNanos?.let {
                            TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - it)
                        }
                        debugLog(
                            "wired start: no AirPlay session $waitedSeconds s " +
                                "after carplay-start-session; NCM ${ncm.trafficSummary(endpoint.airPlayPort)}",
                        )
                        fail(IphoneUsbException.TimedOut("The iPhone did not open CarPlay over USB in time"))
                    }
                },
                secondRequestCanStillGoOut = { startAgain() != Iap2WiredControlClient.StartAgain.DONE },
                onHold = {
                    debugLog(
                        "wired start: no AirPlay session ${WiredStartWatchdog.TIMEOUT_MILLIS / 1000} s " +
                            "after carplay-start-session; holding the limit until " +
                            "${WiredStartWatchdog.HELD_TIMEOUT_MILLIS / 1000} s for the second request; " +
                            "NCM ${ncm.trafficSummary(endpoint.airPlayPort)}",
                    )
                },
            )
            wiredStartWatchdog = startWatchdog
            debugLog("wired iAP2 runtime control starting")
            val result = Iap2WiredControlClient(csm, Iap2MfiAuthenticationClient(mfi)).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                timeoutMillis = controlLoopTimeoutMillis(),
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    if (message == Iap2WiredControlClient.START_SESSION_AGAIN_PROGRESS) {
                        startWatchdog.startSessionSentAgain()
                    } else if (message.startsWith(START_SESSION_PROGRESS)) {
                        if (wiredStartSentAtNanos == null) wiredStartSentAtNanos = System.nanoTime()
                        wiredDeviceName?.let(WiredSessionMemory::started)
                        startWatchdog.startSessionSent()
                    }
                    debugLog("wired $message")
                },
                startSessionAgain = ::startAgain,
            )
            if (result.terminal == Iap2WiredControlTerminal.CHANNEL_CLOSED) {
                val activeCsm = this.csm
                if (activeCsm != null) {
                    this.csm = null
                    closeBestEffort("wired iAP2 CSM") { activeCsm.close() }
                }
                val activeMux = this.mux
                if (activeMux != null) {
                    this.mux = null
                    closeBestEffort("wired USBMUX") { activeMux.close() }
                }
                if (!ncmOwnedLocally) {
                    closeBestEffort("wired VPN transport") { vpnService?.detach() }
                }
                onStatus(CarPlayStatus.Failed("CarPlay control channel closed"))
            } else {
                onStatus(CarPlayStatus.ControlEnded)
            }
        } catch (error: Throwable) {
            debugLog("wired bring-up failed", error)
            val activeCsm = this.csm
            if (activeCsm != null) {
                this.csm = null
                closeBestEffort("wired iAP2 CSM") { activeCsm.close() }
            }
            val activeMux = this.mux
            if (activeMux != null) {
                this.mux = null
                closeBestEffort("wired USBMUX") { activeMux.close() }
            }
            if (!ncmOwnedLocally) {
                closeBestEffort("wired VPN transport") { vpnService?.detach() }
            }
            fail(error)
        } finally {
            wiredStartWatchdog?.terminate()
            if (ncmOwnedLocally) {
                closeBestEffort("wired NCM") { ncm.close() }
            }
        }
    }

    private fun pairNewRecord(client: LockdownPairingClient): LockdownPairRecord =
        client.pair(
            label = config.label,
            hostId = hostId,
            systemBuid = systemBuid,
            totalTimeoutMillis = PAIR_TIMEOUT_MILLIS,
            isCancelled = { closed },
        ).pairRecord.also(savePairRecord)

    private fun rejectedPairRecordError(error: Throwable): String? {
        var cause: Throwable? = error
        while (cause != null) {
            val message = cause.message.orEmpty()
            if (message.contains("InvalidPairRecord", ignoreCase = true)) return "InvalidPairRecord"
            if (message.contains("InvalidHostID", ignoreCase = true)) return "InvalidHostID"
            cause = cause.cause
        }
        return null
    }

    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    private fun createHotspotManager(mode: TvWirelessMode, generation: Int): WirelessHotspotManager =
        when (mode) {
            TvWirelessMode.WIFI_DIRECT -> WifiDirectGroupLink(
                appContext,
                ::debugLog,
                preferredChannel = config.wifiDirectPreferredChannel,
            )
            TvWirelessMode.LAN_WIFI -> LanNetworkLink(
                appContext,
                config.lanWifiSsid,
                config.lanWifiPassphrase,
                ::debugLog,
                onNetworkChanged = {
                    if (!isStaleWirelessRun(generation) && !isWirelessSessionOrHandoffActive(generation)) {
                        restartWireless()
                    } else {
                        debugLog("existing Wi-Fi network change ignored while CarPlay session is active")
                    }
                },
                preferredInterface = config.lanNetworkInterface,
            )
        }

    private fun startWirelessHotspot(
        generation: Int,
        modeOverride: TvWirelessMode? = null,
    ): WirelessHotspotInfo {
        val hotspotMode = modeOverride ?: if (config.projectionProtocol.enablesCarLink) {
            TvWirelessMode.WIFI_DIRECT
        } else {
            config.wirelessMode
        }
        val manager = createHotspotManager(hotspotMode, generation)
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw InterruptedIOException("Hotspot startup cancelled")
            }
            hotspot = manager
        }
        val timeoutMillis = if (hotspotMode == TvWirelessMode.WIFI_DIRECT) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else {
            10_000L
        }
        return try {
            manager.start(timeoutMillis).also { activeHotspotInfo = it }
        } catch (failure: Exception) {
            if (hotspot === manager) hotspot = null
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            if (failure is P2pResetRequiredException) throw failure
            if (failure is WirelessStartupException) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    private data class ConnectedBluetoothLink(
        val device: BluetoothDevice,
        val socket: BluetoothSocket,
        val connectMillis: Long,
    )

    private fun acquireWifiPerformanceLock() = synchronized(wirelessResourceLock) {
        if (closed || wifiPerformanceLock != null) return@synchronized
        val wifiManager = runCatching {
            appContext.applicationContext.getSystemService(WifiManager::class.java)
        }.getOrNull() ?: return@synchronized
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val lock = runCatching {
            wifiManager.createWifiLock(mode, "tvplay-wireless-carplay").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        if (lock != null) {
            wifiPerformanceLock = lock
            debugLog("wireless low-latency WifiLock acquired")
        }
    }

    private fun releaseWifiPerformanceLock() {
        val lock = wifiPerformanceLock ?: return
        wifiPerformanceLock = null
        runCatching {
            if (lock.isHeld) lock.release()
        }
    }

    /**
     * Publishes the Apple Accessory iAP2 service (`00000000-deca-fade-deca-deafdecacaff`) in the TV's
     * Bluetooth SDP table and accepts inbound RFCOMM connections initiated by iOS when the user taps
     * the TV in `Settings -> Bluetooth` or `Settings -> General -> CarPlay`.
     */
    private fun startAccessoryIap2Server(adapter: BluetoothAdapter, generation: Int) {
        val server = synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation) || bluetoothServerSocket != null || acceptedBluetoothSocket != null) {
                return
            }
            val accessoryUuid = UUID.fromString(IAP2_ACCESSORY_UUID)
            runCatching {
                adapter.listenUsingRfcommWithServiceRecord("Wireless iAP", accessoryUuid)
            }.recoverCatching {
                adapter.listenUsingInsecureRfcommWithServiceRecord("Wireless iAP", accessoryUuid)
            }.getOrNull()?.also { bluetoothServerSocket = it }
        } ?: return
        debugLog("wireless iAP2 SDP server listening uuid=$IAP2_ACCESSORY_UUID")
        Thread(
            {
                try {
                    while (!isStaleWirelessRun(generation)) {
                        val incoming = try {
                            server.accept()
                        } catch (_: IOException) {
                            break
                        } ?: continue
                        val remote = incoming.remoteDevice
                        val remoteName = runCatching { remote?.name }.getOrNull()
                        val remoteClass = runCatching { remote?.bluetoothClass?.majorDeviceClass }.getOrNull()
                        if (remote == null || isNonPhoneBluetoothAccessory(remoteName, remoteClass)) {
                            runCatching { incoming.close() }
                            continue
                        }
                        var accepted = false
                        var inFlightOutbound: BluetoothSocket? = null
                        synchronized(wirelessResourceLock) {
                            if (!isStaleWirelessRun(generation) && bluetoothServerSocket === server) {
                                acceptedBluetoothSocket?.let { runCatching { it.close() } }
                                acceptedBluetoothSocket = incoming
                                inFlightOutbound = bluetoothSocket
                                bluetoothSocket = null
                                accepted = true
                            }
                        }
                        if (accepted) {
                            inFlightOutbound?.let { runCatching { it.close() } }
                            debugLog("wireless accepted inbound iAP2 RFCOMM from address=${remote.address}")
                            notifyBluetoothPeerChanged()
                            break
                        } else {
                            runCatching { incoming.close() }
                            break
                        }
                    }
                } finally {
                    synchronized(wirelessResourceLock) {
                        if (bluetoothServerSocket === server) {
                            bluetoothServerSocket = null
                        }
                    }
                    runCatching { server.close() }
                }
            },
            "wireless-rfcomm-accept",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun takeAcceptedBluetoothSocket(): BluetoothSocket? = synchronized(wirelessResourceLock) {
        val socket = acceptedBluetoothSocket ?: return@synchronized null
        acceptedBluetoothSocket = null
        val server = bluetoothServerSocket
        bluetoothServerSocket = null
        if (server != null) runCatching { server.close() }
        socket
    }

    private fun closeAccessoryIap2Server() = synchronized(wirelessResourceLock) {
        val server = bluetoothServerSocket
        bluetoothServerSocket = null
        if (server != null) runCatching { server.close() }
        val accepted = acceptedBluetoothSocket
        acceptedBluetoothSocket = null
        if (accepted != null) runCatching { accepted.close() }
    }

    private fun waitForBluetoothPeerChange(timeoutMillis: Long) {
        if (timeoutMillis <= 0L) return
        synchronized(bluetoothPeerWaitLock) {
            try {
                bluetoothPeerWaitLock.wait(timeoutMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun establishWirelessBluetoothLink(
        adapter: BluetoothAdapter,
        startedHotspot: WirelessHotspotManager?,
        generation: Int,
        hostBluetoothMac: String,
    ): ConnectedBluetoothLink {
        val carLinkCoexistence = config.projectionProtocol.enablesCarLink
        val waitStartNanos = System.nanoTime()
        val waitDeadlineNanos = waitStartNanos + PAIRING_WAIT_TIMEOUT_MILLIS * 1_000_000L
        var targetDevice: BluetoothDevice? = null
        var loggedWaitingForPairing = false
        var reportedDualModeRunning = false
        startAccessoryIap2Server(adapter, generation)
        try {
            while (true) {
                while (targetDevice == null) {
                    if (isStaleWirelessRun(generation)) {
                        throw InterruptedIOException("Wireless Bluetooth selection cancelled")
                    }
                    takeAcceptedBluetoothSocket()?.let { inbound ->
                        return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                    }
                    if (!adapter.isEnabled) {
                        throw IOException(BluetoothRadioCheck.RADIO_OFF)
                    }
                    if (carLinkCoexistence && isCarLinkBleRecentlyActive()) {
                        if (!reportedDualModeRunning) {
                            reportedDualModeRunning = true
                            onStatus(CarPlayStatus.RunningWireless, generation)
                        }
                        startedHotspot?.validateReady()
                        waitForBluetoothPeerChange(1_500L)
                        continue
                    }
                    try {
                        targetDevice = selectWirelessBluetoothDevice(adapter)
                    } catch (failure: ConnectionFailureException) {
                        startAccessoryIap2Server(adapter, generation)
                        if (carLinkCoexistence) {
                            if (!reportedDualModeRunning) {
                                reportedDualModeRunning = true
                                onStatus(CarPlayStatus.RunningWireless, generation)
                            }
                            if (!loggedWaitingForPairing) {
                                loggedWaitingForPairing = true
                                debugLog(
                                    "wireless dual-mode: keeping Wi-Fi Direct hotspot, AirPlay, and ICCOA CarLink ready " +
                                        "while waiting for phone connection (${failure.reason})",
                                )
                            }
                            startedHotspot?.validateReady()
                            waitForBluetoothPeerChange(2_500L)
                            continue
                        }
                        val waitWindowMillis = if (failure.reason == ConnectionFailureCause.IPHONE_NOT_CHOSEN) {
                            12_000L
                        } else {
                            PAIRING_WAIT_TIMEOUT_MILLIS
                        }
                        val effectiveDeadline = minOf(
                            waitDeadlineNanos,
                            waitStartNanos + waitWindowMillis * 1_000_000L,
                        )
                        if (System.nanoTime() >= effectiveDeadline) {
                            throw failure
                        }
                        if (!loggedWaitingForPairing) {
                            loggedWaitingForPairing = true
                            debugLog(
                                "wireless waiting for iPhone Bluetooth pairing/connection (${failure.reason}) " +
                                    "while keeping hotspot and AirPlay ready",
                            )
                        }
                        startedHotspot?.validateReady()
                        waitForBluetoothPeerChange(1_000L)
                    }
                }

                takeAcceptedBluetoothSocket()?.let { inbound ->
                    return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                }

                var device: BluetoothDevice = requireNotNull(targetDevice)
                debugLog(
                    "wireless selected Bluetooth target name=${runCatching { device.name }.getOrNull() ?: "unknown"} " +
                        "address=${device.address} localBt=$hostBluetoothMac",
                )
                onStatus(CarPlayStatus.ConnectingBluetooth)
                runCatching { adapter.cancelDiscovery() }

                var lastError: Throwable? = null
                for (attempt in 1..MAX_RFCOMM_ATTEMPTS) {
                    if (isStaleWirelessRun(generation)) {
                        throw InterruptedIOException("Wireless RFCOMM connect cancelled")
                    }
                    startAccessoryIap2Server(adapter, generation)
                    takeAcceptedBluetoothSocket()?.let { inbound ->
                        return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                    }
                    if (!adapter.isEnabled) throw IOException(BluetoothRadioCheck.RADIO_OFF)
                    if (carLinkCoexistence && isCarLinkBleRecentlyActive()) {
                        debugLog("wireless pausing iAP2 RFCOMM connect while ICCOA CarLink BLE/session is active")
                        break
                    }

                    // When the iPhone has just established an ACL link (e.g., user tapped the TV in
                    // iOS CarPlay settings), iOS opens an inbound RFCOMM channel to IAP2_ACCESSORY_UUID
                    // within ~200-1500ms. Give that inbound connection a head start so an immediate
                    // outbound RFCOMM connect does not collide with iOS SDP/RFCOMM setup.
                    if (isBluetoothDeviceConnected(device)) {
                        val inboundGraceDeadline = System.nanoTime() + 1_800L * 1_000_000L
                        while (!isStaleWirelessRun(generation) && System.nanoTime() < inboundGraceDeadline) {
                            takeAcceptedBluetoothSocket()?.let { inbound ->
                                return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                            }
                            waitForBluetoothPeerChange(120L)
                        }
                    }
                    takeAcceptedBluetoothSocket()?.let { inbound ->
                        return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                    }

                    debugLog(
                        "wireless RFCOMM connecting attempt=$attempt/$MAX_RFCOMM_ATTEMPTS " +
                            "address=${device.address} uuid=$IAP2_IPHONE_UUID",
                    )
                    val socket = synchronized(wirelessResourceLock) {
                        if (isStaleWirelessRun(generation)) {
                            throw InterruptedIOException("Wireless RFCOMM connect cancelled")
                        }
                        device.createRfcommSocketToServiceRecord(UUID.fromString(IAP2_IPHONE_UUID))
                            .also { bluetoothSocket = it }
                    }
                    if (attempt == 1) {
                        logBluetoothConnectionSnapshot(device, "before-connect")
                    }
                    val bluetoothStarted = System.nanoTime()
                    try {
                        connectBluetoothSocket(socket, device.address)
                        val connectMillis = elapsedMillis(bluetoothStarted)
                        connectionDiagnostic(
                            "Bluetooth connect completed attempt=$attempt elapsedMs=$connectMillis " +
                                "socketReportedConnected=${runCatching { socket.isConnected }.getOrNull() ?: "unknown"}",
                        )
                        return ConnectedBluetoothLink(device, socket, connectMillis)
                    } catch (error: Throwable) {
                        runCatching { socket.close() }
                        synchronized(wirelessResourceLock) {
                            if (bluetoothSocket === socket) bluetoothSocket = null
                        }
                        takeAcceptedBluetoothSocket()?.let { inbound ->
                            return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                        }
                        connectionDiagnostic(
                            "Bluetooth connect failed attempt=$attempt elapsedMs=${elapsedMillis(bluetoothStarted)} " +
                                "failureClass=${diagnosticFailureClass(error)}",
                        )
                        lastError = error
                        if (
                            isStaleWirelessRun(generation) ||
                            BluetoothLinkCheck.socketServiceMissing(error) ||
                            !adapter.isEnabled ||
                            attempt == MAX_RFCOMM_ATTEMPTS
                        ) {
                            logBluetoothConnectionSnapshot(device, "after-failure")
                            val hasIap2 = runCatching {
                                device.uuids?.any { it.uuid.toString().equals(IAP2_IPHONE_UUID, ignoreCase = true) } == true
                            }.getOrDefault(false)
                            val nameHasIphone = runCatching {
                                device.name?.contains("iPhone", ignoreCase = true) == true ||
                                    device.name?.contains("iPad", ignoreCase = true) == true
                            }.getOrDefault(false)
                            if (!hasIap2 && !nameHasIphone) {
                                runCatching { device.address?.uppercase(Locale.US) }
                                    .getOrNull()
                                    ?.let { nonIphoneBluetoothAddresses.add(it) }
                            }
                            if (carLinkCoexistence && !isStaleWirelessRun(generation) && adapter.isEnabled) {
                                break
                            }
                            throw error
                        }
                        startedHotspot?.validateReady()
                        debugLog(
                            "wireless RFCOMM connect attempt $attempt/$MAX_RFCOMM_ATTEMPTS failed " +
                                "(${error.message}); retrying in ${RFCOMM_RETRY_DELAY_MILLIS}ms while keeping hotspot alive",
                        )
                        startAccessoryIap2Server(adapter, generation)
                        waitForBluetoothPeerChange(RFCOMM_RETRY_DELAY_MILLIS)
                        takeAcceptedBluetoothSocket()?.let { inbound ->
                            return ConnectedBluetoothLink(inbound.remoteDevice, inbound, connectMillis = 250L)
                        }
                        runCatching { selectWirelessBluetoothDevice(adapter) }.getOrNull()?.let { refreshed ->
                            device = refreshed
                        }
                    }
                }
                if (carLinkCoexistence && !isStaleWirelessRun(generation) && adapter.isEnabled) {
                    targetDevice = null
                    onStatus(CarPlayStatus.RunningWireless, generation)
                    startedHotspot?.validateReady()
                    waitForBluetoothPeerChange(4_000L)
                    continue
                }
                throw lastError ?: IOException("Could not connect RFCOMM to ${device.address}")
            }
        } finally {
            closeAccessoryIap2Server()
        }
    }

    private val nonIphoneBluetoothAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun isKnownAndroidPhoneName(name: String?): Boolean {
        val raw = name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val lower = raw.lowercase(Locale.US)
        if (lower.contains("iphone") || lower.contains("ipad")) return false
        val androidKeywords = listOf(
            "xiaomi", "redmi", "poco", "mi ", "mix ", "blackshark",
            "vivo", "iqoo", "oppo", "oneplus", "realme", "find x", "reno",
            "huawei", "honor", "harmony", "mate ", "pura ",
            "samsung", "galaxy", "sm-", "pixel", "meizu", "motorola",
            "nothing", "nubia", "redmagic", "lenovo", "xperia", "asus", "rog",
        )
        return androidKeywords.any { lower.contains(it) }
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        val iap2Uuid = UUID.fromString(IAP2_IPHONE_UUID)
        fun isAccessoryOrAndroidPhone(device: BluetoothDevice): Boolean {
            val cachedUuids = runCatching { device.uuids }.getOrNull()
            val hasIap2 = cachedUuids?.any { it.uuid == iap2Uuid } == true
            if (hasIap2) return false
            val name = runCatching { device.name }.getOrNull()
            val nameHasIphone = name?.contains("iPhone", ignoreCase = true) == true ||
                name?.contains("iPad", ignoreCase = true) == true
            if (nameHasIphone) return false
            val addr = runCatching { device.address?.uppercase(Locale.US) }.getOrNull()
            if (addr != null && addr in nonIphoneBluetoothAddresses) return true
            if (isKnownAndroidPhoneName(name)) return true
            // Any bonded phone that has already exchanged SDP UUIDs and does NOT advertise
            // Apple's iAP2 UUID (00000000-deca-fade-deca-deafdecacafe) is an Android phone:
            // dialing RFCOMM to it would open a Classic Bluetooth ACL link and trigger A2DP Sink!
            if (!cachedUuids.isNullOrEmpty()) return true
            val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            return isNonPhoneBluetoothAccessory(name, major)
        }

        val candidateDevices = bonded.filterNot(::isAccessoryOrAndroidPhone)
        val iPhones = candidateDevices.filter { device ->
            val nameMatches = device.name?.contains("iPhone", ignoreCase = true) == true ||
                device.name?.contains("iPad", ignoreCase = true) == true
            val uuidMatches = runCatching {
                device.uuids?.any { it.uuid == iap2Uuid } == true
            }.getOrDefault(false)
            val classMatches = !config.projectionProtocol.enablesCarLink && runCatching {
                device.bluetoothClass?.majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PHONE
            }.getOrDefault(false)
            nameMatches || uuidMatches || classMatches
        }

        config.wirelessBluetoothDeviceAddress?.let { selected ->
            val matched = bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }
            if (matched != null) {
                if (!isAccessoryOrAndroidPhone(matched)) {
                    return matched
                }
                debugLog("wireless ignoring non-iPhone Bluetooth device saved as target address=$selected")
            } else if (iPhones.isEmpty() && candidateDevices.isEmpty()) {
                throw ConnectionFailureException(
                    ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED,
                    "所选的 iPhone 已取消蓝牙配对，请重新在 TVPlay 中选择或配对 iPhone",
                )
            } else {
                debugLog("wireless saved Bluetooth target $selected is no longer bonded; falling back to bonded iPhone discovery")
            }
        }
        val directlyConnectedIPhones = iPhones.filter(::isBluetoothDeviceConnected)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "wireless Bluetooth bondedIPhones=${iPhones.size} " +
                "directlyConnected=${directlyConnectedIPhones.size}",
        )
        val connectedIPhones = if (directlyConnectedIPhones.isNotEmpty()) {
            directlyConnectedIPhones
        } else {
            val connectedAddresses = connectedBluetoothDevices(adapter).mapTo(mutableSetOf()) {
                it.address
            }
            iPhones.filter { it.address in connectedAddresses }
        }
        if (connectedIPhones.size == 1) return connectedIPhones.single()
        if (connectedIPhones.size > 1) {
            throw ConnectionFailureException(
                ConnectionFailureCause.IPHONE_NOT_CHOSEN,
                "发现多台已连接的 iPhone，请在 TVPlay 连接设置中指定目标 iPhone",
            )
        }
        if (iPhones.size == 1) return iPhones.single()
        if (iPhones.size > 1) {
            throw ConnectionFailureException(
                ConnectionFailureCause.IPHONE_NOT_CHOSEN,
                "发现多台已配对的 iPhone，请在 TVPlay 连接设置中指定目标 iPhone",
            )
        }
        if (!config.projectionProtocol.enablesCarLink && candidateDevices.size == 1) {
            return candidateDevices.single()
        }
        throw ConnectionFailureException(
            if (candidateDevices.isEmpty() || config.projectionProtocol.enablesCarLink) {
                ConnectionFailureCause.NO_PAIRED_IPHONE
            } else {
                ConnectionFailureCause.IPHONE_NOT_CHOSEN
            },
            "尚未找到已配对的 iPhone，请先与电视蓝牙配对",
        )
    }

    /** An RFCOMM link that came up too fast to have reached a phone, kept for [unrealBluetoothFailure]. */
    private class InstantBluetoothLink(
        val generation: Int,
        val device: BluetoothDevice,
        val stream: BluetoothRfcommDuplexStream,
        val connectMillis: Long,
    )

    private fun watchForUnrealBluetooth(link: InstantBluetoothLink) {
        Thread({
            try {
                Thread.sleep(BluetoothLinkCheck.SILENCE_MILLIS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (isStaleWirelessRun(link.generation) || isWirelessSessionOrHandoffActive(link.generation)) return@Thread
            val failure = unrealBluetoothFailure(link, "silence") ?: return@Thread
            if (isStaleWirelessRun(link.generation) || isWirelessSessionOrHandoffActive(link.generation)) return@Thread
            if (config.projectionProtocol.enablesCarLink && activeHotspotInfo != null) {
                debugLog("unreal Bluetooth link silenced while CarLink is enabled; keeping Wi-Fi Direct hotspot alive")
                onWirelessBootstrapEndedWithActiveSession(link.generation)
                return@Thread
            }
            fail(failure, link.generation)
            closeWirelessStack(generation = link.generation)
        }, "tvplay-bluetooth-link-check").apply { isDaemon = true; start() }
    }

    private fun unrealBluetoothFailure(link: InstantBluetoothLink, trigger: String): WirelessStartupException? {
        val received = link.stream.bytesReceived
        if (!BluetoothLinkCheck.looksUnreal(link.connectMillis, received)) return null
        val started = System.nanoTime()
        val control = runCatching {
            link.device.createRfcommSocketToServiceRecord(UUID.randomUUID())
                .use { connectBluetoothSocket(it, link.device.address) }
        }
        val controlMillis = elapsedMillis(started)
        val unreal = BluetoothLinkCheck.controlProvesUnreal(control.isSuccess, controlMillis)
        connectionDiagnostic(
            "Bluetooth link check trigger=$trigger connectMs=${link.connectMillis} receivedBytes=$received " +
                "controlConnect=${if (control.isSuccess) "succeeded" else "failed"} " +
                "controlMs=$controlMillis unreal=$unreal",
        )
        if (!unreal) return null
        return WirelessStartupException(
            WirelessStartupFailure.BLUETOOTH_UNUSABLE,
            "Android Bluetooth on this device reports ${BluetoothLinkCheck.UNREAL_MARK}: " +
                "a connection to a service no phone offers also succeeded in ${controlMillis}ms",
        )
    }

    private fun connectBluetoothSocket(socket: BluetoothSocket, address: String) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            debugLog(
                "wireless RFCOMM connect timed out after " +
                    "${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms address=$address",
            )
            runCatching { socket.close() }
            throw IOException(
                "Timed out after ${RFCOMM_CONNECT_TIMEOUT_MILLIS}ms connecting RFCOMM to $address",
            )
        }
        val failure = result.get() ?: return
        if (BluetoothLinkCheck.socketServiceMissing(failure)) {
            connectionDiagnostic("Bluetooth socket manager missing failureClass=${failure.javaClass.simpleName}")
        }
        throw BluetoothLinkCheck.connectFailure(failure, address)
    }

    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                connectionDiagnostic("Bluetooth snapshot point=$point unavailable reason=connect-permission")
                return
            }
            val bondState = device.bondState
            val bondName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            val activeAdapter = currentBluetoothAdapter()
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${activeAdapter?.isEnabled} " +
                    "bondState=$bondState bondName=$bondName cachedServicesReadable=${cachedServices.isSuccess} " +
                    "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: UsbTunnelVpnService? = vpnService, generation: Int? = null) =
        synchronized(wirelessResourceLock) {
            if (generation != null && generation != wirelessGeneration.get()) return@synchronized
            val owner = firstTcpWatchdog?.listener
            firstTcpWatchdog?.terminate()
            val diagnostics = wirelessDiagnostics
            wirelessDiagnostics = null
            diagnostics?.close()
            wirelessConnectionProof.clear()
            media.setIapTunnelHandler(null)
            val activeTunnel = wirelessTunnelChannel
            wirelessTunnelChannel = null
            if (activeTunnel != null) closeBestEffort("tunneled iAP2 link") { activeTunnel.close() }

            closeBluetoothBootstrapTransport()

            if (service != null) {
                closeBestEffort("AirPlay service") {
                    if (owner != null) service.detachWireless(owner) else service.detach()
                }
            }

            val activeBonjour = bonjour
            bonjour = null
            if (activeBonjour != null) closeBestEffort("Bonjour") { activeBonjour.close() }

            val activeHotspot = hotspot
            hotspot = null
            activeHotspotInfo = null
            if (activeHotspot != null) closeBestEffort("wireless hotspot") { activeHotspot.close() }
            releaseWifiPerformanceLock()
            wirelessRuntimeIdentification = null
            wirelessAirPlayEndpoint = null
            wirelessPublishedAddress = null
            wirelessAlternateAddress = null
            wirelessAddressRecovery = null
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)
        }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (_: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
            for (profile in intArrayOf(PROFILE_A2DP_SINK, PROFILE_HEADSET_CLIENT)) {
                val connected = try {
                    adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read Bluetooth profile $profile state", error)
                    false
                }
                if (connected) addAll(connectedBluetoothDevices(adapter, profile, BluetoothProfile::class.java))
            }
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(IphoneCarPlayConfiguration.TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter): String {
        val address = try {
            adapter.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(appContext.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(address, settingsAddress)
            .firstOrNull {
                BLUETOOTH_ADDRESS.matches(it) &&
                    !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
            }
            ?: airPlayConfig.btMac
    }

    private fun flipWirelessAddressFamily(generation: Int) = synchronized(wirelessResourceLock) {
        if (isStaleWirelessRun(generation)) return@synchronized
        val published = wirelessPublishedAddress
        val next = published?.let { wirelessAddressRecovery?.nextFamily(it) }
        val from = published?.let {
            if (it is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
        }
        if (isStaleWirelessRun(generation)) return@synchronized
        if (next == null || from == null) {
            debugLog(
                "wireless address family flip skipped published=${from ?: "none"} " +
                    "alternate=${next != null}",
            )
            return@synchronized
        }
        appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOTSPOT_FAMILY_PREFERENCE_KEY, next)
            .apply()
        debugLog("wireless address family flip published=$from next=$next")
    }

    private fun hotspotCandidates(info: WirelessHotspotInfo, fallbackToSnapshot: Boolean = true): List<InetAddress> {
        val name = info.interfaceName ?: return info.hostAddresses
        val scanned = try {
            NetworkInterface.getByName(name)?.inetAddresses?.toList().orEmpty()
        } catch (error: Exception) {
            debugLog("wireless interface scan failed iface=$name error=${error.javaClass.simpleName}")
            emptyList()
        }
        return if (fallbackToSnapshot) {
            (scanned + info.hostAddresses).distinctBy { it.address.toList() }
        } else {
            scanned
        }
    }

    private fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Wireless host address is unavailable")
        }
        return text
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }

    private fun controlLoopTimeoutMillis(): Long = when {
        config.transport == TvTransportMode.WIRED -> Iap2WiredControlClient.NO_TIMEOUT_MILLIS
        else -> CONTROL_LOOP_TIMEOUT_MILLIS
    }

    private fun attachVpn(ncm: NcmUsbBridge, hostMac: ByteArray): Boolean {
        onStatus(CarPlayStatus.AttachingNetwork)
        val service = awaitVpnService() ?: run {
            debugLog("wired VPN service bind failed")
            ncm.close()
            return false
        }
        debugLog("wired VPN service bound; attaching NCM transport")
        val result = try {
            service.attach(
                ncm = ncm,
                linkLocal = config.linkLocal,
                hostMac = hostMac,
                config = airPlayConfig,
                identity = identity,
                pairings = pairings,
                mfi = mfiProvider,
                listener = sessionListener,
                media = media,
            )
        } catch (error: Throwable) {
            ncm.close()
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName))
            return false
        }
        return when (result) {
            UsbTunnelVpnService.AttachResult.Started -> {
                debugLog("wired VPN/NCM transport attach result=started")
                true
            }
            UsbTunnelVpnService.AttachResult.AlreadyStarted -> {
                debugLog("wired VPN/NCM transport attach result=already-started")
                ncm.close()
                false
            }
            is UsbTunnelVpnService.AttachResult.Failed -> {
                debugLog("wired VPN/NCM transport attach result=failed ${result.message} detail=${service.lastAttachFailure}")
                ncm.close()
                onStatus(CarPlayStatus.Failed(result.message))
                false
            }
        }
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun awaitVpnService(): UsbTunnelVpnService? {
        if (closed) return null
        vpnService?.let { return it }
        if (config.transport == TvTransportMode.WIRELESS) {
            return synchronized(this) {
                vpnService ?: UsbTunnelVpnService().also { vpnService = it }
            }
        }
        bindVpn()
        return try {
            if (vpnLatch.await(VPN_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) vpnService else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindVpn() {
        if (vpnBound) return
        vpnBound = true
        try {
            val intent = Intent(appContext, UsbTunnelVpnService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                vpnBound = false
                vpnLatch.countDown()
            }
        } catch (_: Throwable) {
            vpnBound = false
            vpnLatch.countDown()
        }
    }

    private fun unbindVpn() {
        if (vpnBound) {
            vpnBound = false
            try {
                appContext.unbindService(serviceConnection)
            } catch (_: Exception) {
                // The service may have already been unbound.
            }
        }
        vpnService = null
    }

    private fun closeReceivers() {
        listOfNotNull(permissionCloseable, attachCloseable).forEach {
            try {
                it.close()
            } catch (_: Exception) {
                // Receiver is already unregistered.
            }
        }
        permissionCloseable = null
        attachCloseable = null
    }

    private fun requireUsbManager(): UsbManager =
        usbManager ?: throw IOException("USB service is unavailable on this device")

    private fun hasRequiredUsbService(): Boolean {
        if (usbManager != null || config.transport != TvTransportMode.WIRED) return true
        fail(IOException("USB service is unavailable on this device"))
        return false
    }

    private fun fail(error: Throwable, generation: Int? = null) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            val wireless = config.transport == TvTransportMode.WIRELESS
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) return
                firstTcpWatchdog?.terminate()
            }
            val causes = generateSequence(error) { it.cause }.toList()
            onStatus(
                CarPlayStatus.Failed(
                    error.message ?: error.javaClass.simpleName,
                    causes.any { it is P2pResetRequiredException },
                    causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason,
                    causes.firstNotNullOfOrNull {
                        (it as? ConnectionFailureException)?.reason ?: (it as? WirelessStartupException)?.knownCause
                    },
                ),
                if (wireless) wirelessGeneration.get() else null,
            )
        }
    }

    private fun debugLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            val diagnosticPhase = if (phase == Phase.IPHONE) "USB_DISCOVERY" else phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (_: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(FAILURE_CLASS_SANITIZE_REGEX, "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        Log.w(IphoneCarPlayConfiguration.TAG, message, error)
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            Log.w(IphoneCarPlayConfiguration.TAG, "debug log callback failed", callbackError)
        }
    }

    private fun describeThrowable(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current.message?.let { builder.append(": ").append(it) }
            current.stackTrace.firstOrNull()?.let { frame ->
                builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return builder.toString()
    }

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        mainHandler.post {
            if (!closed && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting MFi access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap preserved"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        is CarPlayStatus.IphoneNotListed ->
            "STEP usb/wait: wired iPhone not listed after ${IPHONE_NOT_LISTED_AFTER_MILLIS / 1_000} s; " +
                "other USB devices=$nonHubDeviceListed"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        private const val TRACE_LOG_TAG = "DiPlayTrace"
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val IAP2_ACCESSORY_UUID = "00000000-deca-fade-deca-deafdecacaff"
        private const val PAIRING_WAIT_TIMEOUT_MILLIS = 60_000L
        private const val RFCOMM_RETRY_DELAY_MILLIS = 3_000L
        private const val MAX_RFCOMM_ATTEMPTS = 5
        private const val PROFILE_A2DP_SINK = 11
        private const val PROFILE_HEADSET_CLIENT = 16
        private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
        private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
        private const val PAIR_TIMEOUT_MILLIS = 5 * 60_000L
        private const val VPN_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val WIRELESS_PREFS = "diplay_wireless"
        private const val HOTSPOT_FAMILY_PREFERENCE_KEY = "hotspot_address_family"
        private const val WIRELESS_DEVICE_ID_PREFERENCE_KEY = "wireless_device_identifier"
        private const val CONTROL_LOOP_TIMEOUT_MILLIS = 5 * 60_000L
        private const val PERMISSION_POLL_INTERVAL_MILLIS = 500L
        private const val PERMISSION_POLL_TIMEOUT_MILLIS = 120_000L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        private const val WIRELESS_HANDOFF_TIMEOUT_MILLIS = 45_000L
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 8_000L
        private const val MAXIMUM_REENUMERATION_ATTEMPTS = 2
        const val REENUMERATION_WAIT_MILLIS = 10_000L
        private const val IPHONE_NOT_LISTED_AFTER_MILLIS = 20_000L
        private const val START_SESSION_PROGRESS = "iap2 tx=0x4301"
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        private val FAILURE_CLASS_SANITIZE_REGEX = Regex("[^A-Za-z0-9_$]")
        private val SYSLOG_RELEVANT_REGEX = Regex(" (accessoryd|ACCCarPlayService|iap2d|CarPlay)([\\[(])", RegexOption.IGNORE_CASE)
    }
}

/**
 * Classifies whether a bonded Bluetooth device is an Android TV remote control, speaker, keyboard,
 * gamepad, or other non-phone peripheral so TVPlay never mistakes the TV's own remote for an iPhone.
 */
fun isNonPhoneBluetoothAccessory(name: String?, majorDeviceClass: Int?): Boolean {
    if (name?.contains("iPhone", ignoreCase = true) == true) return false
    if (
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL ||
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO ||
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.WEARABLE ||
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.IMAGING ||
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.TOY ||
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.HEALTH ||
        majorDeviceClass == android.bluetooth.BluetoothClass.Device.Major.COMPUTER
    ) {
        return true
    }
    val lower = name?.lowercase().orEmpty()
    val accessoryKeywords = listOf(
        "remote",
        "遥控",
        "controller",
        "gamepad",
        "joystick",
        "手柄",
        "keyboard",
        "键盘",
        "mouse",
        "鼠标",
        "airmouse",
        "speaker",
        "音箱",
        "耳机",
        "airpods",
        "buds",
        "headphone",
        "headset",
        "soundbar",
    )
    return accessoryKeywords.any { lower.contains(it) }
}
