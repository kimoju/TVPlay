package app.tvplay.engine.protocol.carlink

import app.tvplay.engine.av.MAIN_SCREEN_TYPE
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.protocol.rtsp.AudioCodecKind
import app.tvplay.engine.protocol.rtsp.AudioFormat
import app.tvplay.engine.protocol.rtsp.AudioStreamId
import app.tvplay.engine.protocol.rtsp.MediaSink
import app.tvplay.engine.protocol.rtsp.VideoCodec
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ICCOA CarLink multi-channel session host (`AUTH 57209`, `CERT 57249`, `CONTROL 57219`,
 * `VIDEO 57259`, `MEDIA 57229`, `UIBC 4321`, `RTSP 7236`, `RTP 15550`).
 *
 * Bridges Xiaomi HyperOS CarWith, vivo Jovi InCar, and OPPO/OnePlus/Realme Car+ video, audio,
 * and touch/remote events directly into TVPlay's hardware-accelerated [MediaSink]
 * (`TvAvRenderPipeline`).
 */
class CarLinkControlSession(
    private val displaySpec: TvDisplaySpec,
    private val carBrMac: ByteArray,
    private val pinCodeProvider: () -> String,
    private val certBytes: ByteArray = ByteArray(0),
    private val hevcEnabled: Boolean = true,
    private val mediaSink: MediaSink? = null,
    private val onSessionConnected: (String) -> Unit = {},
    private val onSessionDisconnected: () -> Unit = {},
    private val onMusicMetadata: (CarLinkProtoCodec.NotifyMusicInfoMsg) -> Unit = {},
    private val onLog: (String) -> Unit = {},
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val seqCounter = AtomicInteger(1)
    private val serverSockets = CopyOnWriteArrayList<ServerSocket>()
    private val activeSockets = CopyOnWriteArrayList<Socket>()
    private val workerThreads = CopyOnWriteArrayList<Thread>()
    private val rtpUdpSockets = CopyOnWriteArrayList<DatagramSocket>()

    @Volatile
    private var handshakeSession: CarLinkAuthCipher.ServerHandshakeSession? = null

    @Volatile
    private var negotiatedWorkModes: Int = 1

    @Volatile
    private var controlOutput: OutputStream? = null

    @Volatile
    private var uibcOutput: OutputStream? = null

    @Volatile
    private var videoConfigured = false

    @Volatile
    private var audioConfigured = false

    private val audioStreamId = AudioStreamId(type = 100, audioType = "media")
    private val audioFormat = AudioFormat(
        codec = AudioCodecKind.LPCM,
        sampleRate = 48_000,
        channels = 2,
        payloadType = 100,
        audioType = "media",
    )
    private val audioSampleCounter = AtomicInteger(0)

    @Volatile
    var connectedPhoneModel: String = ""
        private set

    fun isActive(): Boolean = !closed.get() && (controlOutput != null || videoConfigured)

    fun start(bindAddress: InetAddress? = null) {
        if (closed.get()) return
        startTcpChannel(CarLinkChannelType.AUTH, bindAddress) { socket -> handleAuthSocket(socket) }
        startTcpChannel(CarLinkChannelType.CERT, bindAddress) { socket -> handleCertSocket(socket) }
        startTcpChannel(CarLinkChannelType.CONTROL, bindAddress) { socket -> handleControlSocket(socket) }
        startTcpChannel(CarLinkChannelType.SENSOR, bindAddress) { socket -> handleSensorSocket(socket) }
        startTcpChannel(CarLinkChannelType.VIDEO, bindAddress) { socket -> handleVideoSocket(socket) }
        startTcpChannel(CarLinkChannelType.MEDIA, bindAddress) { socket -> handleMediaSocket(socket) }
        startTcpChannel(CarLinkChannelType.UIBC, bindAddress) { socket -> handleUibcSocket(socket) }
        startTcpChannel(CarLinkChannelType.RTSP, bindAddress) { socket -> handleWfdRtspSocket(socket) }
        startRtpUdpReceiver(bindAddress, WFD_RTP_PORT)
        startRtpUdpReceiver(bindAddress, CarLinkChannelType.RTP.port)
        onLog(
            "CarLink session listeners started on ports " +
                "AUTH=${CarLinkChannelType.AUTH.port}, CERT=${CarLinkChannelType.CERT.port}, " +
                "CONTROL=${CarLinkChannelType.CONTROL.port}, SENSOR=${CarLinkChannelType.SENSOR.port}, " +
                "VIDEO=${CarLinkChannelType.VIDEO.port}, MEDIA=${CarLinkChannelType.MEDIA.port}, " +
                "UIBC=${CarLinkChannelType.UIBC.port}, RTSP=${CarLinkChannelType.RTSP.port}, " +
                "RTP=$WFD_RTP_PORT/${CarLinkChannelType.RTP.port}",
        )
    }

    /**
     * Initiates outbound TCP client channels (`startUCarChannel(peerAddress)` in `UCarAdapter`)
     * to the phone's `CONTROL (57219)`, `CERT (57249)`, `MEDIA (57229)`, `SENSOR (57239)`,
     * `UIBC (4321)`, and `RTSP (7236)` servers after `AuthConfirm`.
     */
    fun connectToPeer(peerAddress: String, wfdPort: Int = CarLinkChannelType.RTSP.port) {
        if (closed.get() || peerAddress.isBlank()) return
        connectOutboundChannel(CarLinkChannelType.CERT, peerAddress, CarLinkChannelType.CERT.port) { handleCertSocket(it) }
        connectOutboundChannel(CarLinkChannelType.CONTROL, peerAddress, CarLinkChannelType.CONTROL.port) { handleControlSocket(it) }
        connectOutboundChannel(CarLinkChannelType.SENSOR, peerAddress, CarLinkChannelType.SENSOR.port) { handleSensorSocket(it) }
        connectOutboundChannel(CarLinkChannelType.MEDIA, peerAddress, CarLinkChannelType.MEDIA.port) { handleMediaSocket(it) }
        connectOutboundChannel(CarLinkChannelType.UIBC, peerAddress, CarLinkChannelType.UIBC.port) { handleUibcSocket(it) }
        connectOutboundChannel(CarLinkChannelType.RTSP, peerAddress, wfdPort) { handleWfdRtspSocket(it) }
    }

    private fun connectOutboundChannel(
        channel: CarLinkChannelType,
        peerAddress: String,
        port: Int,
        handler: (Socket) -> Unit,
    ) {
        val thread = Thread(
            {
                var attempts = 0
                while (!closed.get() && attempts < 6) {
                    attempts++
                    val socket = runCatching {
                        Socket().apply {
                            tcpNoDelay = true
                            keepAlive = true
                            connect(InetSocketAddress(peerAddress, port), 1_500)
                        }
                    }.getOrNull()
                    if (socket != null) {
                        activeSockets += socket
                        try {
                            handler(socket)
                        } catch (e: Exception) {
                            if (!closed.get()) {
                                onLog("CarLink outbound ${channel.name} ($peerAddress:$port) closed: ${e.message}")
                            }
                        } finally {
                            activeSockets.remove(socket)
                            runCatching { socket.close() }
                        }
                        return@Thread
                    }
                    try {
                        Thread.sleep(250L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "carlink-${channel.name.lowercase()}-outbound",
        ).apply { isDaemon = true }
        workerThreads += thread
        thread.start()
    }

    private fun startTcpChannel(
        channel: CarLinkChannelType,
        bindAddress: InetAddress?,
        handler: (Socket) -> Unit,
    ) {
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, channel.port))
            }
        }.onFailure { e ->
            onLog("CarLink ${channel.name} bind(${channel.port}) failed: ${e.message}")
        }.getOrNull() ?: return

        serverSockets += server
        val acceptThread = Thread(
            {
                while (!closed.get() && !server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (closed.get()) {
                        runCatching { client.close() }
                        break
                    }
                    runCatching {
                        client.tcpNoDelay = true
                        client.keepAlive = true
                    }
                    activeSockets += client
                    val clientThread = Thread(
                        {
                            try {
                                handler(client)
                            } catch (e: Exception) {
                                if (!closed.get()) {
                                    onLog("CarLink ${channel.name} socket closed: ${e.message}")
                                }
                            } finally {
                                activeSockets.remove(client)
                                runCatching { client.close() }
                            }
                        },
                        "carlink-${channel.name.lowercase()}-client",
                    ).apply { isDaemon = true }
                    workerThreads += clientThread
                    clientThread.start()
                }
            },
            "carlink-${channel.name.lowercase()}-accept",
        ).apply { isDaemon = true }
        workerThreads += acceptThread
        acceptThread.start()
    }

    // -------------------------------------------------------------------------
    // 1. AUTH Channel (57209)
    // -------------------------------------------------------------------------

    private fun handleAuthSocket(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        onLog("CarLink AUTH connected from ${socket.inetAddress.hostAddress}")
        while (!closed.get() && !socket.isClosed) {
            val frame = CarLinkFrameCodec.readFrame(input) ?: break
            when (frame.header.methodIndex) {
                CarLinkMethodIndex.AUTH_REQUEST -> {
                    val req = CarLinkProtoCodec.decodeAuthRequest(frame.body)
                    connectedPhoneModel = req.model.ifBlank { req.productName }.ifBlank { "Android CarLink" }
                    val pin = pinCodeProvider()
                    val hs = handshakeSession?.takeIf { it.pinCode == pin }
                        ?: CarLinkAuthCipher.ServerHandshakeSession(pinCode = pin).also { handshakeSession = it }
                    val resp = hs.processAuthRequest(
                        request = req,
                        dayOrNightMode = 2,
                        screenInfo = "${displaySpec.widthPixels}_${displaySpec.heightPixels}_320",
                    )
                    negotiatedWorkModes = resp.workModes
                    val respFrame = CarLinkFrameCodec.buildFrame(
                        seqId = frame.header.seqId,
                        category = CarLinkCmdCategory.AUTH,
                        methodIndex = CarLinkMethodIndex.AUTH_RESPONSE,
                        body = CarLinkProtoCodec.encodeAuthResponse(resp),
                        source = CarLinkSourceDevice.CAR,
                        format = CarLinkDataFormat.PB3,
                        messageType = CarLinkMessageType.RES,
                    )
                    CarLinkFrameCodec.writeFrame(output, respFrame)
                    onLog(
                        "CarLink AUTH: processed AuthRequest from $connectedPhoneModel " +
                            "(result=${resp.result}, quickConnect=${req.authPk.isEmpty()}, PIN=$pin)",
                    )
                }
                CarLinkMethodIndex.AUTH_CONFIRM -> {
                    val confirm = runCatching {
                        CarLinkProtoCodec.decodeAuthConfirm(frame.body)
                    }.getOrDefault(CarLinkProtoCodec.AuthConfirmMsg())
                    val fallbackIp = socket.inetAddress?.hostAddress.orEmpty()
                    val (peerIp, peerWfdPort) = handshakeSession?.parseAuthConfirmEndpoint(confirm, fallbackIp)
                        ?: (fallbackIp to CarLinkChannelType.RTSP.port)
                    val ack = CarLinkFrameCodec.buildAckFrame(frame.header)
                    CarLinkFrameCodec.writeFrame(output, ack)
                    onLog("CarLink AUTH: received AuthConfirm, peer=$peerIp:$peerWfdPort")
                    if (peerIp.isNotEmpty() && peerIp != "127.0.0.1" && peerIp != "localhost") {
                        connectToPeer(peerIp, peerWfdPort)
                    }
                }
                else -> {
                    CarLinkFrameCodec.writeFrame(output, CarLinkFrameCodec.buildAckFrame(frame.header))
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. CERT Channel (57249)
    // -------------------------------------------------------------------------

    private fun handleCertSocket(socket: Socket) {
        val output = socket.getOutputStream()
        onLog("CarLink CERT connected with ${socket.inetAddress.hostAddress}")
        val effectiveCertBytes = if (certBytes.isNotEmpty()) certBytes else CarLinkProtoCodec.DEFAULT_CAR_CERT_BYTES
        val certBody = CarLinkProtoCodec.encodeCarCertificate(
            CarLinkProtoCodec.CarCertificateMsg(content = effectiveCertBytes),
        )
        val certFrame = CarLinkFrameCodec.buildFrame(
            seqId = seqCounter.getAndIncrement(),
            category = CarLinkCmdCategory.CERT,
            methodIndex = CarLinkMethodIndex.CAR_CERTIFICATE,
            body = certBody,
            source = CarLinkSourceDevice.CAR,
            format = CarLinkDataFormat.PB3,
            messageType = CarLinkMessageType.SEND,
        )
        CarLinkFrameCodec.writeFrame(output, certFrame, handshakeSession?.sessionKey)
        output.flush()
        onLog("CarLink CERT: sent CarCertificate (${effectiveCertBytes.size}B)")
        runCatching { Thread.sleep(100L) }
    }

    // -------------------------------------------------------------------------
    // 3. CONTROL Channel (57219) & SENSOR Channel (57239)
    // -------------------------------------------------------------------------

    private fun handleSensorSocket(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        onLog("CarLink SENSOR connected with ${socket.inetAddress.hostAddress}")
        while (!closed.get() && !socket.isClosed) {
            val frame = CarLinkFrameCodec.readFrame(input, handshakeSession?.sessionKey) ?: break
            val msgType = frame.header.cmdDescription.messageType
            if (msgType == CarLinkMessageType.REQ || msgType == CarLinkMessageType.SEND_SYNC) {
                CarLinkFrameCodec.writeFrame(output, CarLinkFrameCodec.buildAckFrame(frame.header), handshakeSession?.sessionKey)
            }
        }
    }

    private fun handleControlSocket(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        controlOutput = output
        onLog("CarLink CONTROL connected with ${socket.inetAddress.hostAddress}")
        onSessionConnected(connectedPhoneModel.ifBlank { "ICCOA CarLink" })
        runCatching {
            val fgFrame = CarLinkFrameCodec.buildFrame(
                seqId = seqCounter.getAndIncrement(),
                category = CarLinkCmdCategory.CONTROL,
                methodIndex = CarLinkMethodIndex.NOTIFY_CAR_TO_FOREGROUND,
                body = CarLinkProtoCodec.encodeNotifyCarToForeground(),
                source = CarLinkSourceDevice.CAR,
                format = CarLinkDataFormat.PB3,
                messageType = CarLinkMessageType.SEND,
            )
            synchronized(output) {
                CarLinkFrameCodec.writeFrame(output, fgFrame, handshakeSession?.sessionKey)
            }
        }
        val heartbeatThread = Thread(
            {
                while (!closed.get() && !socket.isClosed && controlOutput === output) {
                    try {
                        Thread.sleep(2_000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (closed.get() || socket.isClosed || controlOutput !== output) break
                    val hbFrame = CarLinkFrameCodec.buildFrame(
                        seqId = seqCounter.getAndIncrement(),
                        category = CarLinkCmdCategory.CONTROL,
                        methodIndex = CarLinkMethodIndex.HEARTBEAT,
                        body = CarLinkProtoCodec.encodeHeartbeat(CarLinkProtoCodec.HeartbeatMsg()),
                        source = CarLinkSourceDevice.CAR,
                        format = CarLinkDataFormat.PB3,
                        messageType = CarLinkMessageType.SEND,
                    )
                    val ok = runCatching {
                        synchronized(output) {
                            CarLinkFrameCodec.writeFrame(output, hbFrame, handshakeSession?.sessionKey)
                        }
                        true
                    }.getOrDefault(false)
                    if (!ok) break
                }
            },
            "carlink-control-heartbeat",
        ).apply {
            isDaemon = true
            start()
        }
        workerThreads += heartbeatThread
        try {
            while (!closed.get() && !socket.isClosed) {
                val frame = CarLinkFrameCodec.readFrame(input, handshakeSession?.sessionKey) ?: break
                handleControlFrame(frame, output)
            }
        } finally {
            heartbeatThread.interrupt()
            workerThreads.remove(heartbeatThread)
            if (controlOutput === output) {
                controlOutput = null
                onSessionDisconnected()
            }
        }
    }

    private fun handleControlFrame(frame: CarLinkFrame, output: OutputStream) {
        val sessionKey = handshakeSession?.sessionKey
        val payload = if (sessionKey != null && CarLinkAuthCipher.looksLikeGcmWire(frame.body)) {
            runCatching { CarLinkAuthCipher.decryptGcmWire(sessionKey, frame.body) }.getOrDefault(frame.body)
        } else {
            frame.body
        }
        if (frame.header.cmdDescription.messageType == CarLinkMessageType.SEND_SYNC) {
            synchronized(output) {
                CarLinkFrameCodec.writeFrame(output, CarLinkFrameCodec.buildAckFrame(frame.header), sessionKey)
            }
        }

        when (frame.header.methodIndex) {
            CarLinkMethodIndex.HEARTBEAT -> {
                if (frame.header.cmdDescription.messageType == CarLinkMessageType.REQ) {
                    val hb = CarLinkProtoCodec.encodeHeartbeat(CarLinkProtoCodec.HeartbeatMsg())
                    val reply = CarLinkFrameCodec.buildFrame(
                        seqId = frame.header.seqId,
                        category = CarLinkCmdCategory.CONTROL,
                        methodIndex = CarLinkMethodIndex.HEARTBEAT,
                        body = hb,
                        source = CarLinkSourceDevice.CAR,
                        format = CarLinkDataFormat.PB3,
                        messageType = CarLinkMessageType.RES,
                    )
                    synchronized(output) { CarLinkFrameCodec.writeFrame(output, reply, sessionKey) }
                }
            }
            CarLinkMethodIndex.GET_PORT_REQUEST -> {
                val req = CarLinkProtoCodec.decodeGetPortRequest(payload)
                val targetPort = when (req.type) {
                    1 -> CarLinkChannelType.VIDEO.port
                    2 -> CarLinkChannelType.MEDIA.port
                    3 -> CarLinkChannelType.UIBC.port
                    4 -> CarLinkChannelType.SENSOR.port
                    5 -> CarLinkChannelType.RTSP.port
                    6 -> CarLinkChannelType.RTP.port
                    else -> CarLinkChannelType.VIDEO.port
                }
                val respBytes = CarLinkProtoCodec.encodeGetPortResponse(
                    CarLinkProtoCodec.GetPortResponseMsg(result = 0, port = targetPort),
                )
                val reply = CarLinkFrameCodec.buildFrame(
                    seqId = frame.header.seqId,
                    category = CarLinkCmdCategory.CONTROL,
                    methodIndex = CarLinkMethodIndex.GET_PORT_RESPONSE,
                    body = respBytes,
                    source = CarLinkSourceDevice.CAR,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.RES,
                )
                synchronized(output) { CarLinkFrameCodec.writeFrame(output, reply, sessionKey) }
            }
            CarLinkMethodIndex.GET_UCAR_CONFIG_REQUEST -> {
                val cfgBytes = CarLinkProtoCodec.encodeGetUCarConfigResponse(
                    CarLinkProtoCodec.GetUCarConfigResponseMsg(
                        carBrMac = carBrMac,
                        screenWidth = displaySpec.widthPixels,
                        screenHeight = displaySpec.heightPixels,
                        dpi = 320,
                        videoDisplayWidth = displaySpec.widthPixels,
                        videoDisplayHeight = displaySpec.heightPixels,
                        fps = displaySpec.fps,
                        isSupportP2P = true,
                        isSupportSoftAP = false,
                        isDataTransMode = true,
                        default5GChannel = 149,
                        isSupportCamera = false,
                        isSupportMic = true,
                        isSupportLowLatencyDecodingMode = true,
                        isSupportVoiceWaken = false,
                        sdkVersion = "1.5.0",
                        workModes = negotiatedWorkModes,
                        isSupportPOINav = true,
                        isSupportRotation = false,
                        isSupportHomeKey = true,
                        isSupportBackKey = true,
                        isSupportHevc = hevcEnabled,
                        realDpi = 320,
                    ),
                )
                val reply = CarLinkFrameCodec.buildFrame(
                    seqId = frame.header.seqId,
                    category = CarLinkCmdCategory.CONTROL,
                    methodIndex = CarLinkMethodIndex.GET_UCAR_CONFIG_RESPONSE,
                    body = cfgBytes,
                    source = CarLinkSourceDevice.CAR,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.RES,
                )
                synchronized(output) { CarLinkFrameCodec.writeFrame(output, reply, sessionKey) }
                onLog("CarLink CONTROL: sent GetUCarConfigResponse (${displaySpec.widthPixels}x${displaySpec.heightPixels}@${displaySpec.fps})")
            }
            CarLinkMethodIndex.NOTIFY_MUSIC_INFO -> {
                val music = CarLinkProtoCodec.decodeNotifyMusicInfo(payload)
                onMusicMetadata(music)
                if (frame.header.cmdDescription.messageType == CarLinkMessageType.REQ) {
                    synchronized(output) {
                        CarLinkFrameCodec.writeFrame(output, CarLinkFrameCodec.buildAckFrame(frame.header), sessionKey)
                    }
                }
            }
            CarLinkMethodIndex.DISCONNECT -> {
                onLog("CarLink CONTROL: phone requested Disconnect")
                close()
            }
            else -> {
                if (frame.header.cmdDescription.messageType == CarLinkMessageType.REQ) {
                    synchronized(output) {
                        CarLinkFrameCodec.writeFrame(output, CarLinkFrameCodec.buildAckFrame(frame.header), sessionKey)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. VIDEO Channel (57259) - Direct Annex-B H.264 / HEVC stream
    // -------------------------------------------------------------------------

    private fun handleVideoSocket(socket: Socket) {
        val input = socket.getInputStream()
        onLog("CarLink VIDEO connected from ${socket.inetAddress.hostAddress}")
        try {
            while (!closed.get() && !socket.isClosed) {
                val frame = CarLinkFrameCodec.readFrame(input) ?: break
                if (frame.body.isNotEmpty()) {
                    feedAnnexBVideoAccessUnit(
                        data = frame.body,
                        timestampMicros = frame.header.timestamp.toLong() * 1_000L,
                    )
                }
            }
        } finally {
            if (videoConfigured) {
                videoConfigured = false
                mediaSink?.onScreenStreamActive(MAIN_SCREEN_TYPE, false)
            }
        }
    }

    internal fun feedAnnexBVideoAccessUnit(data: ByteArray, timestampMicros: Long = System.nanoTime() / 1_000L) {
        if (data.isEmpty()) return
        val sink = mediaSink ?: return
        if (!videoConfigured) {
            val codec = if (hevcEnabled && CarLinkAnnexBInspector.looksLikeHevc(data)) {
                VideoCodec.H265
            } else {
                VideoCodec.H264
            }
            val csd = CarLinkAnnexBInspector.extractParameterSets(data) ?: data
            videoConfigured = true
            sink.onScreenStreamActive(MAIN_SCREEN_TYPE, true)
            sink.onVideoCodec(MAIN_SCREEN_TYPE, codec)
            sink.onVideoConfig(MAIN_SCREEN_TYPE, csd)
            onLog("CarLink VIDEO: configured ${codec.name} ${displaySpec.widthPixels}x${displaySpec.heightPixels}")
        }
        val nowNanos = System.nanoTime()
        val senderNanos = timestampMicros * 1_000L
        sink.onVideoFrame(MAIN_SCREEN_TYPE, data, senderNanos, nowNanos)
    }

    // -------------------------------------------------------------------------
    // 5. MEDIA Channel (57229) - PCM 16-bit Audio Stream
    // -------------------------------------------------------------------------

    private fun handleMediaSocket(socket: Socket) {
        val input = socket.getInputStream()
        onLog("CarLink MEDIA connected from ${socket.inetAddress.hostAddress}")
        try {
            while (!closed.get() && !socket.isClosed) {
                val frame = CarLinkFrameCodec.readFrame(input, handshakeSession?.sessionKey) ?: break
                if (frame.body.isNotEmpty()) {
                    val sink = mediaSink ?: continue
                    if (!audioConfigured) {
                        audioConfigured = true
                        audioSampleCounter.set(0)
                        sink.onAudioStarted(audioStreamId, audioFormat, 0)
                    }
                    val samplesInFrame = (frame.body.size / 4).coerceAtLeast(1)
                    val samplePos = audioSampleCounter.getAndAdd(samplesInFrame)
                    sink.onAudioRtp(audioStreamId, audioFormat, frame.body, samplePos)
                }
            }
        } finally {
            if (audioConfigured) {
                audioConfigured = false
                mediaSink?.onAudioStopped(audioStreamId)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 6. UIBC Channel (4321) - Touch & Key Back Channel
    // -------------------------------------------------------------------------

    private fun handleUibcSocket(socket: Socket) {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        uibcOutput = output
        onLog("CarLink UIBC connected with ${socket.inetAddress.hostAddress}:${socket.port}")
        try {
            val buf = ByteArray(1024)
            while (!closed.get() && !socket.isClosed) {
                if (input.read(buf) < 0) break
            }
        } finally {
            if (uibcOutput === output) {
                uibcOutput = null
            }
        }
    }

    fun sendTouch(action: Int, pointers: List<CarLinkUibcDispatcher.TouchPointer>): Boolean {
        val out = uibcOutput ?: return false
        val json = CarLinkUibcDispatcher.buildTouchJson(
            action = action,
            displayWidth = displaySpec.widthPixels,
            displayHeight = displaySpec.heightPixels,
            pointers = pointers,
        )
        val wire = CarLinkUibcDispatcher.encodeWirePayload(json, handshakeSession?.sessionKey)
        return runCatching {
            synchronized(out) {
                out.write(wire)
                out.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun sendKeyEvent(action: Int, androidKeyCode: Int, metaState: Int = 0): Boolean {
        val mapped = CarLinkUibcDispatcher.mapAndroidKeyToCarLinkKeyCode(androidKeyCode)
        var sentUibc = false
        val uibcOut = uibcOutput
        if (uibcOut != null) {
            val json = CarLinkUibcDispatcher.buildKeyJson(action, mapped, metaState)
            val wire = CarLinkUibcDispatcher.encodeWirePayload(json, handshakeSession?.sessionKey)
            sentUibc = runCatching {
                synchronized(uibcOut) {
                    uibcOut.write(wire)
                    uibcOut.flush()
                }
                true
            }.getOrDefault(false)
        }
        val ctrlOut = controlOutput ?: return sentUibc
        val body = CarLinkProtoCodec.encodeCustomKeyEvent(
            CarLinkProtoCodec.CustomKeyEventMsg(
                action = action,
                keycode = mapped,
                metaState = metaState,
            ),
        )
        val frame = CarLinkFrameCodec.buildFrame(
            seqId = seqCounter.getAndIncrement(),
            category = CarLinkCmdCategory.CONTROL,
            methodIndex = CarLinkMethodIndex.CUSTOM_KEY_EVENT,
            body = body,
            source = CarLinkSourceDevice.CAR,
            format = CarLinkDataFormat.PB3,
            messageType = CarLinkMessageType.SEND,
        )
        val sentCtrl = runCatching {
            synchronized(ctrlOut) {
                CarLinkFrameCodec.writeFrame(ctrlOut, frame, handshakeSession?.sessionKey)
            }
            true
        }.getOrDefault(false)
        return sentUibc || sentCtrl
    }

    fun sendKnob(dx: Int, dy: Int): Boolean {
        val keyCode = when {
            dx < 0 -> 21 // KEYCODE_DPAD_LEFT
            dx > 0 -> 22 // KEYCODE_DPAD_RIGHT
            dy < 0 -> 19 // KEYCODE_DPAD_UP
            dy > 0 -> 20 // KEYCODE_DPAD_DOWN
            else -> return false
        }
        val down = sendKeyEvent(CarLinkUibcDispatcher.ACTION_DOWN, keyCode)
        val up = sendKeyEvent(CarLinkUibcDispatcher.ACTION_UP, keyCode)
        return down || up
    }

    fun sendKnobSelect(down: Boolean): Boolean {
        val action = if (down) CarLinkUibcDispatcher.ACTION_DOWN else CarLinkUibcDispatcher.ACTION_UP
        return sendKeyEvent(action, 23) // KEYCODE_DPAD_CENTER
    }

    fun sendMediaButton(androidKeyCode: Int): Boolean {
        val down = sendKeyEvent(CarLinkUibcDispatcher.ACTION_DOWN, androidKeyCode)
        val up = sendKeyEvent(CarLinkUibcDispatcher.ACTION_UP, androidKeyCode)
        return down || up
    }

    fun answerCall(): Boolean {
        val ctrlOut = controlOutput
        if (ctrlOut != null) {
            val frame = CarLinkFrameCodec.buildFrame(
                seqId = seqCounter.getAndIncrement(),
                category = CarLinkCmdCategory.CONTROL,
                methodIndex = CarLinkMethodIndex.PICK_UP_CALL,
                body = CarLinkProtoCodec.encodePickUpCall(true),
                source = CarLinkSourceDevice.CAR,
                format = CarLinkDataFormat.PB3,
                messageType = CarLinkMessageType.SEND,
            )
            runCatching {
                synchronized(ctrlOut) {
                    CarLinkFrameCodec.writeFrame(ctrlOut, frame, handshakeSession?.sessionKey)
                }
            }
        }
        return sendMediaButton(5) // KEYCODE_CALL
    }

    fun endCall(): Boolean {
        val ctrlOut = controlOutput
        if (ctrlOut != null) {
            val frame = CarLinkFrameCodec.buildFrame(
                seqId = seqCounter.getAndIncrement(),
                category = CarLinkCmdCategory.CONTROL,
                methodIndex = CarLinkMethodIndex.NOTIFY_CALL_HUNG_UP,
                body = CarLinkProtoCodec.encodeNotifyCallHungUp(true),
                source = CarLinkSourceDevice.CAR,
                format = CarLinkDataFormat.PB3,
                messageType = CarLinkMessageType.SEND,
            )
            runCatching {
                synchronized(ctrlOut) {
                    CarLinkFrameCodec.writeFrame(ctrlOut, frame, handshakeSession?.sessionKey)
                }
            }
        }
        return sendMediaButton(6) // KEYCODE_ENDCALL
    }

    fun invokeVoiceAssistant(): Boolean {
        val ctrlOut = controlOutput
        var sentCtrl = false
        if (ctrlOut != null) {
            val frame = CarLinkFrameCodec.buildFrame(
                seqId = seqCounter.getAndIncrement(),
                category = CarLinkCmdCategory.CONTROL,
                methodIndex = CarLinkMethodIndex.AWAKEN_VOICE_ASSISTANT,
                body = CarLinkProtoCodec.encodeAwakenVoiceAssistant(0),
                source = CarLinkSourceDevice.CAR,
                format = CarLinkDataFormat.PB3,
                messageType = CarLinkMessageType.SEND,
            )
            sentCtrl = runCatching {
                synchronized(ctrlOut) {
                    CarLinkFrameCodec.writeFrame(ctrlOut, frame, handshakeSession?.sessionKey)
                }
                true
            }.getOrDefault(false)
        }
        val sentKey = sendMediaButton(231) // KEYCODE_VOICE_ASSIST
        return sentCtrl || sentKey
    }

    fun sendNightMode(night: Boolean): Boolean {
        val ctrlOut = controlOutput ?: return false
        val body = CarLinkProtoCodec.encodeNotifySwitchDayOrNight(
            CarLinkProtoCodec.NotifySwitchDayOrNightMsg(mode = if (night) 2 else 1),
        )
        val frame = CarLinkFrameCodec.buildFrame(
            seqId = seqCounter.getAndIncrement(),
            category = CarLinkCmdCategory.CONTROL,
            methodIndex = CarLinkMethodIndex.NOTIFY_SWITCH_DAY_OR_NIGHT,
            body = body,
            source = CarLinkSourceDevice.CAR,
            format = CarLinkDataFormat.PB3,
            messageType = CarLinkMessageType.SEND,
        )
        return runCatching {
            synchronized(ctrlOut) {
                CarLinkFrameCodec.writeFrame(ctrlOut, frame, handshakeSession?.sessionKey)
            }
            true
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // 7. Wi-Fi Display RTSP 1.0 (7236) & RTP (19000 / 15550) Sink (`libovmsink.so`)
    // -------------------------------------------------------------------------

    private fun handleWfdRtspSocket(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val output = socket.getOutputStream()
        val peerHost = socket.inetAddress?.hostAddress.orEmpty().ifBlank { "localhost" }
        onLog("CarLink WFD RTSP connected with $peerHost:${socket.port}")
        var clientCseq = 1
        var rtspSessionId = "12345678"
        var awaitingSetupResponse = false
        var playSent = false

        while (!closed.get() && !socket.isClosed) {
            val requestLine = reader.readLine() ?: break
            if (requestLine.isBlank()) continue
            var cseq = "1"
            var contentLength = 0
            var sessionHeader = ""
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon > 0) {
                    val key = header.substring(0, colon).trim().lowercase()
                    val value = header.substring(colon + 1).trim()
                    when (key) {
                        "cseq" -> cseq = value
                        "content-length" -> contentLength = value.toIntOrNull() ?: 0
                        "session" -> sessionHeader = value.substringBefore(';').trim()
                    }
                }
            }
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = reader.read(chars, readTotal, contentLength - readTotal)
                    if (n < 0) break
                    readTotal += n
                }
                String(chars, 0, readTotal)
            } else {
                ""
            }

            if (sessionHeader.isNotEmpty()) {
                rtspSessionId = sessionHeader
            }

            // Handle RTSP responses from the Phone WFD Source (to our M2 OPTIONS / M6 SETUP / M7 PLAY)
            if (requestLine.startsWith("RTSP/", ignoreCase = true)) {
                if (awaitingSetupResponse || (sessionHeader.isNotEmpty() && !playSent)) {
                    awaitingSetupResponse = false
                    playSent = true
                    val playReq = buildString {
                        append("PLAY rtsp://$peerHost/wfd1.0/streamid=0 RTSP/1.0\r\n")
                        append("CSeq: ${clientCseq++}\r\n")
                        append("Session: $rtspSessionId\r\n")
                        append("\r\n")
                    }
                    synchronized(output) {
                        output.write(playReq.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    onLog("CarLink WFD RTSP: sent M7 PLAY (session=$rtspSessionId)")
                }
                continue
            }

            val method = requestLine.substringBefore(' ').uppercase()
            when (method) {
                "OPTIONS" -> {
                    val resp = buildRtspResponse(
                        cseq = cseq,
                        extraHeaders = listOf(
                            "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER",
                        ),
                    )
                    // Reply 200 OK to M1 OPTIONS and immediately send M2 OPTIONS * RTSP/1.0 (WifiDisplaySink::onOptionsRequest)
                    val m2Options = buildString {
                        append("OPTIONS * RTSP/1.0\r\n")
                        append("CSeq: ${clientCseq++}\r\n")
                        append("Require: org.wfa.wfd1.0\r\n")
                        append("\r\n")
                    }
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.write(m2Options.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
                "GET_PARAMETER" -> {
                    val wfdParams = buildString {
                        if (body.isEmpty() || body.contains("wfd_car_display_mode")) {
                            append("wfd_car_display_mode: mode=0;display=${displaySpec.widthPixels}:${displaySpec.heightPixels};dpi=160;fps=${displaySpec.fps}\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_video_formats")) {
                            append("wfd_video_formats: 00 00 02 04 0001FFFF 1FFFFFFF 00000FFF 00 0000 0000 00 none none\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_audio_codecs")) {
                            append("wfd_audio_codecs: LPCM 00000003 00, AAC 0000000F 00\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_client_rtp_ports")) {
                            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $WFD_RTP_PORT 0 mode=play\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_uibc_capability")) {
                            append("wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch;hidc_cap_list=none;port=none;uibc_encrypted=true\r\n")
                        }
                        if (body.isEmpty() || body.contains("ovm_control_capability")) {
                            append("ovm_control_capability: supported\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_standby_resume_capability")) {
                            append("wfd_standby_resume_capability: supported\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_audio_latency")) {
                            append("wfd_audio_latency: 0\r\n")
                        }
                        if (body.isEmpty() || body.contains("iccoa_support_hevc")) {
                            append("iccoa_support_hevc: ${if (hevcEnabled) "supported" else "none"}\r\n")
                        }
                        if (body.isEmpty() || body.contains("iccoa_support_fusion")) {
                            append("iccoa_support_fusion: supported\r\n")
                        }
                        if (body.isEmpty() || body.contains("wfd_av_raw_packet")) {
                            append("wfd_av_raw_packet: true\r\n")
                        }
                    }
                    val resp = buildRtspResponse(cseq = cseq, body = wfdParams)
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    onLog("CarLink WFD RTSP: replied M3 GET_PARAMETER (${displaySpec.widthPixels}x${displaySpec.heightPixels}@${displaySpec.fps}, rtp=$WFD_RTP_PORT)")
                }
                "SET_PARAMETER" -> {
                    if (body.contains("wfd_uibc_capability")) {
                        val portMatch = Regex("port=(\\d+)").find(body)
                        val peerUibcPort = portMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: CarLinkChannelType.UIBC.port
                        if (uibcOutput == null && peerHost != "localhost" && peerHost != "127.0.0.1") {
                            connectOutboundChannel(CarLinkChannelType.UIBC, peerHost, peerUibcPort) { handleUibcSocket(it) }
                        }
                    }
                    val resp = buildRtspResponse(cseq = cseq)
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    when {
                        body.contains("wfd_trigger_method: SETUP") -> {
                            awaitingSetupResponse = true
                            val setupReq = buildString {
                                append("SETUP rtsp://$peerHost/wfd1.0/streamid=0 RTSP/1.0\r\n")
                                append("CSeq: ${clientCseq++}\r\n")
                                append("Transport: RTP/AVP/UDP;unicast;client_port=$WFD_RTP_PORT-${WFD_RTP_PORT + 1}\r\n")
                                append("\r\n")
                            }
                            synchronized(output) {
                                output.write(setupReq.toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            onLog("CarLink WFD RTSP: sent M6 SETUP (client_port=$WFD_RTP_PORT-${WFD_RTP_PORT + 1})")
                        }
                        body.contains("wfd_trigger_method: PLAY") -> {
                            playSent = true
                            val playReq = buildString {
                                append("PLAY rtsp://$peerHost/wfd1.0/streamid=0 RTSP/1.0\r\n")
                                append("CSeq: ${clientCseq++}\r\n")
                                append("Session: $rtspSessionId\r\n")
                                append("\r\n")
                            }
                            synchronized(output) {
                                output.write(playReq.toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            onLog("CarLink WFD RTSP: sent PLAY")
                        }
                        body.contains("wfd_trigger_method: TEARDOWN") -> {
                            val teardownReq = buildString {
                                append("TEARDOWN rtsp://$peerHost/wfd1.0/streamid=0 RTSP/1.0\r\n")
                                append("CSeq: ${clientCseq++}\r\n")
                                append("Session: $rtspSessionId\r\n")
                                append("\r\n")
                            }
                            synchronized(output) {
                                output.write(teardownReq.toByteArray(Charsets.UTF_8))
                                output.flush()
                            }
                            break
                        }
                    }
                }
                "SETUP" -> {
                    val resp = buildRtspResponse(
                        cseq = cseq,
                        extraHeaders = listOf(
                            "Transport: RTP/AVP/UDP;unicast;client_port=$WFD_RTP_PORT-${WFD_RTP_PORT + 1};server_port=19000-19001",
                            "Session: $rtspSessionId;timeout=60",
                        ),
                    )
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
                "PLAY" -> {
                    onLog("CarLink WFD RTSP: PLAY started")
                    val resp = buildRtspResponse(
                        cseq = cseq,
                        extraHeaders = listOf("Session: $rtspSessionId"),
                    )
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
                "TEARDOWN" -> {
                    val resp = buildRtspResponse(cseq = cseq)
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    break
                }
                else -> {
                    val resp = buildRtspResponse(cseq = cseq)
                    synchronized(output) {
                        output.write(resp.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
            }
        }
    }

    private fun buildRtspResponse(
        cseq: String,
        extraHeaders: List<String> = emptyList(),
        body: String = "",
    ): String = buildString {
        append("RTSP/1.0 200 OK\r\n")
        append("CSeq: $cseq\r\n")
        for (h in extraHeaders) {
            append(h).append("\r\n")
        }
        if (body.isNotEmpty()) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            append("Content-Type: text/parameters\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("\r\n")
            append(body)
        } else {
            append("\r\n")
        }
    }

    private fun startRtpUdpReceiver(bindAddress: InetAddress?, port: Int) {
        val udp = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                receiveBufferSize = 4 * 1024 * 1024
                bind(InetSocketAddress(bindAddress, port))
            }
        }.onFailure { e ->
            onLog("CarLink RTP UDP bind($port) failed: ${e.message}")
        }.getOrNull() ?: return

        rtpUdpSockets += udp
        val rtpThread = Thread(
            {
                val packetBuf = ByteArray(65535)
                val packet = DatagramPacket(packetBuf, packetBuf.size)
                val nalAssembler = ByteArrayOutputStream(256 * 1024)
                while (!closed.get() && !udp.isClosed) {
                    val received = runCatching {
                        udp.receive(packet)
                        true
                    }.getOrDefault(false)
                    if (!received) break
                    val len = packet.length
                    if (len <= 12) continue
                    val csrcCount = packetBuf[0].toInt() and 0x0F
                    val payloadOffset = 12 + csrcCount * 4
                    if (payloadOffset >= len) continue
                    val payloadLen = len - payloadOffset
                    val firstByte = packetBuf[payloadOffset].toInt() and 0xFF
                    when {
                        firstByte == 0x00 && payloadLen > 4 && packetBuf[payloadOffset + 1] == 0.toByte() -> {
                            // Direct Annex-B inside RTP (`wfd_av_raw_packet: true`)
                            val annexB = packetBuf.copyOfRange(payloadOffset, len)
                            feedAnnexBVideoAccessUnit(annexB)
                        }
                        firstByte == 0x47 && payloadLen >= 188 -> {
                            // MPEG-TS over RTP (standard WFD transport stream)
                            CarLinkMpegTsExtractor.extractAnnexB(
                                tsPayload = packetBuf,
                                offset = payloadOffset,
                                length = payloadLen,
                                assembler = nalAssembler,
                                marker = (packetBuf[1].toInt() and 0x80) != 0,
                            ) { accessUnit ->
                                feedAnnexBVideoAccessUnit(accessUnit)
                            }
                        }
                        else -> {
                            // RFC 6184 H.264 / RFC 7798 H.265 RTP packetization
                            CarLinkRtpH264Depacketizer.depacketize(
                                rtpPacket = packetBuf,
                                length = len,
                                assembler = nalAssembler,
                                preferHevc = hevcEnabled,
                            ) { accessUnit, rtpTimestamp ->
                                feedAnnexBVideoAccessUnit(accessUnit, (rtpTimestamp * 1_000_000L) / 90_000L)
                            }
                        }
                    }
                }
            },
            "carlink-rtp-udp-$port",
        ).apply { isDaemon = true }
        workerThreads += rtpThread
        rtpThread.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        controlOutput = null
        uibcOutput = null
        rtpUdpSockets.forEach { s -> runCatching { s.close() } }
        rtpUdpSockets.clear()
        serverSockets.forEach { s -> runCatching { s.close() } }
        serverSockets.clear()
        activeSockets.forEach { s -> runCatching { s.close() } }
        activeSockets.clear()
        if (videoConfigured) {
            videoConfigured = false
            runCatching { mediaSink?.onScreenStreamActive(MAIN_SCREEN_TYPE, false) }
        }
        if (audioConfigured) {
            audioConfigured = false
            runCatching { mediaSink?.onAudioStopped(audioStreamId) }
        }
    }

    companion object {
        const val WFD_RTP_PORT = 19000
    }
}

/**
 * Lightweight Annex-B H.264 / HEVC parameter set inspector for [CarLinkControlSession].
 */
internal object CarLinkAnnexBInspector {
    private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun looksLikeHevc(data: ByteArray): Boolean {
        val nalStart = findNalHeaderIndex(data, 0) ?: return false
        val firstByte = data[nalStart].toInt() and 0xFF
        val h264Type = firstByte and 0x1F
        val hevcType = (firstByte ushr 1) and 0x3F
        return hevcType in 32..34 && h264Type != 7 && h264Type != 8
    }

    fun extractParameterSets(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var idx = 0
        while (idx < data.size) {
            val nalStart = findNalHeaderIndex(data, idx) ?: break
            val nextStart = findNextStartCode(data, nalStart) ?: data.size
            val nalHeader = data[nalStart].toInt() and 0xFF
            val h264Type = nalHeader and 0x1F
            val hevcType = (nalHeader ushr 1) and 0x3F
            if (h264Type == 7 || h264Type == 8 || hevcType in 32..34) {
                out.write(START_CODE_4)
                out.write(data, nalStart, nextStart - nalStart)
            }
            idx = nextStart
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun findNalHeaderIndex(data: ByteArray, from: Int): Int? {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i + 3
                if (i + 4 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i + 4
            }
            i++
        }
        return null
    }

    private fun findNextStartCode(data: ByteArray, from: Int): Int? {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return null
    }
}

/**
 * Extracts elementary Annex-B H.264/HEVC video payloads from 188-byte MPEG-TS packets over WFD RTP.
 */
internal object CarLinkMpegTsExtractor {
    fun extractAnnexB(
        tsPayload: ByteArray,
        offset: Int,
        length: Int,
        assembler: ByteArrayOutputStream,
        marker: Boolean,
        onAccessUnit: (ByteArray) -> Unit,
    ) {
        var pos = offset
        val end = offset + length
        while (pos + 188 <= end) {
            if (tsPayload[pos] != 0x47.toByte()) {
                pos++
                continue
            }
            val pusi = (tsPayload[pos + 1].toInt() and 0x40) != 0
            val pid = ((tsPayload[pos + 1].toInt() and 0x1F) shl 8) or (tsPayload[pos + 2].toInt() and 0xFF)
            val adaptationControl = (tsPayload[pos + 3].toInt() ushr 4) and 0x03
            var payloadPos = pos + 4
            if (adaptationControl == 2 || adaptationControl == 3) {
                val adaptLen = tsPayload[payloadPos].toInt() and 0xFF
                payloadPos += 1 + adaptLen
            }
            // Typical WFD video PIDs: 0x1011 (4113) or any non-PAT/PMT/PCR elementary PID with PES video stream_id (0xE0..0xEF)
            if ((adaptationControl == 1 || adaptationControl == 3) && payloadPos < pos + 188 && pid !in setOf(0, 1, 0x100, 0x1000)) {
                if (pusi && payloadPos + 9 <= pos + 188 &&
                    tsPayload[payloadPos] == 0.toByte() &&
                    tsPayload[payloadPos + 1] == 0.toByte() &&
                    tsPayload[payloadPos + 2] == 1.toByte()
                ) {
                    val streamId = tsPayload[payloadPos + 3].toInt() and 0xFF
                    if (streamId in 0xE0..0xEF) {
                        if (assembler.size() > 0) {
                            onAccessUnit(assembler.toByteArray())
                            assembler.reset()
                        }
                        val pesHeaderDataLen = tsPayload[payloadPos + 8].toInt() and 0xFF
                        payloadPos += 9 + pesHeaderDataLen
                    } else {
                        pos += 188
                        continue
                    }
                }
                if (payloadPos < pos + 188) {
                    assembler.write(tsPayload, payloadPos, (pos + 188) - payloadPos)
                }
            }
            pos += 188
        }
        if (marker && assembler.size() > 0) {
            onAccessUnit(assembler.toByteArray())
            assembler.reset()
        }
    }
}

/**
 * RFC 6184 H.264 & RFC 7798 HEVC/H.265 RTP depacketizer producing Annex-B access units.
 */
internal object CarLinkRtpH264Depacketizer {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    fun depacketize(
        rtpPacket: ByteArray,
        length: Int,
        assembler: ByteArrayOutputStream,
        preferHevc: Boolean = false,
        onAccessUnit: (ByteArray, Long) -> Unit,
    ) {
        if (length <= 12) return
        val marker = (rtpPacket[1].toInt() and 0x80) != 0
        val timestamp = ByteBuffer.wrap(rtpPacket, 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val csrcCount = rtpPacket[0].toInt() and 0x0F
        val payloadOffset = 12 + csrcCount * 4
        if (payloadOffset >= length) return

        val firstByte = rtpPacket[payloadOffset].toInt() and 0xFF
        val h264Type = firstByte and 0x1F
        val hevcType = (firstByte ushr 1) and 0x3F

        if (preferHevc && (hevcType == 48 || hevcType == 49 || hevcType in 32..34) && h264Type != 24 && h264Type != 28) {
            when (hevcType) {
                48 -> { // HEVC AP (Aggregation Packet)
                    var pos = payloadOffset + 2
                    while (pos + 2 <= length) {
                        val naluSize = ((rtpPacket[pos].toInt() and 0xFF) shl 8) or (rtpPacket[pos + 1].toInt() and 0xFF)
                        pos += 2
                        if (pos + naluSize > length) break
                        assembler.write(START_CODE)
                        assembler.write(rtpPacket, pos, naluSize)
                        pos += naluSize
                    }
                }
                49 -> { // HEVC FU (Fragmentation Unit)
                    if (payloadOffset + 2 < length) {
                        val nalHeader0 = rtpPacket[payloadOffset].toInt() and 0xFF
                        val nalHeader1 = rtpPacket[payloadOffset + 1].toInt() and 0xFF
                        val fuHeader = rtpPacket[payloadOffset + 2].toInt() and 0xFF
                        val startBit = (fuHeader and 0x80) != 0
                        val fuType = fuHeader and 0x3F
                        if (startBit) {
                            val reconstructed0 = (nalHeader0 and 0x81) or (fuType shl 1)
                            assembler.write(START_CODE)
                            assembler.write(reconstructed0)
                            assembler.write(nalHeader1)
                        }
                        val fragOffset = payloadOffset + 3
                        if (fragOffset < length) {
                            assembler.write(rtpPacket, fragOffset, length - fragOffset)
                        }
                    }
                }
                else -> {
                    assembler.write(START_CODE)
                    assembler.write(rtpPacket, payloadOffset, length - payloadOffset)
                }
            }
        } else {
            when {
                h264Type in 1..23 -> {
                    assembler.write(START_CODE)
                    assembler.write(rtpPacket, payloadOffset, length - payloadOffset)
                }
                h264Type == 24 -> { // STAP-A
                    var pos = payloadOffset + 1
                    while (pos + 2 <= length) {
                        val naluSize = ((rtpPacket[pos].toInt() and 0xFF) shl 8) or (rtpPacket[pos + 1].toInt() and 0xFF)
                        pos += 2
                        if (pos + naluSize > length) break
                        assembler.write(START_CODE)
                        assembler.write(rtpPacket, pos, naluSize)
                        pos += naluSize
                    }
                }
                h264Type == 28 && payloadOffset + 1 < length -> { // FU-A
                    val fuHeader = rtpPacket[payloadOffset + 1].toInt() and 0xFF
                    val startBit = (fuHeader and 0x80) != 0
                    val reconstructedNal = (firstByte and 0xE0) or (fuHeader and 0x1F)
                    if (startBit) {
                        assembler.write(START_CODE)
                        assembler.write(reconstructedNal)
                    }
                    val fragOffset = payloadOffset + 2
                    if (fragOffset < length) {
                        assembler.write(rtpPacket, fragOffset, length - fragOffset)
                    }
                }
            }
        }
        if (marker && assembler.size() > 0) {
            val accessUnit = assembler.toByteArray()
            assembler.reset()
            onAccessUnit(accessUnit, timestamp)
        }
    }
}
