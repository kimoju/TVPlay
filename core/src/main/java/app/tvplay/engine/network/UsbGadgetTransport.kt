package app.tvplay.engine.network

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import app.tvplay.engine.auth.DuplexByteStream
import app.tvplay.engine.session.UsbDeviceFilter
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

typealias UsbDeviceId = UsbDeviceFilter
typealias UsbGadgetTransport = IphoneUsbHost

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [openIap2UsbSessionAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(
            val device: UsbDevice,
            val afterMissingUiAdbFailure: Boolean = false,
        ) : PermissionRequest()
        data class AdbRequired(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    /**
     * Every USB device Android lists, as `vid:pid node`, sorted. When no iPhone is
     * listed, this shows whether Android sees anything on the head unit's port at all.
     */
    fun listedDevices(): List<String> =
        usbManager.deviceList.values.map(IphoneUsbHost::describe).sorted()

    fun listsNonHubDevice(): Boolean = usbManager.deviceList.values.any { device ->
        !isHub(device.deviceClass, (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass })
    }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice, allowAdbFallback: Boolean = true): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        val ui = UsbPermissionActivityProbe.inspect(appContext)
        if (ui.state != UsbPermissionActivityProbe.State.UNKNOWN) {
            runCatching { onDiagnostic(ui.diagnostic()) }
        }
        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(
            device,
            afterMissingUiAdbFailure = ui.state == UsbPermissionActivityProbe.State.UNAVAILABLE,
        )
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction), guarded = true) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                val transferred = connection.controlTransfer(
                    USB_VENDOR_DEVICE_IN,
                    CARPLAY_CONFIGURATION_REQUEST,
                    0,
                    CARPLAY_CONFIGURATION_INDEX,
                    response,
                    response.size,
                    CONTROL_TRANSFER_TIMEOUT_MILLIS,
                )
                if (transferred != response.size) {
                    // iOS 12 and 15 rejected this request even while unlocked (#509, #455); a locked
                    // iPhone rejects it too (#100). Record what the iPhone exposes to tell them apart.
                    runCatching { onDiagnostic(modeRejectionDiagnostic(connection, device)) }
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $transferred of ${response.size} bytes",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        var connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            connection = selectConfiguration(connection, device, configuration)
            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "usbmux config=${configuration.id} iface=${usbMux.id} alt=${usbMux.alternateSetting} " +
                    "class=${usbMux.interfaceClass}/${usbMux.interfaceSubclass}/${usbMux.interfaceProtocol} " +
                    "endpoints=${usbMux.endpointCount} " +
                    "out=${describeUsbEndpoint(endpoints.first)} " +
                    "in=${describeUsbEndpoint(endpoints.second)}",
            )
            if (!connection.claimInterface(usbMux, true)) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim USBMUX interface 1")
            }
            claimedInterface = usbMux
            return Iap2UsbSession(connection, endpoints.first, endpoints.second, onDiagnostic)
        } catch (error: Throwable) {
            if (claimedInterface != null) connection.releaseInterface(claimedInterface)
            connection.close()
            throw error
        }
    }

    /**
     * Selects [configuration], detaching head-unit drivers that block the switch (#518). Returns
     * the connection to use from now on: the detach replaces [connection] with a new one.
     */
    private fun selectConfiguration(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        configuration: UsbConfiguration,
    ): UsbDeviceConnection {
        val interfaces = (0 until device.configurationCount).map(device::getConfiguration)
            .flatMap { config -> (0 until config.interfaceCount).map(config::getInterface) }
            .associateBy { it.id }
        var current = connection
        try {
            val outcome = UsbConfigurationSelection.select(
                target = configuration.id,
                interfaceIds = interfaces.keys.toList(),
                setConfiguration = { current.setConfiguration(configuration) },
                activeConfiguration = { activeConfiguration(current) },
                // A forced claim detaches the kernel driver. It stays claimed until the reopen.
                detach = { id -> current.claimInterface(interfaces.getValue(id), true) },
                // Closing drops the claims without the driver reattach that releaseInterface() requests.
                reopen = {
                    current.close()
                    current = usbManager.openDevice(device)
                        ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not reopen the iPhone")
                },
            ) ?: return current
            runCatching { onDiagnostic(outcome.diagnostic()) }
            if (outcome.wrongConfiguration) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not switch the iPhone to USB configuration ${configuration.id} " +
                        "(still ${outcome.after}); a head-unit driver may hold it",
                )
            }
            if (!outcome.selected) {
                Log.w(IphoneCarPlayConfiguration.TAG, "setConfiguration ${configuration.id} reported failure; claiming anyway")
            }
            return current
        } catch (error: Throwable) {
            // The caller closes the connection it passed in; a replacement is closed here.
            if (current !== connection) current.close()
            throw error
        }
    }

    /** The iPhone's current configuration value (standard GET_CONFIGURATION), or null if unreadable. */
    private fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        val read = connection.controlTransfer(
            USB_STANDARD_DEVICE_IN, GET_CONFIGURATION_REQUEST, 0, 0, value, value.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return if (read == value.size) value[0].toInt() and 0xff else null
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerReceiver(filter: IntentFilter, guarded: Boolean = false, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
        if (guarded) {
            UsbPermissionBroadcast.register(appContext, receiver, filter)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    /** The iPhone's current USB mode (usbmuxd's 0x45 request) and every configuration it exposes. */
    private fun modeRejectionDiagnostic(connection: UsbDeviceConnection, device: UsbDevice): String {
        val mode = ByteArray(USB_MODE_RESPONSE_LENGTH)
        val read = connection.controlTransfer(
            USB_VENDOR_DEVICE_IN, GET_USB_MODE_REQUEST, 0, 0, mode, mode.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return usbModeDiagnostic(
            read,
            mode,
            device.version,
            (0 until device.configurationCount).map(device::getConfiguration).map { "${it.id}[${IphoneCarPlayConfiguration.describe(it)}]" },
        )
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val USB_STANDARD_DEVICE_IN = 0x80
        private const val GET_CONFIGURATION_REQUEST = 0x08
        private const val GET_USB_MODE_REQUEST = 0x45
        private const val USB_MODE_RESPONSE_LENGTH = 4
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000

        fun describe(device: UsbDevice): String =
            "%04x:%04x %s".format(device.vendorId, device.productId, device.deviceName)

        /** usbmuxd reads 3:3:3:0 in the initial mode and 5:3:3:0 once a mode is set. */
        /** iOS 12 and 15 answer the mode request with 3 bytes instead of 4 (#509, #455); keep them. */
        internal fun usbModeDiagnostic(read: Int, mode: ByteArray, deviceVersion: String?, configurations: List<String>): String {
            val bytes = mode.copyOf(read.coerceIn(0, mode.size)).joinToString(":") { (it.toInt() and 0xff).toString() }
            val current = when (read) {
                mode.size -> bytes
                in 1 until mode.size -> "$bytes (short:$read)"
                else -> "unreadable($read)"
            }
            return "CarPlay USB mode request rejected; current mode=$current " +
                "usbVersion=${deviceVersion ?: "unknown"} configurations=$configurations"
        }

        internal fun isHub(deviceClass: Int, interfaceClasses: List<Int>): Boolean =
            deviceClass == UsbConstants.USB_CLASS_HUB ||
                interfaceClasses.isNotEmpty() && interfaceClasses.all { it == UsbConstants.USB_CLASS_HUB }
    }
}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var pendingRead: UsbRequest? = null
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val requests = UsbRequestQueue(connection, "usbmux-read-reaper")

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = connection.bulkTransfer(outEndpoint, data, data.size, timeoutMillis)
        if (transferred != data.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val request = UsbRequest()
        var initialized = false
        try {
            if (!request.initialize(connection, inEndpoint)) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                )
            }
            initialized = true
            val buffer = ByteBuffer.allocateDirect(USBMUX_READ_CHUNK_BYTES)
            val queueResult = synchronized(stateLock) {
                checkOpenLocked()
                pendingRead = request
                readQueuePolicy.queue(buffer, ::checkOpenLocked) { requests.queue(request, it) }
            }
            if (!queueResult.queued) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis)} " +
                        "firstBytes=${queueResult.firstBytes} fallbackBytes=${queueResult.fallbackBytes ?: "not_attempted"})",
                )
            }
            // The policy remembers an accepted fallback, so this event occurs once per pipe.
            if (queueResult.fallbackBytes != null) runCatching {
                onDiagnostic(
                    "USBMUX read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queueResult.firstBytes} " +
                        "fallbackBytes=${queueResult.fallbackBytes}",
                )
            }
            val completed = try {
                requests.await(timeoutMillis)
            } catch (_: TimeoutException) {
                return@synchronized drainTimedOutRead(request, buffer)
            }
            if (completed == null) {
                throw failSession("Android returned no USBMUX read request")
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            return@synchronized ByteArray(buffer.position()).also {
                buffer.flip()
                buffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        } finally {
            synchronized(stateLock) {
                if (pendingRead === request) pendingRead = null
            }
            if (initialized) request.cancel()
            request.close()
        }
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingRead
        }
        requestToCancel?.cancel()
        requests.close()
        connection.close()
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    /**
     * Cancels a read that timed out and takes it back from Android. A read that completes between
     * the timeout and the cancel can no longer be cancelled: it is already on its way back. That
     * race ended the whole session every few minutes on a Telechips and a DiLink 5.1 head unit
     * (#610, #301). The read is drained either way, and any bytes it carries are returned so the
     * USBMUX stream stays intact.
     */
    private fun drainTimedOutRead(request: UsbRequest, buffer: ByteBuffer): ByteArray? {
        val cancelled = request.cancel()
        val completed = try {
            requests.await(CANCEL_DRAIN_TIMEOUT_MILLIS)
        } catch (_: TimeoutException) {
            throw failSession(
                if (cancelled) "Timed out draining cancelled USBMUX read request"
                else "Android could not cancel timed out USBMUX read request",
            )
        }
        if (completed !== request) {
            throw failSession("Android did not drain the cancelled USBMUX read request")
        }
        if (buffer.position() == 0) return null
        return ByteArray(buffer.position()).also {
            buffer.flip()
            buffer.get(it)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
        const val CANCEL_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}

internal fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}

/**
 * USBMUX version-2 host over an already claimed iPhone bulk pipe.
 *
 * This is deliberately limited to USBMUX framing and one minimal outbound TCP connection. It
 * does not parse lockdown messages, iAP2, TLS, NCM, AirPlay, or any media protocol. All calls
 * may block and must run away from Android's main thread.
 */
class Iap2UsbMuxHost private constructor(
    private val pipe: Iap2UsbSession,
    private val readTimeoutMillis: Long,
    private val onDiagnostic: (String) -> Unit,
) : Closeable {
    private val stateLock = Any()
    private val writeLock = Any()
    private val connections = mutableMapOf<Int, Iap2UsbMuxTcpConnection>()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var nextMuxSequence = 0
    private var nextMuxAcknowledgement = 0
    private var receivedFrames = 0
    private val loggedControlFrames = AtomicInteger()
    private val loggedLargeWrites = AtomicInteger()
    private var nextSourcePort = FIRST_SOURCE_PORT
    private lateinit var readerThread: Thread
    private val receiveFrames = UsbMuxFrameBuffer { line ->
        Log.w("xcertplay-usb", line)
        runCatching { onDiagnostic(line) }
    }

    /** Opens a TCP byte stream to the iPhone service on [destinationPort]. */
    fun connect(
        destinationPort: Int = LOCKDOWN_PORT,
        timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    ): Iap2UsbMuxTcpConnection {
        require(destinationPort in 1..0xffff) { "destinationPort must be a valid TCP port" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val connection = synchronized(stateLock) {
            checkOpenLocked()
            val sourcePort = allocateSourcePortLocked()
            Iap2UsbMuxTcpConnection(this, sourcePort, destinationPort).also {
                connections[sourcePort] = it
            }
        }
        try {
            connection.beginConnect()
            if (!connection.awaitConnected(timeoutMillis)) {
                connection.abort()
                throw IphoneUsbException.TimedOut("USBMUX TCP connection to port $destinationPort timed out")
            }
            return connection
        } catch (error: IphoneUsbException) {
            removeConnection(connection)
            throw error
        }
    }

    override fun close() {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach(Iap2UsbMuxTcpConnection::closeFromHost)
        pipe.close()
        if (::readerThread.isInitialized && Thread.currentThread() !== readerThread) {
            try {
                readerThread.join(readTimeoutMillis + CLOSE_JOIN_MARGIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    internal fun sendTcp(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgement: Int,
        flags: Int,
        payload: ByteArray,
    ) {
        if (payload.size > 512 && loggedLargeWrites.incrementAndGet() <= LOGGED_RX_FRAMES) {
            Log.i("xcertplay-usb", "usbmux TX source=$sourcePort destination=$destinationPort bytes=${payload.size} seq=$sequence ack=$acknowledgement")
        }
        val tcp = ByteArray(TCP_HEADER_BYTES + payload.size)
        putU16(tcp, 0, sourcePort)
        putU16(tcp, 2, destinationPort)
        putU32(tcp, 4, sequence)
        putU32(tcp, 8, acknowledgement)
        tcp[12] = (TCP_HEADER_BYTES / 4 shl 4).toByte()
        tcp[13] = flags.toByte()
        putU16(tcp, 14, TCP_WINDOW_FIELD)
        payload.copyInto(tcp, TCP_HEADER_BYTES)
        sendFrame(PROTOCOL_TCP, tcp)
    }

    internal fun removeConnection(connection: Iap2UsbMuxTcpConnection) {
        synchronized(stateLock) {
            if (connections[connection.sourcePort] === connection) {
                connections.remove(connection.sourcePort)
            }
        }
    }

    private fun begin() {
        val version = ByteArray(VERSION_MESSAGE_BYTES)
        putU32(version, 0, PROTOCOL_VERSION)
        putU32(version, 4, VERSION_MESSAGE_BYTES)
        putU32(version, 8, USBMUX_VERSION)
        pipe.write(version, HANDSHAKE_TIMEOUT_MILLIS.toInt())
        // The phone replies with the same proto=0, length=20, version=2 packet. Protocol 1 is not
        // a distinct "version reply" here; waiting for it discards the valid reply and times out.
        val deadline = System.nanoTime() + HANDSHAKE_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        var staleFrames = 0
        var reply: UsbMuxFrame
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            }
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            reply = takeFrame(remainingMillis)
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            if (
                reply.protocol == PROTOCOL_VERSION &&
                reply.length == VERSION_MESSAGE_BYTES &&
                reply.word8 == USBMUX_VERSION
            ) {
                break
            }
            // Android can leave already-received TCP payloads queued in the bulk endpoint when
            // an app process is replaced. They belong to the previous host instance and must not
            // be mistaken for the version reply sent in response to the new handshake.
            if (reply.protocol != PROTOCOL_TCP || ++staleFrames > MAX_STALE_HANDSHAKE_FRAMES) {
                throw IphoneUsbException.Protocol(
                    "Invalid USBMUX version reply: proto=${reply.protocol} " +
                        "length=${reply.length} version=${reply.word8}",
                )
            }
            Log.i("xcertplay-usb", "discarding stale usbmux TCP frame before version reply")
        }
        Log.i("xcertplay-usb", "usbmux version accepted: ${reply.word8}")
        // Optional reply padding is handled by the incremental framer. Retain a possible
        // fragmented/coalesced next frame instead of discarding the entire remainder.
        sendFrame(PROTOCOL_SETUP, byteArrayOf(SETUP_VALUE.toByte()))
        readerThread = Thread(::readerLoop, "iap2-usbmux-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Reads one complete USBMUX frame, keeping partial data buffered across reads. */
    private fun takeFrame(timeoutMillis: Long): UsbMuxFrame? {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            synchronized(stateLock) {
                if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
                receiveFrames.takeFrame()?.let { frame ->
                    // LIVI only trusts the length field on receive: iPhone replies do not
                    // carry the 0xFEEDFACE word in the header's fourth field.
                    // One line per frame filled logcat within a minute of a wired session (#634).
                    if (++receivedFrames <= LOGGED_RX_FRAMES) Log.i(
                        "xcertplay-usb",
                        "usbmux rx proto=${frame.protocol} length=${frame.length} word8=0x" +
                            frame.word8.toUInt().toString(16),
                    )
                    nextMuxAcknowledgement = frame.sequence
                    return frame
                }
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            val bytes = pipe.read(remainingMillis) ?: continue
            synchronized(stateLock) {
                receiveFrames.append(bytes)
            }
        }
    }

    private fun sendFrame(protocol: Int, payload: ByteArray) = synchronized(writeLock) {
        val sequenceAndAcknowledgement = synchronized(stateLock) {
            checkOpenLocked()
            nextMuxSequence to nextMuxAcknowledgement
        }
        val frame = ByteArray(MUX_HEADER_BYTES + payload.size)
        putU32(frame, 0, protocol)
        putU32(frame, 4, frame.size)
        putU32(frame, 8, MUX_MAGIC)
        putU16(frame, 12, sequenceAndAcknowledgement.first)
        putU16(frame, 14, sequenceAndAcknowledgement.second)
        payload.copyInto(frame, MUX_HEADER_BYTES)
        try {
            pipe.write(frame, WRITE_TIMEOUT_MILLIS)
        } catch (error: IphoneUsbException) {
            fail(error)
            throw error
        }
        synchronized(stateLock) {
            nextMuxSequence = (nextMuxSequence + 1) and 0xffff
        }
    }

    private fun readerLoop() {
        try {
            while (true) {
                synchronized(stateLock) {
                    if (closed) return
                }
                val frame = takeFrame(readTimeoutMillis) ?: continue
                if (frame.protocol == PROTOCOL_TCP) dispatchTcp(frame.payload)
            }
        } catch (error: IphoneUsbException) {
            fail(error)
        } catch (error: RuntimeException) {
            fail(IphoneUsbException.DeviceUnavailable("USBMUX reader failed", error))
        }
    }

    private fun dispatchTcp(frame: ByteArray) {
        val offset = 0
        val length = frame.size
        if (length < TCP_HEADER_BYTES) {
            throw IphoneUsbException.Protocol("USBMUX TCP frame is shorter than its header")
        }
        val tcpHeaderBytes = ((frame[offset + 12].toInt() ushr 4) and 0x0f) * 4
        if (tcpHeaderBytes < TCP_HEADER_BYTES || tcpHeaderBytes > length) {
            throw IphoneUsbException.Protocol("Invalid USBMUX TCP header length")
        }
        val destinationPort = readU16(frame, offset + 2)
        if (length == tcpHeaderBytes && (frame[13].toInt() and TCP_SYN_FIN_RST != 0 || loggedControlFrames.incrementAndGet() <= LOGGED_RX_FRAMES)) {
            Log.i("xcertplay-usb", "usbmux TCP control destination=$destinationPort flags=${frame[13].toInt() and 0xff} ack=${readU32(frame, 8)} window=${readU16(frame, 14)}")
        }
        val connection = synchronized(stateLock) { connections[destinationPort] } ?: return
        connection.onPacket(
            flags = frame[offset + 13].toInt() and 0xff,
            sequence = readU32(frame, offset + 4),
            payload = frame.copyOfRange(offset + tcpHeaderBytes, offset + length),
        )
    }

    private fun fail(error: IphoneUsbException) {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            failure = failure ?: error
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach { it.closeFromHost(error) }
        pipe.close()
    }

    private fun allocateSourcePortLocked(): Int {
        repeat(0xffff) {
            val candidate = nextSourcePort
            nextSourcePort = if (candidate == 0xffff) FIRST_SOURCE_PORT else candidate + 1
            if (candidate !in connections) return candidate
        }
        throw IphoneUsbException.DeviceUnavailable("USBMUX has no free TCP source ports")
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
    }

    companion object {
        const val LOCKDOWN_PORT = 62078
        private const val LOGGED_RX_FRAMES = 50

        private const val PROTOCOL_VERSION = 0
        private const val PROTOCOL_SETUP = 2
        private const val PROTOCOL_TCP = 6
        private const val USBMUX_VERSION = 2
        private const val SETUP_VALUE = 0x07
        private const val MUX_MAGIC = 0xfeedface.toInt()
        private const val VERSION_MESSAGE_BYTES = 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MUX_HEADER_BYTES = 16
        private const val TCP_HEADER_BYTES = 20
        private const val TCP_SYN_FIN_RST = 0x07
        private const val TCP_WINDOW_FIELD = 512
        private const val FIRST_SOURCE_PORT = 1
        private const val HANDSHAKE_TIMEOUT_MILLIS = 60_000L
        private const val MAX_STALE_HANDSHAKE_FRAMES = 32
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val WRITE_TIMEOUT_MILLIS = 2_000
        private const val CLOSE_JOIN_MARGIN_MILLIS = 100L

        /** Performs the USBMUX v2 handshake and starts the framed reader. */
        fun open(
            pipe: Iap2UsbSession,
            readTimeoutMillis: Long = 1_000,
            onDiagnostic: (String) -> Unit = {},
        ): Iap2UsbMuxHost {
            require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
            return Iap2UsbMuxHost(pipe, readTimeoutMillis, onDiagnostic).also {
                try {
                    it.begin()
                } catch (error: Throwable) {
                    it.close()
                    throw error
                }
            }
        }

        private fun putU16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 8).toByte()
            target[offset + 1] = value.toByte()
        }

        private fun putU32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        private fun readU16(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

        private fun readU32(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 24) or
                ((source[offset + 1].toInt() and 0xff) shl 16) or
                ((source[offset + 2].toInt() and 0xff) shl 8) or
                (source[offset + 3].toInt() and 0xff)
    }
}

/** A blocking TCP byte stream carried by [Iap2UsbMuxHost]. */
class Iap2UsbMuxTcpConnection internal constructor(
    private val host: Iap2UsbMuxHost,
    internal val sourcePort: Int,
    private val destinationPort: Int,
) : DuplexByteStream {
    private val stateLock = Object()
    private val writeLock = Any()
    private val received = ArrayDeque<ByteArray>()
    private var nextSequence = 0
    private var nextAcknowledgement = 0
    private var connected = false
    private var closed = false
    private var failure: IphoneUsbException? = null

    /** Sends [data] as an ordered byte stream, split into USBMUX TCP payloads of at most 16 KiB. */
    override fun send(data: ByteArray) {
        synchronized(stateLock) { checkConnectedLocked() }
        var offset = 0
        while (offset < data.size) {
            val count = minOf(MAX_SEND_PAYLOAD_BYTES, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + count)
            synchronized(writeLock) {
                val sequenceAndAck = synchronized(stateLock) {
                    checkConnectedLocked()
                    nextSequence to nextAcknowledgement
                }
                host.sendTcp(
                    sourcePort,
                    destinationPort,
                    sequenceAndAck.first,
                    sequenceAndAck.second,
                    TCP_ACK,
                    chunk,
                )
                synchronized(stateLock) { nextSequence += count }
            }
            offset += count
        }
    }

    /**
     * Receives up to [maxBytes] from the byte stream. Returns null on timeout and an empty array
     * after FIN or local close; a peer RST is reported as a transport failure.
     */
    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (received.isEmpty() && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    stateLock.wait(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while waiting for USBMUX TCP data")
                }
            }
            failure?.let { throw it }
            if (received.isEmpty()) {
                return ByteArray(0)
            }
            val packet = received.removeFirst()
            return if (packet.size <= maxBytes) {
                packet
            } else {
                received.addFirst(packet.copyOfRange(maxBytes, packet.size))
                packet.copyOf(maxBytes)
            }
        }
    }

    override fun close() {
        val shouldSendFin = synchronized(stateLock) {
            if (closed) return
            closed = true
            stateLock.notifyAll()
            connected
        }
        if (shouldSendFin) {
            try {
                synchronized(writeLock) {
                    val sequenceAndAck = synchronized(stateLock) { nextSequence to nextAcknowledgement }
                    host.sendTcp(
                        sourcePort,
                        destinationPort,
                        sequenceAndAck.first,
                        sequenceAndAck.second,
                        TCP_FIN or TCP_ACK,
                        ByteArray(0),
                    )
                }
            } catch (_: IphoneUsbException) {
                // The host may have already closed its USB pipe; the closed state remains final.
            }
        }
        host.removeConnection(this)
    }

    internal fun beginConnect() {
        sendControl(TCP_SYN)
    }

    internal fun awaitConnected(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (!connected && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                try {
                    stateLock.wait(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while connecting USBMUX TCP")
                }
            }
            failure?.let { throw it }
            return connected && !closed
        }
    }

    internal fun abort() {
        val shouldSendReset = synchronized(stateLock) {
            if (closed) return
            closed = true
            stateLock.notifyAll()
            true
        }
        if (shouldSendReset) {
            try {
                sendControl(TCP_RST or TCP_ACK)
            } catch (_: IphoneUsbException) {
                // A failed reset cannot make the local connection usable again.
            }
        }
        host.removeConnection(this)
    }

    internal fun closeFromHost(error: IphoneUsbException? = null) = synchronized(stateLock) {
        if (closed) return@synchronized
        failure = error ?: failure
        closed = true
        stateLock.notifyAll()
    }

    internal fun onPacket(flags: Int, sequence: Int, payload: ByteArray) {
        if ((flags and TCP_RST) != 0) {
            synchronized(stateLock) {
                failure = IphoneUsbException.DeviceUnavailable("USBMUX TCP connection was reset by the peer")
                closed = true
                stateLock.notifyAll()
            }
            host.removeConnection(this)
            return
        }
        if ((flags and TCP_SYN) != 0 && (flags and TCP_ACK) != 0) {
            synchronized(stateLock) {
                if (closed) return
                nextSequence += 1
                nextAcknowledgement = sequence + 1
            }
            sendControl(TCP_ACK)
            synchronized(stateLock) {
                if (!closed) {
                    connected = true
                    stateLock.notifyAll()
                }
            }
            return
        }
        if (payload.isNotEmpty()) {
            synchronized(stateLock) {
                if (closed) return
                nextAcknowledgement += payload.size
                received.addLast(payload)
                stateLock.notifyAll()
            }
            sendControl(TCP_ACK)
        }
        if ((flags and TCP_FIN) != 0) {
            synchronized(stateLock) {
                if (closed) return
                nextAcknowledgement += 1
            }
            sendControl(TCP_ACK)
            synchronized(stateLock) {
                closed = true
                stateLock.notifyAll()
            }
            host.removeConnection(this)
        }
    }

    private fun sendControl(flags: Int) = synchronized(writeLock) {
        val sequenceAndAck = synchronized(stateLock) { nextSequence to nextAcknowledgement }
        host.sendTcp(
            sourcePort,
            destinationPort,
            sequenceAndAck.first,
            sequenceAndAck.second,
            flags,
            ByteArray(0),
        )
    }

    private fun checkConnectedLocked() {
        failure?.let { throw it }
        if (!connected || closed) throw IphoneUsbException.DeviceUnavailable("USBMUX TCP connection is not open")
    }

    private companion object {
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_ACK = 0x10
        const val MAX_SEND_PAYLOAD_BYTES = 16 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var bulkOutAccepted = false
    private var notReadyFrames = 0

    /** [System.nanoTime] when the iPhone first took a frame on bulk OUT, or null while it still NAKs the pipe. */
    @Volatile var linkOpenedAtNanos: Long? = null
        private set
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    // A full-speed link uses 64-byte packets; the short-packet pad follows the real packet size.
    private val usbPacketSize = inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var resyncSkipped = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val readRequests = UsbRequestQueue(connection, "ncm-read-reaper")
    private val traffic = NcmTrafficCounter()
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        // bulkTransfer() pins `block` in a JNI critical section until it returns (see the read
        // path above). While the phone still NAKs this pipe, every frame waited out the full
        // timeout, and each USBMUX write of the iAP2 handshake finished only when one expired:
        // ~2 s per step on Android 8.1–14 (#634, #335, #301). Until the phone takes its first
        // frame, a frame that cannot go out at once is dropped, as it was after the full timeout.
        val transferred = connection.bulkTransfer(
            outEndpoint,
            block,
            block.size,
            if (bulkOutAccepted) timeoutMillis else minOf(timeoutMillis, NOT_READY_WRITE_TIMEOUT_MILLIS),
        )
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!bulkOutAccepted) notReadyFrames++
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
            return@synchronized
        }
        if (!bulkOutAccepted) {
            bulkOutAccepted = true
            linkOpenedAtNanos = System.nanoTime()
            runCatching { onDiagnostic("NCM bulk-out ready after $notReadyFrames dropped frames") }
        }
        if (transferred != block.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of ${block.size} bytes",
            )
        }
        traffic.sent()
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /** Frames to and from the iPhone so far, for a report line; see [NcmTrafficCounter]. */
    fun trafficSummary(airPlayPort: Int?): String = traffic.summary(airPlayPort)

    /** Whether the iPhone tried to connect to [port] at or after [sinceNanos]; see [NcmTrafficCounter]. */
    fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean = traffic.connectionAttemptSince(port, sinceNanos)

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        readRequests.close()
        connection.close()
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            // wBlockLength describes the complete NTB. A packet-aligned USB transfer may end
            // with a ZLP (no byte in the buffer), or carry one zero byte to force a short packet.
            // Do not wait for that optional byte or consume the next NTB's header as padding.
            // Keep this state when the pad/header arrives in a later USB completion.
            if (optionalShortPacketPad && bufferedSize > 0) {
                optionalShortPacketPad = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> if (!resynchronize("Invalid NTB16 short-packet pad")) return
                }
            }
            if (bufferedSize < NTH16_LENGTH) return
            val blockLength = readU16(buffered, 8)
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                if (!resynchronize("NCM read buffer does not begin with an NTB16 header")) return
                continue
            }
            if (blockLength < 28) {
                if (!resynchronize("Invalid NTB16 block length $blockLength")) return
                continue
            }
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            discard(blockLength)
            optionalShortPacketPad = blockLength % usbPacketSize == 0
            finishResync()
        }
    }

    /**
     * Android reaps a bulk transfer that failed part way (babble, CRC, a dropped packet) as a
     * normal completion with whatever bytes arrived, so a damaged NTB can leave the stream
     * misaligned. Like Linux cdc_ncm, drop the damaged bytes and continue at the next valid NTB16
     * header; TCP resends what was lost. Returns false while no header is buffered yet. Damage
     * that keeps recurring still fails the session, as before.
     */
    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = System.nanoTime()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.first() > RESYNC_WINDOW_NANOS) resyncTimes.removeFirst()
            if (resyncTimes.size >= MAX_RESYNCS_PER_WINDOW) throw failSession("$reason (repeated)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            resyncSkipped = 0
        }
        val next = (1..bufferedSize - NTH16_LENGTH).firstOrNull(::plausibleHeaderAt)
        // Without a header, keep the bytes that could still be the start of one.
        val skipped = next ?: (bufferedSize - (NTH16_LENGTH - 1)).coerceAtLeast(0)
        discard(skipped)
        resyncSkipped += skipped
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS || resyncCount % 50 == 0) {
            runCatching { onDiagnostic("NCM resynchronized after $reason; skipped=$resyncSkipped resyncs=$resyncCount") }
        }
    }

    /** Stricter than the normal path, so payload bytes are unlikely to pass as a header. */
    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (readU32(buffered, offset) != Ntb16Codec.NTH16_SIG) return false
        val headerLength = readU16(buffered, offset + 4)
        val blockLength = readU16(buffered, offset + 8)
        val ndpIndex = readU16(buffered, offset + 10)
        return headerLength == NTH16_LENGTH && blockLength >= 28 &&
            ndpIndex >= NTH16_LENGTH && ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize -= count
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        traffic.received(frame)
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) { readRequests.queue(current, it) }
                    if (!queued.queued) throw failSession(
                        "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
                            "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                            "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                    )
                    readQueued = true
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        try {
            val completed = try {
                readRequests.await(timeoutMillis.coerceAtLeast(1))
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val NTH16_LENGTH = 12
        private const val MAX_RESYNCS_PER_WINDOW = 16
        private const val RESYNC_WINDOW_NANOS = 10_000_000_000L
        private const val LOGGED_RESYNCS = 5
        internal const val USB_SETUP_ATTEMPTS = 5
        private const val USB_SETUP_RETRY_MILLIS = 100L
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        internal const val NOT_READY_WRITE_TIMEOUT_MILLIS = 20
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * Retries a USB setup call that a vendor kernel can lose to its own cdc_ncm driver, which may
         * rebind between Android's forced detach and the claim (#518). Returns the successful attempt.
         */
        internal fun retryUsbSetup(
            attempts: Int = USB_SETUP_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
            action: () -> Boolean,
        ): Int? {
            for (attempt in 1..attempts) {
                if (action()) return attempt
                if (attempt < attempts) pause(USB_SETUP_RETRY_MILLIS)
            }
            return null
        }

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = retryUsbSetup { connection.claimInterface(first, true) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=${firstClaimed != null}" +
                        " attempts=${firstClaimed ?: USB_SETUP_ATTEMPTS}",
                )
                if (firstClaimed == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = retryUsbSetup { connection.claimInterface(function.data, true) }
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=${dataClaimed != null}" +
                            " attempts=${dataClaimed ?: USB_SETUP_ATTEMPTS}",
                    )
                    if (dataClaimed == null) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                val altSelected = retryUsbSetup { connection.setInterface(function.data) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=${altSelected != null}" +
                        " attempts=${altSelected ?: USB_SETUP_ATTEMPTS}",
                )
                if (altSelected == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    onDiagnostic,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    private var outboundLogBudget = 24
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            start()
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        ncm.close()
        tun.close()
        join(ncmToTunThread)
        join(tunToNcmThread)
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                if (!EthernetIpv6Codec.isIpv6Frame(frame)) continue
                val currentPeer = peerMac
                val nextPeer = if (currentPeer != null && EthernetIpv6Codec.macEquals(frame, 6, currentPeer)) {
                    currentPeer
                } else {
                    frame.copyOfRange(6, 12).also { peerMac = it }
                }
                val payloadOffset = EthernetIpv6Codec.HEADER_BYTES
                val payloadLength = frame.size - payloadOffset
                if (!loggedInbound) {
                    loggedInbound = true
                    Log.i(
                        TAG,
                        "ncm first inbound ipv6 bytes=$payloadLength " +
                            "peer=${nextPeer.macString()}",
                    )
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(payloadOffset)}")
                }
                output.write(frame, payloadOffset, payloadLength)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val isNa = EthernetIpv6Codec.isNeighborAdvertisement(buffer, length)
                val ipv6Source: ByteArray
                val ipv6Length: Int
                if (isNa) {
                    val tunPacket = buffer.copyOf(length)
                    val modified = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                    if (modified.size != tunPacket.size) {
                        Log.i(TAG, "ncm added target-link-layer option to neighbor advertisement")
                    }
                    ipv6Source = modified
                    ipv6Length = modified.size
                } else {
                    ipv6Source = buffer
                    ipv6Length = length
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    Log.i(TAG, "ncm outbound ${ipv6Source.summary(0, ipv6Length)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6Source, ipv6Length)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        Log.i(TAG, "ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    Log.i(
                        TAG,
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6Source, 0, ipv6Length)
                ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int, limit: Int = size): String {
        val payloadBytes = limit - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}

/**
 * Untagged Ethernet II framing around IPv6 payloads, the shape NCM datagrams carry.
 *
 * The NCM data path moves raw Ethernet frames. This codec is the seam the later Android VPN layer
 * uses to exchange IPv6 packets with the phone; it does not create or own a network interface.
 */
object EthernetIpv6Codec {
    const val ETHERTYPE_IPV6 = 0x86dd
    const val MAC_BYTES = 6

    internal const val HEADER_BYTES = 14

    data class Ipv6Frame(
        val sourceMac: ByteArray,
        val destinationMac: ByteArray,
        val ipv6: ByteArray,
    )

    data class Ipv6FrameView(
        val sourceMac: ByteArray,
        val payloadOffset: Int,
        val payloadLength: Int,
    )

    internal fun isIpv6Frame(frame: ByteArray): Boolean =
        frame.size > HEADER_BYTES && readU16(frame, 12) == ETHERTYPE_IPV6

    internal fun macEquals(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        for (i in 0 until MAC_BYTES) {
            if (source[offset + i] != target[i]) return false
        }
        return true
    }

    /** Returns null for tagged frames, non-IPv6 frames, or truncated input. */
    fun parseIpv6(frame: ByteArray): Ipv6Frame? {
        if (frame.size < HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6Frame(
            sourceMac = frame.copyOfRange(6, 12),
            destinationMac = frame.copyOfRange(0, 6),
            ipv6 = frame.copyOfRange(HEADER_BYTES, frame.size),
        )
    }

    /** Returns an allocation-light view for callers that can consume the payload in place. */
    fun parseIpv6View(frame: ByteArray): Ipv6FrameView? {
        if (frame.size <= HEADER_BYTES || readU16(frame, 12) != ETHERTYPE_IPV6) return null
        return Ipv6FrameView(
            sourceMac = frame.copyOfRange(6, 12),
            payloadOffset = HEADER_BYTES,
            payloadLength = frame.size - HEADER_BYTES,
        )
    }

    fun build(sourceMac: ByteArray, destinationMac: ByteArray, ipv6: ByteArray): ByteArray =
        build(sourceMac, destinationMac, ipv6, 0, ipv6.size)

    fun build(
        sourceMac: ByteArray,
        destinationMac: ByteArray,
        ipv6: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(sourceMac.size == MAC_BYTES) { "sourceMac must be $MAC_BYTES bytes" }
        require(destinationMac.size == MAC_BYTES) { "destinationMac must be $MAC_BYTES bytes" }
        require(length > 0 && offset >= 0 && offset + length <= ipv6.size) { "ipv6 payload must not be empty" }

        val frame = ByteArray(HEADER_BYTES + length)
        destinationMac.copyInto(frame, 0)
        sourceMac.copyInto(frame, 6)
        putU16(frame, 12, ETHERTYPE_IPV6)
        ipv6.copyInto(frame, HEADER_BYTES, offset, offset + length)
        return frame
    }

    /** Maps an IPv6 multicast destination to its Ethernet 33:33:xx:xx:xx:xx address. */
    fun multicastDestinationMac(ipv6: ByteArray, length: Int = ipv6.size): ByteArray? {
        if (length < IPV6_HEADER_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return null
        if ((ipv6[IPV6_DESTINATION_OFFSET].toInt() and 0xff) != 0xff) return null
        return byteArrayOf(
            0x33,
            0x33,
            ipv6[IPV6_DESTINATION_OFFSET + 12],
            ipv6[IPV6_DESTINATION_OFFSET + 13],
            ipv6[IPV6_DESTINATION_OFFSET + 14],
            ipv6[IPV6_DESTINATION_OFFSET + 15],
        )
    }

    internal fun isNeighborAdvertisement(ipv6: ByteArray, length: Int = ipv6.size): Boolean =
        length >= ICMPV6_NA_MIN_BYTES &&
            (ipv6[0].toInt() ushr 4 and 0x0f) == 6 &&
            (ipv6[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) == ICMPV6_NEXT_HEADER &&
            (ipv6[IPV6_HEADER_BYTES].toInt() and 0xff) == ICMPV6_NEIGHBOR_ADVERTISEMENT

    /**
     * A TUN interface has no layer-2 address, so Android emits Neighbor Advertisements without a
     * Target Link-Layer Address option. Add the option required by the Ethernet peer and repair
     * the IPv6 payload length and ICMPv6 checksum.
     */
    fun addNeighborAdvertisementTargetMac(ipv6: ByteArray, hostMac: ByteArray): ByteArray {
        require(hostMac.size == MAC_BYTES) { "hostMac must be $MAC_BYTES bytes" }
        if (ipv6.size < ICMPV6_NA_MIN_BYTES || (ipv6[0].toInt() ushr 4 and 0x0f) != 6) return ipv6
        if ((ipv6[IPV6_NEXT_HEADER_OFFSET].toInt() and 0xff) != ICMPV6_NEXT_HEADER ||
            (ipv6[IPV6_HEADER_BYTES].toInt() and 0xff) != ICMPV6_NEIGHBOR_ADVERTISEMENT
        ) return ipv6

        val payloadBytes = readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET)
        if (payloadBytes < ICMPV6_NA_BYTES || IPV6_HEADER_BYTES + payloadBytes > ipv6.size) return ipv6
        var optionOffset = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
        val payloadEnd = IPV6_HEADER_BYTES + payloadBytes
        while (optionOffset + 2 <= payloadEnd) {
            val optionBytes = (ipv6[optionOffset + 1].toInt() and 0xff) * 8
            if (optionBytes == 0 || optionOffset + optionBytes > payloadEnd) break
            if ((ipv6[optionOffset].toInt() and 0xff) == ICMPV6_TARGET_LINK_LAYER_OPTION &&
                optionBytes >= 8
            ) {
                return ipv6.copyOf().also { result ->
                    hostMac.copyInto(result, optionOffset + 2)
                    writeIcmpv6Checksum(result)
                }
            }
            optionOffset += optionBytes
        }

        val result = ipv6.copyOf(payloadEnd + 8)
        result[IPV6_PAYLOAD_LENGTH_OFFSET] = ((payloadBytes + 8) ushr 8).toByte()
        result[IPV6_PAYLOAD_LENGTH_OFFSET + 1] = (payloadBytes + 8).toByte()
        result[payloadEnd] = ICMPV6_TARGET_LINK_LAYER_OPTION.toByte()
        result[payloadEnd + 1] = 1
        hostMac.copyInto(result, payloadEnd + 2)
        writeIcmpv6Checksum(result)
        return result
    }

    private fun writeIcmpv6Checksum(ipv6: ByteArray) {
        val payloadBytes = readU16(ipv6, IPV6_PAYLOAD_LENGTH_OFFSET)
        ipv6[IPV6_HEADER_BYTES + 2] = 0
        ipv6[IPV6_HEADER_BYTES + 3] = 0
        var sum = 0L
        fun addWords(start: Int, bytes: Int) {
            var offset = start
            val end = start + bytes
            while (offset + 1 < end) {
                sum += readU16(ipv6, offset).toLong()
                offset += 2
            }
            if (offset < end) sum += (ipv6[offset].toInt() and 0xff).toLong() shl 8
        }
        addWords(IPV6_SOURCE_OFFSET, 32)
        sum += (payloadBytes ushr 16).toLong()
        sum += (payloadBytes and 0xffff).toLong()
        sum += ICMPV6_NEXT_HEADER.toLong()
        addWords(IPV6_HEADER_BYTES, payloadBytes)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv().toInt() and 0xffff
        ipv6[IPV6_HEADER_BYTES + 2] = (checksum ushr 8).toByte()
        ipv6[IPV6_HEADER_BYTES + 3] = checksum.toByte()
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private const val IPV6_HEADER_BYTES = 40
    private const val IPV6_PAYLOAD_LENGTH_OFFSET = 4
    private const val IPV6_NEXT_HEADER_OFFSET = 6
    private const val IPV6_SOURCE_OFFSET = 8
    private const val IPV6_DESTINATION_OFFSET = 24
    private const val ICMPV6_NEXT_HEADER = 58
    private const val ICMPV6_NEIGHBOR_ADVERTISEMENT = 136
    private const val ICMPV6_NA_BYTES = 24
    private const val ICMPV6_NA_MIN_BYTES = IPV6_HEADER_BYTES + ICMPV6_NA_BYTES
    private const val ICMPV6_TARGET_LINK_LAYER_OPTION = 2
}

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    // Same bound as the bridge's frame queue, which fails the session past it.
    private const val MAX_ENTRIES_PER_BLOCK = 256

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        val blockLength = DATAGRAM_INDEX + frame.size
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, NTH_LENGTH)

        putU32(block, 12, NDP16_SIG)
        putU16(block, 16, NDP_LENGTH)
        putU16(block, 18, 0)
        putU16(block, 20, DATAGRAM_INDEX)
        putU16(block, 22, frame.size)
        putU16(block, 24, 0)
        putU16(block, 26, 0)

        frame.copyInto(block, DATAGRAM_INDEX)
        // A transfer that ends exactly on a USB packet boundary is read as a short packet.
        return if (block.size % USB_PACKET_SIZE == 0) block + byteArrayOf(0) else block
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A block that repeats an NDP offset, lists more than
     * [MAX_ENTRIES_PER_BLOCK] entries or yields more datagram bytes than its own length (entries
     * that overlap) is dropped whole.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return emptyList()

        val datagrams = ArrayList<ByteArray>(1)
        var ndpOffset = offset + readU16(block, offset + 10)
        val visitedNdps = HashSet<Int>()
        var entries = 0
        var totalBytes = 0
        while (ndpOffset != 0) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (!visitedNdps.add(ndpOffset)) return emptyList()
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = readU16(block, ndpOffset + 4)
            val nextNdp = readU16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = readU16(block, entry)
                val datagramLength = readU16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                if (++entries > MAX_ENTRIES_PER_BLOCK) return emptyList()
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    totalBytes += datagramLength
                    if (totalBytes > length) return emptyList()
                    datagrams.add(block.copyOfRange(datagramStart, datagramEnd))
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
        }
        return datagrams
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}

/**
 * Queues and reaps reads on one [UsbDeviceConnection] on every supported Android version.
 *
 * API 26 added `UsbRequest.queue(ByteBuffer)` and `requestWait(timeout)`. Android 7.x has only
 * `queue(ByteBuffer, Int)` and a blocking `requestWait()`, so a helper thread reaps completions
 * there while a request is in flight, and [await] waits for them with a timeout.
 * The owner must be the only `requestWait` user on the connection, and must cancel its requests
 * before [close] so that the helper thread can finish.
 */
internal class UsbRequestQueue(private val connection: UsbDeviceConnection, threadName: String) : Closeable {
    private val reaper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) null else {
        UsbCompletionReaper(threadName) { connection.requestWait() }
    }

    /** Queues [buffer] from its position to its limit; a completed read advances the position. */
    fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return request.queue(buffer)
        // The Android 7.x call fills a direct buffer from its start, whatever its position.
        require(buffer.position() == 0) { "Android 7 USB reads must start at buffer position 0" }
        @Suppress("DEPRECATION")
        val queued = request.queue(buffer, buffer.remaining())
        if (queued) reaper!!.queued()
        return queued
    }

    /**
     * Returns the next completed request, or null when Android reports a failed connection.
     * Throws [TimeoutException] when none completes within [timeoutMillis].
     */
    @Throws(TimeoutException::class)
    fun await(timeoutMillis: Long): UsbRequest? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            connection.requestWait(timeoutMillis)
        } else {
            reaper!!.await(timeoutMillis)
        }

    override fun close() {
        reaper?.close()
    }
}

/**
 * Turns a blocking "wait for any completion" call into completions that can be awaited with a
 * timeout. [reapBlocking] runs on one helper thread, and only while a queued request has not
 * completed yet: with nothing in flight it would block until the device detaches.
 * A null from [reapBlocking] means the connection failed; every later [await] then returns null.
 */
internal class UsbCompletionReaper<T : Any>(
    private val threadName: String,
    private val reapBlocking: () -> T?,
) : Closeable {
    private val lock = Object()
    private val completed = kotlin.collections.ArrayDeque<T>()
    private var inFlight = 0
    private var failed = false
    private var closed = false
    private var thread: Thread? = null

    /** Records one successfully queued request. Call it only after the request was queued. */
    fun queued() = synchronized(lock) {
        check(!closed) { "USB request reaper is closed" }
        inFlight += 1
        if (thread == null && !failed) {
            thread = Thread(::reapLoop, threadName).apply {
                isDaemon = true
                start()
            }
        }
        lock.notifyAll()
    }

    @Throws(TimeoutException::class)
    fun await(timeoutMillis: Long): T? = synchronized(lock) {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (completed.isEmpty() && !failed) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw TimeoutException("No USB request completed within $timeoutMillis ms")
            TimeUnit.NANOSECONDS.timedWait(lock, remaining)
        }
        completed.removeFirstOrNull()
    }

    override fun close() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    private fun reapLoop() {
        while (true) {
            synchronized(lock) {
                while (inFlight == 0 && !closed) lock.wait()
                if (inFlight == 0) {
                    thread = null
                    return
                }
            }
            val request = try {
                reapBlocking()
            } catch (_: RuntimeException) {
                null
            }
            synchronized(lock) {
                if (request == null) {
                    failed = true
                    thread = null
                    lock.notifyAll()
                    return
                }
                inFlight -= 1
                completed.addLast(request)
                lock.notifyAll()
            }
        }
    }
}

internal data class UsbMuxFrame(
    val protocol: Int,
    val length: Int,
    val word8: Int,
    val sequence: Int,
    val payload: ByteArray,
)

/** Incremental framing; callers serialize access with their USBMUX state lock. */
internal class UsbMuxFrameBuffer(private val diagnostic: (String) -> Unit = {}) {
    private var bytes = EMPTY_BYTES
    private var optionalReplyPadding: UsbMuxFrame? = null
    private var paddingReports = 0
    private var lastUsbReadBytes = 0
    val bufferedBytes: Int get() = bytes.size

    fun append(transfer: ByteArray) {
        lastUsbReadBytes = transfer.size
        if (transfer.isEmpty()) return
        bytes = if (bytes.isEmpty()) transfer else bytes + transfer
    }

    fun takeFrame(): UsbMuxFrame? {
        if (bytes.size < HEADER_BYTES) return null
        var length = readU32(bytes, 4)
        if (length !in HEADER_BYTES..MAX_FRAME_BYTES) {
            val previous = optionalReplyPadding
            // The captured iOS 27 VERSION and valid RX TCP replies, including payload-bearing
            // replies, have four extra bytes. Do not scan or discard a USB completion:
            // only this single four-byte boundary is eligible, before validated TCP or the
            // captured protocol-1 diagnostic shape.
            // A normal or split next header at offset zero is always preserved.
            // Wait for the candidate MUX/TCP headers or complete diagnostic payload. A timeout
            // leaves these bytes in place; a legitimate split frame must never be lost.
            if (previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                bytes.size < PADDING_BYTES + HEADER_BYTES) return null
            val followingProtocol = readU32(bytes, PADDING_BYTES)
            val followingLength = readU32(bytes, PADDING_BYTES + 4)
            val candidateHeader = previous != null && readU32(bytes, 0) != PROTOCOL_TCP &&
                readU32(bytes, PADDING_BYTES + 8) == CAPTURED_REPLY_MAGIC
            val validTarget = when {
                candidateHeader && followingProtocol == PROTOCOL_TCP &&
                    followingLength in (HEADER_BYTES + TCP_HEADER_BYTES)..MAX_FRAME_BYTES -> {
                    if (bytes.size < PADDING_BYTES + HEADER_BYTES + TCP_HEADER_BYTES) return null
                    val tcpHeaderBytes = ((bytes[PADDING_BYTES + HEADER_BYTES + 12].toInt() ushr 4) and 0x0f) * 4
                    tcpHeaderBytes in TCP_HEADER_BYTES..(followingLength - HEADER_BYTES) &&
                        readU16(bytes, PADDING_BYTES + HEADER_BYTES) != 0 &&
                        readU16(bytes, PADDING_BYTES + HEADER_BYTES + 2) != 0
                }
                candidateHeader && followingProtocol == PROTOCOL_DIAGNOSTIC &&
                    followingLength in (HEADER_BYTES + 2)..(HEADER_BYTES + MAX_DIAGNOSTIC_PAYLOAD_BYTES) -> {
                    // Issue #100 also captures four extra bytes before/after protocol 1.
                    // Validate its entire bounded subtype/text payload before recovering.
                    // A timeout leaves the trailer and incomplete frame untouched.
                    if (bytes.size < PADDING_BYTES + followingLength) return null
                    isCapturedDiagnosticPayload(bytes, PADDING_BYTES + HEADER_BYTES, followingLength - HEADER_BYTES)
                }
                // Issue #100 (0.2.19): a 24-byte transfer carried a VERSION reply and its own trailer
                // after the previous reply's trailer. Its whole header is fixed, so validate it exactly.
                previous != null && readU32(bytes, 0) != PROTOCOL_TCP && followingProtocol == PROTOCOL_VERSION &&
                    followingLength == VERSION_BYTES && readU32(bytes, PADDING_BYTES + 8) == VERSION_REPLY_WORD -> true
                else -> false
            }
            if (previous != null && validTarget) {
                bytes = bytes.copyOfRange(PADDING_BYTES, bytes.size)
                optionalReplyPadding = null
                if (paddingReports++ < MAX_PADDING_REPORTS) report(
                    "USBMUX optional reply padding skipped bytes=$PADDING_BYTES " +
                        "previousProtocol=${previous.protocol} previousLength=${previous.length} " +
                        "nextProtocol=$followingProtocol nextLength=$followingLength " +
                        "lastUsbReadBytes=$lastUsbReadBytes bufferedBytes=${bytes.size}")
                if (bytes.size < HEADER_BYTES) return null
                length = readU32(bytes, 4)
            } else {
                report("USBMUX framing rejected declaredLength=$length bufferedBytes=${bytes.size} " +
                    "lastUsbReadBytes=$lastUsbReadBytes optionalReplyPadding=${previous != null}")
                throw IphoneUsbException.Protocol("Invalid USBMUX frame length $length")
            }
        }
        if (bytes.size < length) return null
        val frame = UsbMuxFrame(
            protocol = readU32(bytes, 0), length = length, word8 = readU32(bytes, 8),
            sequence = ((bytes[12].toInt() and 0xff) shl 8) or (bytes[13].toInt() and 0xff),
            payload = bytes.copyOfRange(HEADER_BYTES, length),
        )
        bytes = if (length == bytes.size) EMPTY_BYTES else bytes.copyOfRange(length, bytes.size)
        optionalReplyPadding = frame.takeIf(::canHaveOptionalReplyPadding)
        return frame
    }

    private fun canHaveOptionalReplyPadding(frame: UsbMuxFrame): Boolean {
        if (frame.protocol == PROTOCOL_VERSION && frame.length == VERSION_BYTES && frame.word8 == VERSION_REPLY_WORD) return true
        if (frame.protocol == PROTOCOL_DIAGNOSTIC && frame.word8 == CAPTURED_REPLY_MAGIC) {
            return isCapturedDiagnosticPayload(frame.payload, 0, frame.payload.size)
        }
        if (frame.protocol != PROTOCOL_TCP || frame.word8 != CAPTURED_REPLY_MAGIC ||
            frame.payload.size < TCP_HEADER_BYTES) return false
        val tcpHeaderBytes = ((frame.payload[12].toInt() ushr 4) and 0x0f) * 4
        return tcpHeaderBytes in TCP_HEADER_BYTES..frame.payload.size &&
            readU16(frame.payload, 0) != 0 && readU16(frame.payload, 2) != 0
    }

    /** Only the captured diagnostic subtype followed by bounded printable ASCII qualifies. */
    private fun isCapturedDiagnosticPayload(source: ByteArray, offset: Int, length: Int): Boolean =
        length in 2..MAX_DIAGNOSTIC_PAYLOAD_BYTES && source[offset].toInt() == DIAGNOSTIC_TEXT_SUBTYPE &&
            (offset + 1 until offset + length).all { source[it].toInt() in 0x20..0x7e }

    private fun report(line: String) { runCatching { diagnostic(line) } }

    private fun readU16(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val HEADER_BYTES = 16
        const val TCP_HEADER_BYTES = 20
        const val MAX_FRAME_BYTES = 65_536
        const val VERSION_BYTES = 20
        const val VERSION_REPLY_WORD = 2
        const val PADDING_BYTES = 4
        const val MAX_PADDING_REPORTS = 4
        const val PROTOCOL_VERSION = 0
        const val PROTOCOL_DIAGNOSTIC = 1
        const val PROTOCOL_TCP = 6
        const val DIAGNOSTIC_TEXT_SUBTYPE = 4
        const val MAX_DIAGNOSTIC_PAYLOAD_BYTES = 1_024
        val CAPTURED_REPLY_MAGIC = 0xfaceface.toInt()
        private val EMPTY_BYTES = ByteArray(0)
    }
}

/**
 * Switches the iPhone to its CarPlay configuration when a head-unit kernel driver holds an
 * interface of the current one.
 *
 * Linux refuses SET_CONFIGURATION while any interface of the active configuration is bound
 * ("usbfs: interface 0 claimed by snd-usb-audio while '…' sets config #6"). An 8227L kernel bound
 * snd-usb-audio to the iPhone's audio configuration, so every switch failed, the claims landed on
 * the wrong configuration and DiPlay reported "could not claim the NCM interface 3" (#518).
 * Android detaches a kernel driver only through a forced claim, so each interface number is
 * claimed with force. Android's releaseInterface() asks the kernel to reattach the driver
 * (USBDEVFS_CONNECT), which undid the detach before the switch in 0.2.19, so the claims are
 * dropped by closing the connection instead: usbfs releases them on close without reattaching.
 */
internal object UsbConfigurationSelection {
    data class Outcome(
        val target: Int,
        val selected: Boolean,
        val before: Int?,
        val after: Int?,
        val detached: List<Int>,
    ) {
        /** Android reported the switch as failed and the iPhone still reads another configuration. */
        val wrongConfiguration: Boolean get() = !selected && after != null && after != target

        fun diagnostic(): String =
            "USB configuration select target=$target ok=$selected before=${before ?: "unknown"} " +
                "detached=${detached.joinToString(",").ifEmpty { "none" }} after=${after ?: "unknown"}"
    }

    /**
     * Returns null when the first [setConfiguration] succeeds. Otherwise, unless
     * [activeConfiguration] already reads [target], detaches drivers from [interfaceIds] with a
     * claim that stays held, lets [reopen] close and reopen the connection to drop those claims,
     * and tries once more on the new connection.
     */
    fun select(
        target: Int,
        interfaceIds: List<Int>,
        setConfiguration: () -> Boolean,
        activeConfiguration: () -> Int?,
        detach: (Int) -> Boolean,
        reopen: () -> Unit,
    ): Outcome? {
        if (setConfiguration()) return null
        val before = activeConfiguration()
        if (before == target) return Outcome(target, selected = false, before, before, emptyList())
        val detached = interfaceIds.distinct().sorted().filter(detach)
        if (detached.isNotEmpty()) reopen()
        val selected = setConfiguration()
        return Outcome(target, selected, before, activeConfiguration(), detached)
    }
}

/**
 * Finds the NCM control/data interface pair inside an active iPhone configuration.
 *
 * LIVI claims the control interface, claims the data interface, and selects data alternate
 * setting 1, the setting that carries the bulk endpoints. This class only reads descriptors.
 */
object NcmFunctionDiscovery {
    const val CONTROL_CLASS = 0x02
    const val CONTROL_SUBCLASS = 0x0d
    const val DATA_CLASS = 0x0a
    const val APPLE_ETHERNET_CLASS = 0xff
    const val APPLE_ETHERNET_SUBCLASS = 0xfd
    const val APPLE_ETHERNET_PROTOCOL = 0x01
    const val DATA_ALTERNATE_SETTING = 1

    data class NcmFunction(
        val control: UsbInterface,
        val data: UsbInterface,
        val statusIn: UsbEndpoint?,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint,
    )

    fun find(configuration: UsbConfiguration): NcmFunction? {
        return findCdcNcm(configuration)
    }

    private fun findCdcNcm(configuration: UsbConfiguration): NcmFunction? {
        val control = interfaces(configuration).firstOrNull {
            it.interfaceClass == CONTROL_CLASS && it.interfaceSubclass == CONTROL_SUBCLASS
        } ?: return null
        val data = interfaces(configuration)
            .filter { it.interfaceClass == DATA_CLASS && bulkEndpoints(it) != null }
            .minByOrNull { if (it.alternateSetting == DATA_ALTERNATE_SETTING) 0 else 1 }
            ?: return null
        val endpoints = bulkEndpoints(data) ?: return null
        val statusIn = (0 until control.endpointCount)
            .map(control::getEndpoint)
            .singleOrNull {
                it.direction == UsbConstants.USB_DIR_IN &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_INT
            }
        return NcmFunction(control, data, statusIn, endpoints.first, endpoints.second)
    }

    private fun interfaces(configuration: UsbConfiguration): List<UsbInterface> =
        (0 until configuration.interfaceCount).map(configuration::getInterface)

    private fun bulkEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val input = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val output = endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (input != null && output != null) input to output else null
    }
}

/**
 * Counts the Ethernet frames on the NCM link, so a wired attempt that never gets its AirPlay session can tell an
 * iPhone that never tried (no frames, or no TCP connection attempt) from one whose packets arrive but do not reach
 * DiPlay's listener (#889, #890). Only headers are read, never payloads. Free of Android types for JVM tests.
 */
internal class NcmTrafficCounter(private val nanoTime: () -> Long = System::nanoTime) {
    private var fromIphone = 0L
    private var ipv6 = 0L
    private var ipv4 = 0L
    private var toIphone = 0L
    private val synsByPort = LinkedHashMap<Int, Int>()
    private val lastSynNanosByPort = HashMap<Int, Long>()

    @Synchronized fun received(frame: ByteArray) {
        fromIphone++
        val etherType = u16(frame, ETHER_TYPE_OFFSET) ?: return
        val tcp = when (etherType) {
            ETHER_TYPE_IPV6 -> {
                ipv6++
                if (u8(frame, ETHERNET_HEADER + IPV6_NEXT_HEADER_OFFSET) == PROTOCOL_TCP) ETHERNET_HEADER + IPV6_HEADER else null
            }
            ETHER_TYPE_IPV4 -> {
                ipv4++
                val headerLength = ((u8(frame, ETHERNET_HEADER) ?: return) and 0x0f) * 4
                if (u8(frame, ETHERNET_HEADER + IPV4_PROTOCOL_OFFSET) == PROTOCOL_TCP) ETHERNET_HEADER + headerLength else null
            }
            else -> null
        } ?: return
        val flags = u8(frame, tcp + TCP_FLAGS_OFFSET) ?: return
        if (flags and TCP_SYN == 0 || flags and TCP_ACK != 0) return
        val port = u16(frame, tcp + TCP_DESTINATION_PORT_OFFSET) ?: return
        if (port in synsByPort || synsByPort.size < MAX_PORTS) {
            synsByPort[port] = (synsByPort[port] ?: 0) + 1
            lastSynNanosByPort[port] = nanoTime()
        }
    }

    /** Whether the iPhone tried to connect to [port] at or after [sinceNanos] ([System.nanoTime] time). */
    @Synchronized fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean =
        lastSynNanosByPort[port]?.let { it - sinceNanos >= 0 } == true

    @Synchronized fun sent() {
        toIphone++
    }

    /** One line for the report; [airPlayPort] is DiPlay's listener, whose connection attempts are named apart. */
    @Synchronized fun summary(airPlayPort: Int?): String {
        val toAirPlay = airPlayPort?.let { synsByPort[it] } ?: 0
        val others = synsByPort.filterKeys { it != airPlayPort }.entries.joinToString(",") { "${it.key}:${it.value}" }
        return "fromIphone=$fromIphone ipv6=$ipv6 ipv4=$ipv4 tcpConnectToAirPlay=$toAirPlay " +
            "tcpConnectOther=${others.ifEmpty { "none" }} toIphone=$toIphone"
    }

    private fun u8(frame: ByteArray, offset: Int): Int? = frame.getOrNull(offset)?.toInt()?.and(0xff)

    private fun u16(frame: ByteArray, offset: Int): Int? {
        val high = u8(frame, offset) ?: return null
        val low = u8(frame, offset + 1) ?: return null
        return (high shl 8) or low
    }

    private companion object {
        const val ETHERNET_HEADER = 14
        const val ETHER_TYPE_OFFSET = 12
        const val ETHER_TYPE_IPV4 = 0x0800
        const val ETHER_TYPE_IPV6 = 0x86dd
        const val IPV6_HEADER = 40
        const val IPV6_NEXT_HEADER_OFFSET = 6
        const val IPV4_PROTOCOL_OFFSET = 9
        const val PROTOCOL_TCP = 6
        const val TCP_DESTINATION_PORT_OFFSET = 2
        const val TCP_FLAGS_OFFSET = 13
        const val TCP_SYN = 0x02
        const val TCP_ACK = 0x10
        const val MAX_PORTS = 8
    }
}

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/** Upper bound enforced by Android 8.0/8.1's queue(ByteBuffer) overload. */
internal const val USBFS_BULK_URB_CEILING_BYTES = 16 * 1024

/**
 * Android 8.0/8.1 queue(ByteBuffer) throws above 16 KiB, so cap the first submission there.
 * The false-return fallback is a compatibility hypothesis for explicit vendor queue rejection.
 * Android 9 UsbRequest.queue(ByteBuffer) accepts any size and clears its queued state on false:
 * https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/hardware/usb/UsbRequest.java
 * Large read regions may retry at smaller sizes down to 2 KiB, even when a platform cap or
 * cached successful size reduced the first submission. Short regions are never retried.
 * Call under the pipe's state lock, including publication, queueing and the open-state checks.
 */
internal class UsbReadQueuePolicy(private val queueCeiling: Int? = null) {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val originalRemaining = buffer.remaining()
        val firstBytes = minOf(
            originalRemaining, successfulLimit ?: originalRemaining, queueCeiling ?: originalRemaining,
        )
        buffer.limit(position + firstBytes)
        if (submitUnchanged(buffer, position, firstBytes, submit)) {
            return UsbReadQueueResult(true, firstBytes)
        }
        if (originalRemaining <= COMPATIBILITY_BYTES || firstBytes <= MIN_COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }

        var size = if (firstBytes > COMPATIBILITY_BYTES) COMPATIBILITY_BYTES else {
            maxOf(MIN_COMPATIBILITY_BYTES, firstBytes / 2)
        }
        var lastAttempt = firstBytes
        while (size >= MIN_COMPATIBILITY_BYTES) {
            checkOpen()
            buffer.limit(position + size)
            lastAttempt = size
            if (submitUnchanged(buffer, position, size, submit)) {
                successfulLimit = size
                return UsbReadQueueResult(true, firstBytes, size)
            }
            size /= 2
        }
        buffer.limit(originalLimit)
        return UsbReadQueueResult(false, firstBytes, lastAttempt)
    }

    /** A false queue result is retryable only while its buffer range remains unchanged. */
    private fun submitUnchanged(
        buffer: ByteBuffer,
        position: Int,
        size: Int,
        submit: (ByteBuffer) -> Boolean,
    ): Boolean {
        if (submit(buffer)) return true
        check(buffer.position() == position && buffer.limit() == position + size) {
            "Rejected USB queue changed its buffer state"
        }
        return false
    }

    companion object {
        private const val COMPATIBILITY_BYTES = USBFS_BULK_URB_CEILING_BYTES
        private const val MIN_COMPATIBILITY_BYTES = 2 * 1024

        fun forCurrentPlatform(): UsbReadQueuePolicy = UsbReadQueuePolicy(
            // Android 7 uses queue(ByteBuffer, Int); Android 9 removed the new queue's bound.
            // Only API 26/27 require a ceiling before queueing, rather than a retry on false.
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.P) {
                USBFS_BULK_URB_CEILING_BYTES
            } else {
                null
            },
        )
    }
}

/**
 * Descriptor-based discovery of the iPhone's CarPlay configuration.
 *
 * Configuration ids differ between iPhone models, so the configuration is identified by its
 * interfaces: Apple USB Multiplexor (USBMUX) plus the NCM/Ethernet function CarPlay uses.
 */
object IphoneCarPlayConfiguration {
    const val TAG = "xcertplay-usb"

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val PREFERRED_USBMUX_OUT = 0x04
    private const val PREFERRED_USBMUX_IN = 0x85

    fun find(device: UsbDevice): UsbConfiguration? {
        val configurations = (0 until device.configurationCount).map(device::getConfiguration)
        val chosen = configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it) }
            ?: configurations.firstOrNull { usbMuxInterface(it) != null && hasCdcNcm(it) }
        Log.i(
            TAG,
            "carplay config chosen=${chosen?.id} " +
                "available=${configurations.map { it.id }} detail=${chosen?.let(::describe)}",
        )
        return chosen
    }

    /** Only CarPlay mode adds Apple Ethernet; iOS 17+ offers USBMUX and NCM without it (#634). */
    fun isCarPlayMode(configuration: UsbConfiguration): Boolean =
        usbMuxInterface(configuration) != null && hasCdcNcm(configuration) && hasAppleEthernet(configuration)

    fun describe(configuration: UsbConfiguration): String =
        (0 until configuration.interfaceCount).joinToString(",") { index ->
            val usbInterface = configuration.getInterface(index)
            "${usbInterface.id}/${usbInterface.alternateSetting}" +
                ":${usbInterface.interfaceClass.toString(16)}" +
                ".${usbInterface.interfaceSubclass.toString(16)}" +
                ".${usbInterface.interfaceProtocol.toString(16)}" +
                "x${usbInterface.endpointCount}"
        }

    fun usbMuxInterface(configuration: UsbConfiguration): UsbInterface? =
        (0 until configuration.interfaceCount).map(configuration::getInterface).firstOrNull {
            it.interfaceClass == USBMUX_CLASS &&
                it.interfaceSubclass == USBMUX_SUBCLASS &&
                it.interfaceProtocol == USBMUX_PROTOCOL
        }

    fun usbMuxEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        val endpoints = (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint)
        val out = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_OUT &&
                it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_OUT &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        val input = endpoints.firstOrNull {
            it.address == PREFERRED_USBMUX_IN &&
                it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        } ?: endpoints.singleOrNull {
            it.direction == UsbConstants.USB_DIR_IN &&
                it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
        return if (out != null && input != null) out to input else null
    }

    private fun hasCdcNcm(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == NCM_CONTROL_CLASS && it.interfaceSubclass == NCM_CONTROL_SUBCLASS
        }

    private fun hasAppleEthernet(configuration: UsbConfiguration): Boolean =
        (0 until configuration.interfaceCount).map(configuration::getInterface).any {
            it.interfaceClass == APPLE_ETHERNET_CLASS &&
                it.interfaceSubclass == APPLE_ETHERNET_SUBCLASS &&
                it.interfaceProtocol == APPLE_ETHERNET_PROTOCOL
        }
}

/** Checks Android 7.1 through 9's explicit USB permission activity without opening it. */
internal object UsbPermissionActivityProbe {
    internal const val COMPONENT = "com.android.systemui/com.android.systemui.usb.UsbPermissionActivity"

    enum class State { AVAILABLE, UNAVAILABLE, UNKNOWN }

    data class Result(val state: State) {
        fun diagnostic(): String = "USB permission UI state=$state component=$COMPONENT"
    }

    fun inspect(context: Context): Result {
        // Android 10+ uses overlay-configurable components and package visibility restrictions.
        if (!supportsProbe()) return Result(State.UNKNOWN)
        return Result(probe(context.applicationContext.packageManager))
    }

    internal fun supportsProbe(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk in Build.VERSION_CODES.N_MR1..Build.VERSION_CODES.P

    internal fun probe(manager: PackageManager): State = try {
        val component = ComponentName.unflattenFromString(COMPONENT)!!
        // system_server opens this activity, so it need not be exported to applications.
        val info = manager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        val activityEnabled = enabled(manager.getComponentEnabledSetting(component), info.enabled)
        val appEnabled = enabled(
            manager.getApplicationEnabledSetting(component.packageName),
            info.applicationInfo.enabled,
        )
        if (activityEnabled && appEnabled) State.AVAILABLE else State.UNAVAILABLE
    } catch (_: PackageManager.NameNotFoundException) {
        State.UNAVAILABLE
    } catch (_: Exception) {
        State.UNKNOWN
    }

    private fun enabled(setting: Int, manifestEnabled: Boolean): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }
}

internal object UsbPermissionBroadcast {
    /** Signature permission declared in the manifest. Only this app can hold it. */
    fun senderPermission(context: Context): String = "${context.packageName}.USB_PERMISSION_RESULT"

    /**
     * Android 13 adds RECEIVER_NOT_EXPORTED. Before that a receiver registered at run time accepts broadcasts from
     * any app, so require a permission that only this app holds. The USB dialog result is sent through this
     * app's own PendingIntent, so it carries this app's identity.
     */
    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, senderPermission(context), null)
        }
    }
}
