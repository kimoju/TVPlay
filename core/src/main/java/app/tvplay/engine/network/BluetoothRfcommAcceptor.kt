package app.tvplay.engine.network

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.tvplay.engine.auth.DuplexByteStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlin.math.min

typealias BlockingDuplexByteStream = DuplexByteStream
typealias BluetoothRfcommAcceptor = BluetoothRfcommDuplexStream

/** Nullable platform getters matter on head units that expose a socket without usable streams. */
internal interface BluetoothRfcommSocketAccess {
    fun inputStream(): InputStream?
    fun outputStream(): OutputStream?
    fun close()
}

class BluetoothRfcommStreamException internal constructor(
    val operation: Operation,
    val reason: Reason,
    cause: Throwable? = null,
) : IOException("Bluetooth RFCOMM ${operation.name.lowercase()} failed: ${reason.name.lowercase()}", cause) {
    enum class Operation { INPUT_STREAM, OUTPUT_STREAM, READ, WRITE }
    enum class Reason { STREAM_UNAVAILABLE, STREAM_ACCESS_FAILED, READ_FAILED, WRITE_FAILED }
}

/**
 * A bounded [DuplexByteStream] over an already-open RFCOMM socket.
 *
 * Android's RFCOMM input has no per-read timeout, so one daemon reader performs the blocking
 * reads. [close] closes the owned socket, which unblocks that reader.
 */
class BluetoothRfcommDuplexStream internal constructor(
    private val socket: BluetoothRfcommSocketAccess,
    private val onDiagnostic: (String) -> Unit = {},
) : DuplexByteStream {
    constructor(socket: BluetoothSocket, onDiagnostic: (String) -> Unit = {}) : this(
        object : BluetoothRfcommSocketAccess {
            override fun inputStream(): InputStream? = socket.inputStream
            override fun outputStream(): OutputStream? = socket.outputStream
            override fun close() = socket.close()
        },
        onDiagnostic,
    )

    private val lock = Object()
    private val sendLock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var peerEnded = false
    private var closed = false
    private var socketCloseStarted = false
    private var failure: IOException? = null
    private var readCalls = 0L
    private var receivedBytes = 0L
    private var flushAllowed = true

    /** Bytes the peer has delivered so far; zero for as long as nothing answers. */
    val bytesReceived: Long get() = synchronized(lock) { receivedBytes }

    // Acquire both streams before starting any reader or iAP2 protocol thread. Android getters
    // have platform-nullability, and some vendor sockets return null after connect() succeeds.
    private val input = acquireStream(BluetoothRfcommStreamException.Operation.INPUT_STREAM, socket::inputStream)
    private val output = acquireStream(BluetoothRfcommStreamException.Operation.OUTPUT_STREAM, socket::outputStream)

    private val reader = Thread(::readLoop, "xcertplay-bluetooth-rfcomm-reader").apply {
        isDaemon = true
    }

    init {
        report("Bluetooth RFCOMM streams ready input=true output=true")
        reader.start()
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("Bluetooth RFCOMM stream is closed")
            }
            try {
                output.write(data)
                if (flushAllowed) flushOutput()
            } catch (error: Exception) {
                val io = BluetoothRfcommStreamException(
                    BluetoothRfcommStreamException.Operation.WRITE,
                    BluetoothRfcommStreamException.Reason.WRITE_FAILED,
                    error,
                )
                throw fail(io)
            }
        }
    }

    /**
     * Android's socket flush polls the TIOCOUTQ ioctl. A Ford SYNC+ (i.MX8, Android 8.1) refuses
     * that ioctl with EACCES although the write itself succeeded (#582). The bytes are already
     * queued, so this socket stops flushing instead of failing the link.
     */
    private fun flushOutput() {
        try {
            output.flush()
        } catch (error: IOException) {
            if (!refusedIoctl(error)) throw error
            flushAllowed = false
            report("Bluetooth RFCOMM flush refused by the head unit; sending without flush")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                failure?.let { throw it }
                if (peerEnded || closed) return EMPTY

                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** Closes the owned socket and waits briefly for the reader to leave its blocking read. */
    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                lock.notifyAll()
                true
            }
        }
        if (!firstClose) return

        var closeFailure = closeSocketOnce()
        if (Thread.currentThread() !== reader) {
            try {
                reader.join(CLOSE_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                closeFailure = combine(
                    closeFailure,
                    IOException("Interrupted while closing the Bluetooth RFCOMM reader", interrupted),
                )
            }
            if (reader.isAlive) {
                closeFailure = combine(
                    closeFailure,
                    IOException("Bluetooth RFCOMM reader did not stop after close"),
                )
            }
        }
        if (closeFailure != null) throw closeFailure
    }

    private fun readLoop() {
        var readFailure: IOException? = null
        try {
            while (true) {
                val readSize = synchronized(lock) {
                    while (!closed && failure == null && pendingBytes >= MAX_PENDING_BYTES) {
                        lock.wait()
                    }
                    if (closed || failure != null) return
                    min(READ_CHUNK_BYTES, MAX_PENDING_BYTES - pendingBytes)
                }

                val buffer = ByteArray(readSize)
                readCalls++
                when (val count = input.read(buffer)) {
                    -1 -> {
                        if (!isStopping()) reportReadTerminal("ENDED")
                        synchronized(lock) {
                            peerEnded = true
                            lock.notifyAll()
                        }
                        return
                    }

                    0 -> Unit
                    else -> synchronized(lock) {
                        if (closed) return
                        receivedBytes += count
                        pending.addLast(if (count == buffer.size) buffer else buffer.copyOf(count))
                        pendingBytes += count
                        lock.notifyAll()
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!isStopping()) {
                readFailure = IOException("Bluetooth RFCOMM reader was interrupted", interrupted)
            }
        } catch (io: IOException) {
            if (!isStopping()) readFailure = BluetoothRfcommStreamException(
                BluetoothRfcommStreamException.Operation.READ,
                BluetoothRfcommStreamException.Reason.READ_FAILED,
                io,
            )
        } catch (failure: Throwable) {
            if (!isStopping()) readFailure = BluetoothRfcommStreamException(
                BluetoothRfcommStreamException.Operation.READ,
                BluetoothRfcommStreamException.Reason.READ_FAILED,
                failure,
            )
            if (failure is Error) throw failure
        } finally {
            readFailure?.let {
                reportReadTerminal("FAILED")
                fail(it)
            }
            val closeFailure = closeSocketOnce()
            if (readFailure == null && closeFailure != null && !isClosed() && !endedCleanly()) {
                fail(closeFailure)
            }
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) {
            lock.notifyAll()
            return chunk
        }

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        lock.notifyAll()
        return head
    }

    private fun fail(io: IOException): IOException {
        synchronized(lock) {
            failure?.let { return it }
            // Closing a socket intentionally unblocks both read and write/flush. Such a write
            // exception during bootstrap handoff is cancellation, not a new transport failure.
            if (closed) return IOException("Bluetooth RFCOMM stream is closed")
            failure = io
            lock.notifyAll()
        }
        reportFailure(io)
        closeSocketOnce()?.let { if (it !== io) io.addSuppressed(it) }
        return io
    }

    private fun <T : Any> acquireStream(
        operation: BluetoothRfcommStreamException.Operation,
        acquire: () -> T?,
    ): T {
        try {
            return acquire() ?: throw BluetoothRfcommStreamException(
                operation, BluetoothRfcommStreamException.Reason.STREAM_UNAVAILABLE,
            )
        } catch (error: Throwable) {
            val failure = if (error is BluetoothRfcommStreamException) error else
                BluetoothRfcommStreamException(operation, BluetoothRfcommStreamException.Reason.STREAM_ACCESS_FAILED, error)
            reportFailure(failure)
            closeSocketOnce()?.let(failure::addSuppressed)
            if (error is Error) throw error
            throw failure
        }
    }

    private fun reportFailure(error: IOException) {
        val streamFailure = error as? BluetoothRfcommStreamException
        report("Bluetooth RFCOMM stream result=FAILED operation=${streamFailure?.operation ?: "UNKNOWN"} " +
            "reason=${streamFailure?.reason ?: "UNKNOWN"} failureClass=${error.javaClass.simpleName} " +
            "causeClass=${error.cause?.javaClass?.simpleName ?: "none"} " +
            "nestedCauseClass=${error.cause?.cause?.javaClass?.simpleName ?: "none"}")
    }

    private fun reportReadTerminal(result: String) {
        report("Bluetooth RFCOMM reader result=$result beforeFirstByte=${receivedBytes == 0L} " +
            "readCalls=$readCalls receivedBytes=$receivedBytes")
    }

    private fun report(message: String) {
        try { onDiagnostic(message) } catch (_: Exception) {
            // A diagnostic callback cannot prevent transport setup or owned-socket cleanup.
        }
    }

    private fun closeSocketOnce(): IOException? {
        synchronized(lock) {
            if (socketCloseStarted) return null
            socketCloseStarted = true
        }
        return try {
            socket.close()
            null
        } catch (failure: Throwable) {
            if (failure is Error) throw failure
            IOException("Could not close the Bluetooth RFCOMM socket", failure)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun isStopping(): Boolean = synchronized(lock) { closed || failure != null }

    private fun endedCleanly(): Boolean = synchronized(lock) { closed || peerEnded }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun combine(first: IOException?, second: IOException): IOException {
        if (first == null) return second
        if (first !== second) first.addSuppressed(second)
        return first
    }

    private companion object {
        private const val READ_CHUNK_BYTES = 8_192
        private const val MAX_PENDING_BYTES = 65_536
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val EMPTY = ByteArray(0)
    }
}

private val PERMISSION_ERRNO = Regex("\\bE(ACCES|PERM)\\b")

/** A permission refusal as android.system.ErrnoException words it: "ioctl failed: EACCES (…)". */
internal fun refusedIoctl(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it.message?.contains(PERMISSION_ERRNO) == true }

/**
 * What wireless startup can know about the head unit's Bluetooth before it changes the Wi-Fi.
 *
 * Wireless CarPlay begins over Bluetooth. With the radio off, bringing up the car hotspot or a
 * Wi-Fi Direct group first is work for an attempt that cannot succeed. On a head unit with one
 * radio it can also take the unit off the Wi-Fi it was using, and every retry does it again.
 */
object BluetoothRadioCheck {
    fun isOn(context: Context): Boolean = BluetoothDiagnosticSnapshot.capture(context).isEnabled

    /**
     * Why this attempt cannot reach an iPhone, or null when it can go on. A radio that DiPlay
     * paused itself is turned back on before the handshake, so it does not stop the attempt here.
     */
    fun blocker(adapterPresent: Boolean, enabled: Boolean, pausedByDiPlay: Boolean): String? = when {
        !adapterPresent -> NO_ADAPTER
        !enabled && !pausedByDiPlay -> RADIO_OFF
        else -> null
    }

    /**
     * Why this attempt cannot reach an iPhone, taking state into account.
     */
    fun blockerForState(adapterPresent: Boolean, state: Int?, pausedByDiPlay: Boolean): String? = when {
        !adapterPresent || state == null -> NO_ADAPTER
        (state == BluetoothAdapter.STATE_OFF ||
            state == BluetoothAdapter.STATE_TURNING_OFF) && !pausedByDiPlay -> RADIO_OFF
        else -> null
    }

    /**
     * What [blocker]'s answer is thrown as. A radio that is off can be switched on, so that attempt is
     * retried; no retry gives a head unit an adapter, so that one stops with its cause.
     */
    fun failure(blocker: String): IOException =
        if (blocker == NO_ADAPTER) WirelessStartupException(WirelessStartupFailure.BLUETOOTH_UNUSABLE, blocker)
        else IOException(blocker)

    /** Ends in the words the host screen matches on for Bluetooth that apps cannot use. */
    const val NO_ADAPTER = "Bluetooth adapter is unavailable: Android on this head unit ${BluetoothLinkCheck.NO_SOCKETS_MARK}"

    /** The words that ask the driver to act; the host screen matches on them. */
    const val RADIO_OFF_MARK = "Turn on Bluetooth"
    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}

/**
 * Tells a real RFCOMM link from one a head unit only claims.
 *
 * Reaching a phone takes a page, a service search and a channel setup: hundreds of milliseconds at
 * best. Some head units keep calls and music on the maker's own Bluetooth module and leave Android
 * an adapter whose sockets "connect" at once and never carry a byte. Waiting for iAP2 there tells
 * the driver nothing, and no retry can change it.
 */
object BluetoothLinkCheck {
    /** True for a connect that returned at once and has delivered nothing since. */
    fun looksUnreal(connectMillis: Long, bytesReceived: Long): Boolean =
        connectMillis < INSTANT_CONNECT_MILLIS && bytesReceived == 0L

    /**
     * The control experiment's answer. A real stack has to find a service on the phone before it
     * can connect to it, so a connect to a service no phone offers cannot succeed; where it does,
     * and at once, the adapter is accepting connections that go nowhere.
     */
    fun controlProvesUnreal(controlConnected: Boolean, controlMillis: Long): Boolean =
        controlConnected && controlMillis < INSTANT_CONNECT_MILLIS

    /**
     * A connect that returns sooner than this did not reach a phone. Units whose links are not
     * real answered in 4 to 17 ms; a unit with working Bluetooth never took less than 100 ms.
     */
    const val INSTANT_CONNECT_MILLIS = 50L

    /**
     * How long an instant link may stay silent before the claim is put to a test. A link that
     * fails or ends sooner without a byte is tested at that moment instead.
     */
    const val SILENCE_MILLIS = 12_000L

    /** The words of the failure that reports it; the host screen matches on them. */
    const val UNREAL_MARK = "connections that are not real"

    /**
     * True when Android's Bluetooth service handed BluetoothSocket no socket manager, so no app on
     * the head unit can open an RFCOMM link, whatever the phone does. An Allwinner T7 on Android 9
     * failed every connect this way within 3 ms while the car's own calls and music kept working
     * (#709). Android 9 to 11 call through the missing manager and throw a NullPointerException
     * that names it; Android 13 checks first and throws "bt get socket manager failed".
     */
    fun socketServiceMissing(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(4).any {
            val message = it.message.orEmpty()
            (it is NullPointerException && "IBluetoothSocketManager" in message) ||
                (it is IOException && "get socket manager failed" in message)
        }

    /**
     * What a failed RFCOMM connect is reported as. A missing socket manager cannot be retried
     * away, so it stops the attempts with its cause; anything else stays the IOException it was.
     */
    fun connectFailure(failure: Throwable, address: String): IOException = when {
        socketServiceMissing(failure) -> WirelessStartupException(
            WirelessStartupFailure.BLUETOOTH_UNUSABLE,
            "Android Bluetooth on this head unit $NO_SOCKETS_MARK: its Bluetooth service has no socket manager",
        ).apply { initCause(failure) }
        failure is IOException -> failure
        else -> IOException("Could not connect RFCOMM to $address", failure)
    }

    /** The words of that failure; the host screen matches on them. */
    const val NO_SOCKETS_MARK = "cannot open connections for apps"
}

/**
 * Diagnostic snapshot for Bluetooth state using public Android APIs.
 *
 * Privacy guarantee: Never records MAC addresses, PINs, pairing secrets, or device names.
 */
object BluetoothDiagnosticSnapshot {

    enum class Classification {
        ABSENT,
        TURNING_ON,
        TURNING_OFF,
        DISABLED,
        PERMISSION_ERROR,
        READY,
    }

    data class Snapshot(
        val apiLevel: Int,
        val hasBluetoothManager: Boolean,
        val hasManagerAdapter: Boolean,
        val hasDefaultAdapterFallback: Boolean,
        val adapterStateName: String,
        val adapterStateCode: Int?,
        val isEnabled: Boolean,
        val connectPermissionStatus: String,
        val classification: Classification,
    )

    fun capture(context: Context): Snapshot {
        val apiLevel = Build.VERSION.SDK_INT
        val manager = runCatching { context.getSystemService(BluetoothManager::class.java) }.getOrNull()
        val hasBluetoothManager = manager != null
        val managerAdapter = runCatching { manager?.adapter }.getOrNull()
        val hasManagerAdapter = managerAdapter != null

        @Suppress("DEPRECATION")
        val defaultAdapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val hasDefaultAdapterFallback = defaultAdapter != null

        val effectiveAdapter = managerAdapter ?: defaultAdapter

        val stateCode = runCatching { effectiveAdapter?.state }.getOrNull()
        val stateName = when (stateCode) {
            BluetoothAdapter.STATE_OFF -> "STATE_OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "STATE_TURNING_ON"
            BluetoothAdapter.STATE_ON -> "STATE_ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "STATE_TURNING_OFF"
            null -> if (effectiveAdapter == null) "ABSENT" else "UNAVAILABLE"
            else -> "UNKNOWN($stateCode)"
        }

        val isEnabled = runCatching { effectiveAdapter?.isEnabled == true }.getOrDefault(false)

        val connectPermissionStatus = if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                "GRANTED"
            } else {
                "DENIED"
            }
        } else {
            "NOT_REQUIRED(API $apiLevel)"
        }

        val classification = when {
            effectiveAdapter == null -> Classification.ABSENT
            connectPermissionStatus == "DENIED" -> Classification.PERMISSION_ERROR
            stateCode == BluetoothAdapter.STATE_TURNING_ON -> Classification.TURNING_ON
            stateCode == BluetoothAdapter.STATE_TURNING_OFF -> Classification.TURNING_OFF
            !isEnabled -> Classification.DISABLED
            else -> Classification.READY
        }

        return Snapshot(
            apiLevel = apiLevel,
            hasBluetoothManager = hasBluetoothManager,
            hasManagerAdapter = hasManagerAdapter,
            hasDefaultAdapterFallback = hasDefaultAdapterFallback,
            adapterStateName = stateName,
            adapterStateCode = stateCode,
            isEnabled = isEnabled,
            connectPermissionStatus = connectPermissionStatus,
            classification = classification,
        )
    }

    fun report(context: Context): String {
        val snap = capture(context)

        val presenceDetail = if (snap.classification == Classification.ABSENT) {
            "ABSENT (Standard Bluetooth adapter is unavailable; separate OEM Bluetooth module is a possibility requiring device evidence)"
        } else {
            "PRESENT"
        }

        return buildString {
            appendLine("--- Bluetooth Diagnostic Snapshot ---")
            appendLine("Android API level: ${snap.apiLevel}")
            appendLine("BluetoothManager service: ${if (snap.hasBluetoothManager) "exists" else "null"}")
            appendLine("BluetoothManager adapter: ${if (snap.hasManagerAdapter) "exists" else "null"}")
            appendLine("BluetoothAdapter.getDefaultAdapter(): ${if (snap.hasDefaultAdapterFallback) "exists" else "null"}")
            appendLine("Adapter presence: $presenceDetail")
            appendLine("Adapter state: ${snap.adapterStateName}")
            appendLine("isEnabled: ${snap.isEnabled}")
            appendLine("BLUETOOTH_CONNECT permission: ${snap.connectPermissionStatus}")
            appendLine("Classification: ${snap.classification}")
        }
    }
}
