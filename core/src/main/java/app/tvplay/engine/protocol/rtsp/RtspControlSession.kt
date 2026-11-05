package app.tvplay.engine.protocol.rtsp

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.auth.AirPlayCrypto
import app.tvplay.engine.auth.ControlCipher
import app.tvplay.engine.auth.DuplexByteStream
import app.tvplay.engine.auth.MfiAuthProvider
import app.tvplay.engine.auth.MfiSapHandshake
import app.tvplay.engine.auth.PairSetup
import app.tvplay.engine.auth.PairVerify
import app.tvplay.engine.auth.PairingRegistry
import app.tvplay.engine.auth.toHexString
import app.tvplay.engine.av.ViewportTouchContact
import app.tvplay.engine.session.TvStreamServerConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

typealias AirPlaySession = RtspControlSession
typealias RtspSessionListener = AirPlaySessionListener
typealias RtspMediaHandler = AirPlayMediaHandler

data class AirPlayDeviceInfo(
    val name: String,
    val deviceId: String,
    val wifiMac: String,
    val model: String,
)

/** Per-bind listener identity used to isolate stale accepts and teardowns. */
class AirPlayListenerIdentity(val generation: Int) {
    val id: Long = nextId.incrementAndGet()

    private companion object {
        val nextId = AtomicLong()
    }
}

data class AirPlayTcpAccepted(
    val listener: AirPlayListenerIdentity,
    val internal: Boolean,
    val acceptedAtNanos: Long,
)

fun isInternalAirPlayPeer(peer: InetAddress, local: InetAddress): Boolean {
    if (peer.isLoopbackAddress || peer == local) return true
    val peerHost = peer.hostAddress?.substringBefore('%')?.removePrefix("::ffff:")
    val localHost = local.hostAddress?.substringBefore('%')?.removePrefix("::ffff:")
    if (peerHost != null && peerHost.equals(localHost, ignoreCase = true)) return true
    return runCatching { NetworkInterface.getByInetAddress(peer) != null }.getOrDefault(false)
}

internal fun isSameOrAuthorizedAirPlayPeer(candidate: InetAddress?, expected: InetAddress): Boolean {
    if (candidate == null) return false
    if (candidate == expected) return true
    val candidateHost = candidate.hostAddress?.substringBefore('%')?.removePrefix("::ffff:")
    val expectedHost = expected.hostAddress?.substringBefore('%')?.removePrefix("::ffff:")
    if (candidateHost != null && candidateHost.equals(expectedHost, ignoreCase = true)) return true
    if (candidate.isLoopbackAddress ||
        runCatching { NetworkInterface.getByInetAddress(candidate) != null }.getOrDefault(false)
    ) {
        return false
    }
    val candidateLocalPeer = candidate.isLinkLocalAddress || candidate.isSiteLocalAddress
    val expectedLocalPeer = expected.isLinkLocalAddress || expected.isSiteLocalAddress
    return candidateLocalPeer && expectedLocalPeer
}

/**
 * Accepts until a connection comes from [expected], the control-session peer; others are closed unread.
 */
internal fun ServerSocket.acceptFrom(expected: InetAddress, onRejected: (InetAddress?) -> Unit = {}): Socket {
    while (true) {
        val socket = accept()
        if (isSameOrAuthorizedAirPlayPeer(socket.inetAddress, expected)) return socket
        onRejected(socket.inetAddress)
        runCatching { socket.close() }
    }
}

/** Only fixed protocol routes/methods and byte counts survive into the diagnostic report. */
object AirPlayControlDiagnostics {
    fun request(method: String, path: String, bytes: Int): String {
        val verb = method.takeIf {
            it in setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER")
        } ?: "OTHER"
        val route = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            .takeIf { it in setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup") } ?: "other"
        return "airplay control request method=$verb route=$route contentBytes=$bytes"
    }
}

/** Only product/version tokens, never the phone's name, addresses or pairing identifiers. */
object AirPlayPeerDiagnostics {
    private val modelToken = Regex("(?:iPhone|iPad|iPod)[0-9]{1,3},[0-9]{1,3}")
    private val versionToken = Regex("[0-9][0-9A-Za-z._-]{0,39}")

    fun summary(model: String, osVersion: String, sourceVersion: String): String {
        fun version(value: String) = value.takeIf(versionToken::matches) ?: "not_reported"
        return "Phone display peer model=${model.takeIf(modelToken::matches) ?: "not_reported"} " +
            "ios=${version(osVersion)} airplay=${version(sourceVersion)}"
    }
}

/** Session lifecycle and command callbacks for the driver/UI layer. */
interface AirPlaySessionListener {
    fun onTcpAccepted(event: AirPlayTcpAccepted) {}
    fun onSessionActive(session: RtspControlSession) {}
    fun onSessionEnded(session: RtspControlSession) {}
    fun onVideoFrameRendered(session: RtspControlSession) {}
    fun onTransportError(message: String) {}
    fun onDeviceInfo(session: RtspControlSession, info: AirPlayDeviceInfo) {}
    fun onHostUiRequested(session: RtspControlSession) {}
    fun onCommand(session: RtspControlSession, type: String, params: Map<String, Any?>) {}
    val wantsTrace: Boolean get() = true
    fun onDebugLog(message: String) {}
}

/** Stream transport seam; media decode/render is supplied by [CarPlayMediaEngine]. */
interface AirPlayMediaHandler {
    fun onScreen(session: RtspControlSession, type: Int, stream: Map<String, Any?>): Int? = null
    fun onAudio(session: RtspControlSession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onDataStream(session: RtspControlSession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onBufferedAudio(session: RtspControlSession, stream: Map<String, Any?>): Map<String, Any?>? = null
    fun onBufferedAudioControl(session: RtspControlSession, method: String, body: Map<String, Any?>): Map<String, Any?>? = null
    fun onFeedback(session: RtspControlSession): Map<String, Any?>? = null
    fun onTeardown(session: RtspControlSession, type: Int) {}
    fun onSessionClosed(session: RtspControlSession) {}
    fun setIapTunnelHandler(handler: ((DuplexByteStream) -> Boolean)?) {}
    fun onSetupResponseSent(session: RtspControlSession) {}
    fun onAudioDuck(session: RtspControlSession, ducked: Boolean) {}
}

/**
 * One CarPlay AirPlay RTSP control connection.
 *
 * Owns RTSP framing, pairing/auth/info routing, the encrypted event channel used for HID input,
 * stream SETUP/TEARDOWN routing, the NTP timing exchange, and the keep-alive socket.
 */
class RtspControlSession(
    private val socket: Socket,
    private val config: TvStreamServerConfig,
    private val identity: AccessoryIdentity,
    private val pairings: PairingRegistry,
    private val mfi: MfiAuthProvider?,
    private val listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
) : Closeable {
    internal val pairSetup = PairSetup(identity, pairings)
    internal val pairVerify = PairVerify(identity, pairings)
    internal var cipher: ControlCipher? = null
    internal var encBuf = ByteArray(0)
    internal var deviceBtMac = ""
    internal val activeStreams = linkedSetOf<Int>()

    @Volatile private var mainScreenToken: Any? = null
    @Volatile private var mainFrameRendered = false
    val hasRenderedMainFrame: Boolean get() = mainFrameRendered && !closed.get()

    private val closed = AtomicBoolean(false)
    internal val isClosed: Boolean get() = closed.get()
    private val notified = AtomicBoolean(false)
    private var eventServer: ServerSocket? = null
    private var eventSocket: Socket? = null
    private var eventCipher: ControlCipher? = null
    private var eventCseq = 0
    private var pendingNightMode: Boolean? = null
    private val firstTouchSendLogged = AtomicBoolean(false)
    private val touchSendFailureLogged = AtomicBoolean(false)
    private val touchSlots = AirPlayTouchSlots(AirPlayHid.TOUCH_CONTACTS)
    private val ntp = NtpClock()
    private var keepAliveSocket: DatagramSocket? = null
    private var keepAliveThread: Thread? = null
    private val eventWriteLock = Any()
    private val eventThreads = CopyOnWriteArrayList<Thread>()

    val host: String = socket.inetAddress?.hostAddress ?: ""
    val localAddress: InetAddress? = socket.localAddress
    private val peerAddress: InetAddress? = socket.inetAddress
    internal val remoteAddress: InetAddress?
        get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address
    val controllerId: String? get() = pairVerify.verifiedControllerId
    val sharedSecret: ByteArray? get() = pairVerify.shared?.copyOf()

    fun syncedNtp(): BigInteger = ntp.syncedNtp()

    internal fun logDebug(message: String) = debugLog(message)

    internal fun videoFrameRendered() {
        if (closed.get()) return
        mainFrameRendered = true
        listener.onVideoFrameRendered(this)
    }

    internal fun logTrace(message: String) = trace { message }

    fun start() {
        Thread(::runControl, "airplay-control").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mainScreenToken = null
        safeClose(socket)
        try {
            media.onSessionClosed(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay media stream teardown failed", error)
        }
        teardown()
        if (notified.compareAndSet(false, true)) listener.onSessionEnded(this)
    }

    /** Stable identity of the usable primary screen; replaced on SETUP, absent after teardown/close. */
    fun mainScreenSessionToken(): Any? = if (closed.get()) null else mainScreenToken

    fun sendCommand(command: Map<String, Any?>): Boolean = synchronized(eventWriteLock) {
        sendCommandLocked(command)
    }

    private fun sendCommandLocked(command: Map<String, Any?>, extraHeaders: String = ""): Boolean {
        val socket = eventSocket ?: return false
        val cipher = eventCipher ?: return false
        eventCseq++
        val body = BplistCodec.encode(command)
        val head = "POST /command RTSP/1.0\r\n" + extraHeaders +
            "Content-Type: $PLIST_CONTENT_TYPE\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "CSeq: $eventCseq\r\n\r\n"
        trace { "airplay event tx headers=$head bodyHex=${body.toHexString()}" }
        return try {
            val bytes = cipher.encrypt(head.toByteArray(Charsets.US_ASCII) + body)
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
            true
        } catch (error: Exception) {
            Log.w(TAG, "airplay event command failed type=${command["type"]}", error)
            close()
            false
        }
    }

    /** [contacts] carry Android pointer ids; an empty list releases every finger. */
    fun sendTouch(contacts: List<ViewportTouchContact>): Boolean {
        val scaled = touchSlots.assign(contacts).map {
            it.copy(x = it.x * config.main.widthPixels, y = it.y * config.main.heightPixels)
        }
        val report = AirPlayHid.touchReport(scaled)
        val sent = sendHidReport(AirPlayHid.TOUCH_HID_UID, report)
        if (sent && firstTouchSendLogged.compareAndSet(false, true)) {
            val first = scaled.firstOrNull()
            Log.i(
                TAG,
                "airplay touch report sent contacts=${scaled.size} first=" +
                    "(${first?.x},${first?.y},down=${first?.down}) report=${report.toHexString()}",
            )
        } else if (!sent && touchSendFailureLogged.compareAndSet(false, true)) {
            Log.w(TAG, "airplay touch dropped: event channel is not ready")
        }
        return sent
    }

    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true) {
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(state))
        if (momentary) sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState()))
    }

    fun sendKnobSelect(down: Boolean): Boolean =
        sendHidReport(AirPlayHid.KNOB_HID_UID, AirPlayHid.knobReport(AirPlayKnobState(select = down)))

    fun sendMedia(index: Int) {
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(index))
        sendHidReport(AirPlayHid.MEDIA_HID_UID, AirPlayHid.mediaReport(0))
    }

    fun sendTelephony(index: Int) {
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(index))
        sendHidReport(AirPlayHid.TELEPHONY_HID_UID, AirPlayHid.telephonyReport(0))
    }

    fun invokeSiri() {
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 2)))
        sendCommand(linkedMapOf("type" to "requestSiri", "params" to linkedMapOf("siriAction" to 3)))
    }

    fun sendIapMessage(data: ByteArray, timeoutMillis: Long = 0L): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val command = linkedMapOf<String, Any?>(
            "type" to "iAPSendMessage",
            "params" to linkedMapOf("data" to data),
        )
        if (timeoutMillis == 0L) return sendCommand(command)

        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            val sent = synchronized(eventWriteLock) {
                if (eventSocket == null || eventCipher == null) {
                    null
                } else {
                    sendCommandLocked(command)
                }
            }
            if (sent != null) return sent
            if (closed.get()) return false

            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) return false
            val sleepMillis = minOf(
                EVENT_READY_POLL_MILLIS,
                (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND,
            ).coerceAtLeast(1)
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    fun setNightMode(night: Boolean): Boolean = synchronized(eventWriteLock) {
        pendingNightMode = night
        sendPendingNightModeLocked()
    }

    private fun sendPendingNightModeLocked(): Boolean {
        val night = pendingNightMode ?: return true
        val sent = sendCommandLocked(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to night)),
        )
        if (sent) pendingNightMode = null
        return sent
    }

    private fun sendHidReport(uid: Int, report: ByteArray): Boolean =
        sendCommand(
            linkedMapOf(
                "type" to "hidSendReport",
                "uuid" to uid.toString(16),
                "hidReport" to report,
            ),
        )

    private fun runControl() {
        configureTcpLiveness(socket) { debugLog(it) }
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        var accumulated = ByteArray(0)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        var closeReason = "session closed"
        try {
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) {
                    closeReason = "peer EOF"
                    break
                }
                var plaintext = buffer.copyOf(count)
                val activeCipher = cipher
                if (activeCipher != null) {
                    encBuf += plaintext
                    val decrypted = try {
                        activeCipher.decrypt(encBuf)
                    } catch (error: Exception) {
                        closeReason = "control decrypt failed: ${error.message ?: error.javaClass.simpleName}"
                        Log.e(TAG, "airplay $closeReason encrypted=${encBuf.size}", error)
                        break
                    }
                    encBuf = decrypted.rest
                    plaintext = decrypted.data
                }
                accumulated += plaintext
                val parsed = RtspMessageCodec.parseMessages(accumulated)
                accumulated = parsed.rest
                for (request in parsed.messages) {
                    val cseq = request.headers["cseq"] ?: "-"
                    val path = request.path.lowercase()
                    val showInDebugOverlay =
                        !path.endsWith("/feedback") &&
                            !(request.method == "POST" && path.endsWith("/command"))
                    debugLog(
                        "airplay rx ${request.method} ${request.path} cseq=$cseq body=${request.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) {
                        debugLog(
                            AirPlayControlDiagnostics.request(request.method, request.path, request.body.size),
                            false,
                        )
                    }
                    trace {
                        "airplay control rx headers=${request.headers} " +
                            "bodyHex=${request.body.toHexString()}"
                    }
                    val response = try {
                        handle(request)
                    } catch (error: Exception) {
                        Log.e(
                            TAG,
                            "airplay handler failed ${request.method} ${request.path} cseq=$cseq",
                            error,
                        )
                        RtspMessageCodec.Response(status = 500)
                    }
                    debugLog(
                        "airplay tx status=${response.status ?: 200} cseq=$cseq body=${response.body.size}",
                        showInDebugOverlay,
                    )
                    if (showInDebugOverlay) {
                        debugLog(
                            "airplay control response status=${response.status ?: 200} contentBytes=${response.body.size}",
                            false,
                        )
                    }
                    val wire = RtspMessageCodec.buildResponse(request, response)
                    trace { "airplay control tx wireHex=${wire.toHexString()}" }
                    output.write(cipher?.encrypt(wire) ?: wire)
                    if (cipher == null && pairVerify.controlKeys != null) {
                        val keys = pairVerify.controlKeys!!
                        cipher = ControlCipher(keys.readKey, keys.writeKey)
                        debugLog("airplay control encryption enabled")
                    }
                }
                output.flush()
                notifySetupResponseSent()
            }
        } catch (error: Exception) {
            closeReason = "control I/O failed: ${error.message ?: error.javaClass.simpleName}"
            if (!closed.get()) Log.e(TAG, "airplay $closeReason", error)
        } finally {
            debugLog("airplay control closing reason=$closeReason activeStreams=$activeStreams")
            close()
        }
    }

    private fun handle(request: RtspMessageCodec.Request): RtspMessageCodec.Response {
        when (request.method) {
            "SETUP" -> return handleSetup(request)
            "SET_PARAMETER" -> return RtspMessageCodec.Response(status = 200)
            "RECORD" -> {
                listener.onSessionActive(this)
                return RtspMessageCodec.Response(status = 200)
            }
            "TEARDOWN" -> return handleTeardown(request)
        }

        if (config.bufferedAudioOutputEnabled && request.method in BUFFERED_AUDIO_METHODS) {
            val body = if (request.body.isEmpty() && request.method == "GETANCHOR") {
                emptyMap()
            } else {
                runCatching { asMap(BplistCodec.decode(request.body)) }.getOrNull()
                    ?: return RtspMessageCodec.Response(status = 400)
            }
            val response = media.onBufferedAudioControl(this, request.method, body)
            if (request.method != "GETANCHOR") debugLog("airplay ${request.method} $body -> ${response ?: "ok"}")
            return if (response == null) {
                RtspMessageCodec.Response(status = 200)
            } else {
                RtspMessageCodec.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(response),
                )
            }
        }

        val path = request.path.lowercase()
        return when {
            path.endsWith("/pair-setup") -> RtspMessageCodec.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairSetup.handle(request.body),
            )
            path.endsWith("/pair-verify") -> RtspMessageCodec.Response(
                headers = mapOf("Content-Type" to PAIRING_CONTENT_TYPE),
                body = pairVerify.handle(request.body),
            )
            path.endsWith("/auth-setup") -> {
                val body = mfi?.let { provider ->
                    runCatching { MfiSapHandshake(provider).handleAuthSetup(request.body) }.getOrNull()
                }
                if (body == null || body.isEmpty()) {
                    RtspMessageCodec.Response(status = 400)
                } else {
                    RtspMessageCodec.Response(
                        headers = mapOf("Content-Type" to OCTET_CONTENT_TYPE),
                        body = body,
                    )
                }
            }
            path.endsWith("/info") -> {
                val info = RtspInfoPayload.build(config)
                if (request.body.isNotEmpty()) {
                    val requestInfo = try {
                        BplistCodec.decode(request.body).toString()
                    } catch (_: Exception) {
                        "<unparseable ${request.body.size} bytes>"
                    }
                    debugLog("airplay /info request=$requestInfo")
                }
                Log.i(
                    TAG,
                    "airplay /info features=${info["features"]} " +
                        "audioFormats=${(info["audioFormats"] as? List<*>)?.size ?: 0} " +
                        "audioLatencies=${(info["audioLatencies"] as? List<*>)?.size ?: 0}",
                )
                debugLog("airplay /info displays=${info["displays"]}")
                RtspMessageCodec.Response(
                    headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                    body = BplistCodec.encode(info),
                )
            }
            request.method == "POST" && path.endsWith("/command") -> handleCommand(request)
            request.method == "POST" && path.endsWith("/feedback") -> {
                val body = media.onFeedback(this)
                if (body == null) {
                    RtspMessageCodec.Response(status = 200)
                } else {
                    RtspMessageCodec.Response(
                        headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
                        body = BplistCodec.encode(body),
                    )
                }
            }
            else -> RtspMessageCodec.Response(status = 200)
        }
    }

    private fun notifySetupResponseSent() {
        try {
            media.onSetupResponseSent(this)
        } catch (error: Exception) {
            Log.w(TAG, "airplay SETUP response callback failed", error)
        }
    }

    private fun debugLog(message: String, uiVisible: Boolean = true) {
        Log.i(TAG, message)
        if (!uiVisible) return
        try {
            listener.onDebugLog(message)
        } catch (error: Exception) {
            Log.w(TAG, "debug log callback failed", error)
        }
    }

    private inline fun trace(message: () -> String) {
        if (!listener.wantsTrace) return
        try {
            listener.onDebugLog("TRACE ${message()}")
        } catch (error: Exception) {
            Log.w(TAG, "trace log callback failed", error)
        }
    }

    private fun handleSetup(request: RtspMessageCodec.Request): RtspMessageCodec.Response {
        val dict = try {
            asMap(BplistCodec.decode(request.body)) ?: return RtspMessageCodec.Response(status = 400)
        } catch (error: Exception) {
            Log.e(TAG, "airplay SETUP plist decode failed body=${request.body.size}", error)
            return RtspMessageCodec.Response(status = 400)
        }
        debugLog("airplay SETUP keys=${dict.keys.sorted()}")
        val streams = dict["streams"] as? List<*>
        if (streams != null) {
            val responseStreams = handleStreams(streams)
            debugLog("airplay SETUP response streams=$responseStreams")
            val body = BplistCodec.encode(linkedMapOf("streams" to responseStreams))
            trace { "airplay SETUP response bplistHex=${body.toHexString()}" }
            return RtspMessageCodec.Response(headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE), body = body)
        }

        val name = string(dict["name"])
        val deviceId = string(dict["deviceID"])
        val wifiMac = string(dict["macAddress"]).lowercase()
        val model = string(dict["model"])
        debugLog(AirPlayPeerDiagnostics.summary(model, string(dict["osVersion"]), string(dict["sourceVersion"])))
        if (deviceId.isNotEmpty()) deviceBtMac = deviceId
        if (name.isNotEmpty() || deviceId.isNotEmpty() || wifiMac.isNotEmpty()) {
            listener.onDeviceInfo(this, AirPlayDeviceInfo(name, deviceId, wifiMac, model))
        }

        val peerTimingPort = long(dict["timingPort"])?.toInt() ?: 0
        val response = linkedMapOf<String, Any?>(
            "timingPort" to openTiming(peerTimingPort),
            "eventPort" to openEvent(),
        )
        if (dict["keepAliveLowPower"] == true || dict["keepAliveLowPower"] == 1L) {
            response["keepAlivePort"] = openKeepAlive()
        }
        response["enabledFeatures"] = setupEnabledFeatures(config, dict["features"] as? List<*>)
        return RtspMessageCodec.Response(
            headers = mapOf("Content-Type" to PLIST_CONTENT_TYPE),
            body = BplistCodec.encode(response),
        )
    }

    private fun handleStreams(streams: List<*>): List<Any?> {
        val result = arrayListOf<Any?>()
        for (entry in streams) {
            val stream = asMap(entry) ?: continue
            val type = long(stream["type"])?.toInt() ?: continue
            debugLog("airplay SETUP stream type=$type payload=$stream")
            when (type) {
                STREAM_TYPE_MAIN_SCREEN -> {
                    val port = media.onScreen(this, type, stream)
                    debugLog("airplay screen stream type=$type dataPort=${port ?: "rejected"}")
                    if (port != null) {
                        activeStreams.add(type)
                        mainScreenToken = Any()
                        result.add(linkedMapOf("type" to type, "dataPort" to port))
                    }
                }
                STREAM_TYPE_REALTIME_AUDIO,
                STREAM_TYPE_MAIN_AUDIO,
                STREAM_TYPE_ALT_AUDIO,
                STREAM_TYPE_MAIN_HIGH_AUDIO,
                -> {
                    val streamResponse = media.onAudio(this, type, stream)
                    debugLog(
                        "airplay audio stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"} " +
                            "controlPort=${streamResponse?.get("controlPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_MAIN_BUFFERED_AUDIO -> {
                    val streamResponse = if (config.bufferedAudioOutputEnabled) media.onBufferedAudio(this, stream) else null
                    debugLog(
                        "airplay buffered audio stream accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                STREAM_TYPE_DATA -> {
                    val streamResponse = media.onDataStream(this, stream)
                    debugLog(
                        "airplay data stream type=$type accepted=${streamResponse != null} " +
                            "dataPort=${streamResponse?.get("dataPort") ?: "none"}",
                    )
                    if (streamResponse != null) {
                        activeStreams.add(type)
                        result.add(streamResponse)
                    }
                }
                else -> debugLog("airplay unsupported stream type=$type")
            }
        }
        return result
    }

    private fun handleCommand(request: RtspMessageCodec.Request): RtspMessageCodec.Response {
        val body = try {
            asMap(BplistCodec.decode(request.body)) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap<String, Any?>()
        }
        val type = string(body["type"])
        val params = asMap(body["params"]) ?: emptyMap()
        debugLog("airplay command type=$type keys=${params.keys.sorted()}")
        when (type) {
            "duckAudio" -> media.onAudioDuck(this, true)
            "unduckAudio" -> media.onAudioDuck(this, false)
            "requestUI" -> listener.onHostUiRequested(this)
        }
        listener.onCommand(this, type, params)
        return RtspMessageCodec.Response(status = 200)
    }

    private fun handleTeardown(request: RtspMessageCodec.Request): RtspMessageCodec.Response {
        val decodedBody = try {
            BplistCodec.decode(request.body)
        } catch (_: Exception) {
            null
        }
        val types = teardownStreamTypes(decodedBody)

        debugLog(
            "airplay TEARDOWN types=${types ?: "all"} activeBefore=$activeStreams " +
                "body=${request.body.size} bytes payload=$decodedBody",
        )
        trace { "airplay TEARDOWN raw${request.body.size}Hex=${request.body.toHexString()}" }

        if (types == null || STREAM_TYPE_MAIN_SCREEN in types) mainScreenToken = null
        if (types == null) {
            activeStreams.toList().forEach { media.onTeardown(this, it) }
            activeStreams.clear()
        } else {
            types.forEach { type -> if (activeStreams.remove(type)) media.onTeardown(this, type) }
        }
        return RtspMessageCodec.Response(status = 200)
    }

    fun hasActiveMediaStreams(): Boolean =
        activeStreams.any { it != STREAM_TYPE_DATA }

    private fun openTiming(peerPort: Int): Int {
        val port = ntp.listen()
        if (peerPort > 0) peerAddress?.let { ntp.start(it, peerPort) }
        return port
    }

    private fun openKeepAlive(): Int {
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("::"), 0))
        keepAliveSocket = socket
        keepAliveThread = Thread({ runKeepAlive(socket) }, "airplay-keepalive").apply {
            isDaemon = true
            start()
        }
        return socket.localPort
    }

    private fun runKeepAlive(socket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!closed.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: Exception) {
                if (closed.get()) return
            }
        }
    }

    private fun openEvent(): Int {
        val server = ServerSocket(0, 50, InetAddress.getByName("::"))
        eventServer = server
        spawnEvent("airplay-event-accept") { acceptEvent(server) }
        return server.localPort
    }

    private fun teardown() {
        ntp.close()
        safeClose(keepAliveSocket)
        keepAliveSocket = null
        keepAliveThread?.interrupt()
        keepAliveThread = null
        safeClose(eventServer)
        eventServer = null
        safeClose(eventSocket)
        eventSocket = null
        eventCipher = null
        eventThreads.forEach { it.interrupt() }
        eventThreads.clear()
    }

    private fun acceptEvent(server: ServerSocket) {
        try {
            val expected = peerAddress ?: throw IOException("control peer unknown")
            val socket = server.acceptFrom(expected) { debugLog("airplay event rejected sender outside the control session") }
            socket.setSoLinger(true, 0)
            socket.tcpNoDelay = true
            debugLog("airplay event connection accepted from ${socket.remoteSocketAddress} noDelay=${socket.tcpNoDelay}")
            eventSocket = socket
            val shared = pairVerify.shared
            if (shared == null) {
                Log.e(TAG, "airplay event rejected: pair-verify shared secret unavailable")
                safeClose(socket)
                close()
                return
            }
            val writeKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".toByteArray(Charsets.US_ASCII),
                "Events-Write-Encryption-Key".toByteArray(Charsets.US_ASCII),
                32,
            )
            val readKey = AirPlayCrypto.hkdfSha512(
                shared,
                "Events-Salt".toByteArray(Charsets.US_ASCII),
                "Events-Read-Encryption-Key".toByteArray(Charsets.US_ASCII),
                32,
            )
            eventCipher = ControlCipher(readKey, writeKey)
            synchronized(eventWriteLock) {
                sendPendingNightModeLocked()
            }
            runEventRead(socket)
        } catch (error: Exception) {
            if (!closed.get()) {
                Log.e(TAG, "airplay event accept failed", error)
                close()
            }
        }
    }

    private fun runEventRead(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            var encrypted = ByteArray(0)
            var plaintext = ByteArray(0)
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val count = input.read(buffer)
                if (count < 0) break
                val cipher = eventCipher ?: break
                encrypted += buffer.copyOf(count)
                val decrypted = try {
                    cipher.decrypt(encrypted)
                } catch (error: Exception) {
                    Log.e(TAG, "airplay event decrypt failed encrypted=${encrypted.size}", error)
                    break
                }
                encrypted = decrypted.rest
                plaintext += decrypted.data
                val parsed = RtspMessageCodec.parseMessages(plaintext)
                plaintext = parsed.rest
                for (message in parsed.messages) {
                    if (message.method.startsWith("RTSP/") || message.method.startsWith("HTTP/")) continue
                    debugLog(
                        "airplay event rx ${message.method} ${message.path} cseq=${message.headers["cseq"] ?: "-"} body=${message.body.size}",
                    )
                    val response = RtspMessageCodec.buildResponse(message, RtspMessageCodec.Response(status = 200))
                    trace {
                        "airplay event rx headers=${message.headers} " +
                            "bodyHex=${message.body.toHexString()}"
                    }
                    trace { "airplay event tx wireHex=${response.toHexString()}" }
                    synchronized(eventWriteLock) {
                        output.write(cipher.encrypt(response))
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            if (!closed.get()) Log.e(TAG, "airplay event read failed", error)
        } finally {
            debugLog("airplay event connection closed")
            if (eventSocket === socket) eventSocket = null
            eventCipher = null
            safeClose(socket)
            if (!closed.get()) close()
        }
    }

    private fun spawnEvent(name: String, body: () -> Unit) {
        val thread = Thread(body, name).apply { isDaemon = true }
        eventThreads.add(thread)
        thread.start()
    }

    private companion object {
        const val TAG = "TVPlay"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        const val PAIRING_CONTENT_TYPE = "application/pairing+tlv8"
        const val OCTET_CONTENT_TYPE = "application/octet-stream"

        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_REALTIME_AUDIO = 96
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_ALT_AUDIO = 101
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_MAIN_BUFFERED_AUDIO = 103
        const val STREAM_TYPE_DATA = 130
        private val BUFFERED_AUDIO_METHODS = setOf("SETRATE", "SETRATEANCHORTIME", "GETANCHOR", "FLUSHBUFFERED")

        const val READ_CHUNK_BYTES = 16 * 1024
        const val EVENT_READY_POLL_MILLIS = 25L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

internal const val MAIN_BUFFERED_FEATURE = "mainBuffered"

internal fun setupEnabledFeatures(config: TvStreamServerConfig, proposed: List<*>?): List<String> {
    val features = mutableListOf<String>()
    if (config.hevc) features.add("hevc")
    features.add("iAPChannel")
    features.add("viewAreas")
    if (config.bufferedAudioOutputEnabled && proposed.orEmpty().contains(MAIN_BUFFERED_FEATURE)) {
        features.add(MAIN_BUFFERED_FEATURE)
    }
    return features
}

internal fun teardownStreamTypes(body: Any?): List<Int>? {
    val entries = ((body as? Map<*, *>)?.get("streams") as? List<*>).orEmpty().mapNotNull { entry ->
        val stream = entry as? Map<*, *> ?: return@mapNotNull null
        val type = (stream["type"] as? Number)?.toInt() ?: return@mapNotNull null
        type to (stream["streamID"] as? Number)?.toLong()
    }
    if (entries.isEmpty()) return null
    return entries.filterNot { (type, streamId) -> type == 130 && streamId != null && streamId != 1L }.map { it.first }
}

internal fun safeClose(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: Exception) {
        // Best-effort close.
    }
}

private fun configureTcpLiveness(socket: Socket, diagnostic: (String) -> Unit) {
    socket.keepAlive = true
    if (Build.VERSION.SDK_INT < 26) {
        diagnostic("TCP peer health tuning unavailable: API ${Build.VERSION.SDK_INT}")
        return
    }
    try {
        if (Build.VERSION.SDK_INT >= 29) {
            ParcelFileDescriptor.fromSocket(socket).use { duplicate -> tuneTcpKeepAlive(duplicate.fileDescriptor) }
        } else {
            tuneTcpKeepAlive(socket.javaClass.getMethod("getFileDescriptor\$").invoke(socket) as FileDescriptor)
        }
        diagnostic("TCP peer health enabled idle=30s interval=5s count=6 deadline=60000ms; no video-idle timeout")
    } catch (failure: Exception) {
        diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
    }
}

@RequiresApi(26)
private fun tuneTcpKeepAlive(fd: FileDescriptor) {
    // Linux UAPI <linux/tcp.h>
    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 4, 30)
    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 5, 5)
    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 6, 6)
    Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, 60_000)
}

private fun asMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    val result = LinkedHashMap<String, Any?>(map.size)
    for ((key, entry) in map) result[key.toString()] = entry
    return result
}

private fun string(value: Any?): String = value as? String ?: ""

private fun long(value: Any?): Long? = (value as? Number)?.toLong()
