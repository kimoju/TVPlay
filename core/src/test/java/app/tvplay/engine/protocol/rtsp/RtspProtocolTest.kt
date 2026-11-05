package app.tvplay.engine.protocol.rtsp

import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.av.DisplayInsets
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.av.ViewportTouchContact
import app.tvplay.engine.session.TvIconAsset
import app.tvplay.engine.session.TvStreamServerConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspProtocolTest {

    @Test
    fun rtspMessageCodecParsesPipelinesAndRetainsPartialMessage() {
        val body1 = "hello".toByteArray(Charsets.US_ASCII)
        val req1 = "POST /pair-setup RTSP/1.0\r\nCSeq: 1\r\nContent-Length: ${body1.size}\r\n\r\n".toByteArray() + body1
        val partial = "GET /info RTSP/1.0\r\nCSeq: 2\r\n".toByteArray()
        val parsed = RtspMessageCodec.parseMessages(req1 + partial)
        assertEquals(1, parsed.messages.size)
        assertEquals("POST", parsed.messages[0].method)
        assertEquals("/pair-setup", parsed.messages[0].path)
        assertEquals("1", parsed.messages[0].headers["cseq"])
        assertArrayEquals(body1, parsed.messages[0].body)
        assertArrayEquals(partial, parsed.rest)
    }

    @Test
    fun bplistCodecEncodesAndDecodesNestedMapsListsAndPrimitives() {
        val original = linkedMapOf<String, Any?>(
            "model" to "TVPlay,1",
            "statusFlags" to 4L,
            "features" to 0x100029ffL,
            "active" to true,
            "modes" to listOf("Knob", "Touch"),
            "bytes" to byteArrayOf(1, 2, 3, 4),
        )
        val encoded = BplistCodec.encode(original)
        val decoded = BplistCodec.decode(encoded) as Map<*, *>
        assertEquals("TVPlay,1", decoded["model"])
        assertEquals(4L, decoded["statusFlags"])
        assertEquals(true, decoded["active"])
        assertEquals(listOf("Knob", "Touch"), decoded["modes"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded["bytes"] as ByteArray)
    }

    @Test
    fun hidKnobAndTouchReportsEncodeExpectedBinaryLayout() {
        val knob = AirPlayHid.knobReport(
            AirPlayKnobState(select = true, home = false, back = true, x = -1, y = 1, wheel = 2),
        )
        assertEquals(4, knob.size)
        assertEquals(0x05.toByte(), knob[0])
        assertEquals((-1).toByte(), knob[1])
        assertEquals(1.toByte(), knob[2])
        assertEquals(2.toByte(), knob[3])

        val touch = AirPlayHid.touchReport(
            listOf(ViewportTouchContact(id = 0, down = true, x = 512.0, y = 256.0)),
        )
        assertEquals(12, touch.size)
        assertEquals(0.toByte(), touch[0])
        assertEquals(1.toByte(), touch[1])
        assertTrue(touch[2] == 0x00.toByte() && touch[3] == 0x02.toByte())
    }

    @Test
    fun rtspInfoPayloadEncodesOemIconsSafeAreaRightHandDriveAndTvKnobDescriptor() {
        val fakeIcon = TvIconAsset(widthPixels = 120, heightPixels = 120, data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        val display = TvDisplaySpec(
            widthPixels = 1920,
            heightPixels = 1080,
            fps = 60,
            primaryInputDevice = 3,
            safeArea = DisplayInsets(top = 10, bottom = 10, left = 20, right = 20),
            safeAreaDrawOutside = true,
        )
        val config = TvStreamServerConfig(
            deviceName = "TVPlay",
            deviceId = "02:11:22:33:44:55",
            btMac = "02:11:22:33:44:55",
            sourceVersion = "950.7.1",
            main = display,
            icons = listOf(fakeIcon),
            oemLabel = "TVPlay",
            rightHandDrive = true,
        )

        val encoded = BplistCodec.encode(RtspInfoPayload.build(config))
        val decoded = BplistCodec.decode(encoded) as Map<*, *>

        assertEquals(true, decoded["rightHandDrive"])
        assertEquals(true, decoded["oemIconVisible"])
        assertEquals("TVPlay", decoded["oemIconLabel"])

        val oemIcons = decoded["oemIcons"] as? List<*>
        assertNotNull(oemIcons)
        assertEquals(1, oemIcons!!.size)
        val iconEntry = oemIcons[0] as Map<*, *>
        assertEquals(120L, iconEntry["widthPixels"])
        assertEquals(120L, iconEntry["heightPixels"])
        assertArrayEquals(fakeIcon.data, iconEntry["imageData"] as ByteArray)

        val displays = decoded["displays"] as List<*>
        val mainDisplay = displays[0] as Map<*, *>
        assertEquals(1920L, mainDisplay["widthPixels"])
        assertEquals(1080L, mainDisplay["heightPixels"])
        assertEquals(60L, mainDisplay["maxFPS"])
        assertEquals(3L, mainDisplay["primaryInputDevice"])
        val viewAreas = mainDisplay["viewAreas"] as List<*>
        val viewArea = viewAreas[0] as Map<*, *>
        val safeArea = viewArea["safeArea"] as Map<*, *>
        assertEquals(true, safeArea["drawUIOutsideSafeArea"])
        assertEquals(1880L, safeArea["widthPixels"])
        assertEquals(1060L, safeArea["heightPixels"])

        val hidDevices = decoded["hidDevices"] as List<*>
        assertEquals(4, hidDevices.size)

        val configNoIcons = config.copy(icons = emptyList(), rightHandDrive = false)
        val decodedNoIcons = BplistCodec.decode(BplistCodec.encode(RtspInfoPayload.build(configNoIcons))) as Map<*, *>
        assertEquals(false, decodedNoIcons["rightHandDrive"])
        assertFalse(decodedNoIcons.containsKey("oemIcons"))
    }

    @Test
    fun isSameOrAuthorizedAirPlayPeerAllowsDualStackPeerAndRejectsLoopback() {
        val ipv4Peer = java.net.InetAddress.getByName("192.168.49.42")
        val ipv6LinkLocalPeer = java.net.InetAddress.getByName("fe80::1c2b:3d4e:5f6a:7b8c")
        val ipv6UlaPeer = java.net.InetAddress.getByName("fd12:3456:789a::42")
        val loopback = java.net.InetAddress.getByName("127.0.0.1")

        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv4Peer, ipv4Peer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv6LinkLocalPeer, ipv4Peer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv4Peer, ipv6LinkLocalPeer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv6UlaPeer, ipv4Peer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv4Peer, ipv6UlaPeer))
        assertFalse(isSameOrAuthorizedAirPlayPeer(loopback, ipv4Peer))
    }

    @Test
    fun screenStreamDecryptsConfigAndVideoFramesAndIapTunnelWakesOnCleanPeerEof() {
        val loopback = java.net.InetAddress.getByName("127.0.0.1")
        val videoKey = ByteArray(32) { (it + 1).toByte() }
        val receivedCodecs = mutableListOf<VideoCodec>()
        val receivedConfigs = mutableListOf<ByteArray>()
        val receivedFrames = mutableListOf<ByteArray>()
        val closedLatch = java.util.concurrent.CountDownLatch(1)

        val screen = ScreenStream(videoKey, loopback)
        val port = screen.listen(
            object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) {
                    receivedCodecs += codec
                }

                override fun onConfig(codecData: ByteArray) {
                    receivedConfigs += codecData
                }

                override fun onFrame(naluBytes: ByteArray) {
                    receivedFrames += naluBytes
                }

                override fun onClosed(cause: Throwable?) {
                    closedLatch.countDown()
                }
            },
        )

        java.net.Socket("127.0.0.1", port).use { client ->
            val out = client.getOutputStream()
            // Send OP_VIDEO_CONFIG (opcode = 1) with avcC box header
            val avccRecord = byteArrayOf(1, 0x42, 0x00, 0x1e, 0xff.toByte(), 0xe1.toByte(), 0, 4, 0x67, 0x42, 0x00, 0x1e)
            val configPayload = byteArrayOf(0, 0, 0, 12, 'a'.code.toByte(), 'v'.code.toByte(), 'c'.code.toByte(), 'C'.code.toByte()) + avccRecord
            val configHeader = ByteArray(128).also { hdr ->
                hdr[0] = configPayload.size.toByte()
                hdr[4] = 1 // OP_VIDEO_CONFIG
            }
            out.write(configHeader)
            out.write(configPayload)

            // Send OP_VIDEO_FRAME (opcode = 0) with 4-byte length-prefixed NALU sealed with ChaCha20-Poly1305
            val clearNalu = byteArrayOf(0, 0, 0, 4, 0x65, 0x11, 0x22, 0x33)
            val frameHeader = ByteArray(128).also { hdr ->
                val sealedLen = clearNalu.size + 16
                hdr[0] = sealedLen.toByte()
                hdr[4] = 0 // OP_VIDEO_FRAME
            }
            val sealedBody = app.tvplay.engine.auth.AirPlayCrypto.chachaSeal(
                key = videoKey,
                nonce = app.tvplay.engine.auth.AirPlayCrypto.nonce64(0L),
                plaintext = clearNalu,
                aad = frameHeader,
            )
            out.write(frameHeader)
            out.write(sealedBody)
            out.flush()
        }

        assertTrue(closedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf(VideoCodec.H264), receivedCodecs)
        assertEquals(1, receivedConfigs.size)
        assertEquals(1, receivedFrames.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33), receivedFrames.single())
        screen.close()

        // Verify IapTunnel decrypts comm package and signals onClosed(null) on clean peer EOF
        val tunnelKey = ByteArray(32) { (it + 9).toByte() }
        val receivedIap = mutableListOf<ByteArray>()
        val tunnelClosedLatch = java.util.concurrent.CountDownLatch(1)
        val tunnel = IapTunnel(tunnelKey, loopback, loopback)
        val tunnelPort = tunnel.listen(
            object : IapTunnel.Listener {
                override fun onIap(bytes: ByteArray) {
                    receivedIap += bytes
                }

                override fun onClosed(cause: Throwable?) {
                    tunnelClosedLatch.countDown()
                }
            },
        )
        java.net.Socket("127.0.0.1", tunnelPort).use { client ->
            val iapBody = byteArrayOf(0xFF.toByte(), 0x5A, 0x00, 0x08)
            val pkg = ByteArray(32 + iapBody.size).also { buf ->
                val total = 32 + iapBody.size
                buf[0] = (total ushr 24).toByte()
                buf[1] = (total ushr 16).toByte()
                buf[2] = (total ushr 8).toByte()
                buf[3] = total.toByte()
                // "comm" = 0x636f6d6d at offset 16
                buf[16] = 0x63
                buf[17] = 0x6f
                buf[18] = 0x6d
                buf[19] = 0x6d
                iapBody.copyInto(buf, 32)
            }
            val aad = byteArrayOf(pkg.size.toByte(), (pkg.size ushr 8).toByte())
            val sealed = app.tvplay.engine.auth.AirPlayCrypto.chachaSeal(
                key = tunnelKey,
                nonce = app.tvplay.engine.auth.AirPlayCrypto.nonce64(0L),
                plaintext = pkg,
                aad = aad,
            )
            client.getOutputStream().write(aad + sealed)
            client.getOutputStream().flush()
        }
        assertTrue("IapTunnel must invoke onClosed on clean peer EOF", tunnelClosedLatch.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, receivedIap.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x5A, 0x00, 0x08), receivedIap.single())
        tunnel.close()
    }

    @Test
    fun encryptedEventChannelSerializesConcurrentCommandsWithoutNonceReordering() {
        val writeKey = ByteArray(32) { (it + 0x21).toByte() }
        val readKey = ByteArray(32) { (it + 0x41).toByte() }
        val senderCipher = app.tvplay.engine.auth.ControlCipher(writeKey = writeKey, readKey = readKey)
        val receiverCipher = app.tvplay.engine.auth.ControlCipher(writeKey = readKey, readKey = writeKey)

        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            val receivedPlaintexts = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
            val expectedMessages = 16
            val readerDone = java.util.concurrent.CountDownLatch(1)

            val clientSocket = java.net.Socket("127.0.0.1", port)
            val acceptedSocket = server.accept()

            Thread {
                try {
                    val input = acceptedSocket.getInputStream()
                    val buf = ByteArray(4096)
                    var encBuf = ByteArray(0)
                    while (receivedPlaintexts.size < expectedMessages) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        val decrypted = receiverCipher.decrypt(encBuf + buf.copyOf(n))
                        encBuf = decrypted.rest
                        if (decrypted.data.isNotEmpty()) {
                            val parsed = RtspMessageCodec.parseMessages(decrypted.data)
                            for (msg in parsed.messages) {
                                receivedPlaintexts.add(msg.body)
                            }
                        }
                    }
                } finally {
                    readerDone.countDown()
                }
            }.apply { isDaemon = true; start() }

            // Configure AirPlaySession with encrypted eventSocket + eventCipher
            val config = TvStreamServerConfig(
                deviceName = "TVPlay",
                deviceId = "02:11:22:33:44:55",
                btMac = "02:11:22:33:44:55",
                sourceVersion = "950.7.1",
                main = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080),
            )
            val session = AirPlaySession(
                socket = java.net.Socket(),
                config = config,
                identity = AccessoryIdentity.generate(),
                pairings = app.tvplay.engine.auth.PairingRegistry(),
                mfi = null,
                listener = object : AirPlaySessionListener {},
                media = object : AirPlayMediaHandler {},
            )
            val eventSocketField = AirPlaySession::class.java.getDeclaredField("eventSocket").apply { isAccessible = true }
            val eventCipherField = AirPlaySession::class.java.getDeclaredField("eventCipher").apply { isAccessible = true }
            eventSocketField.set(session, clientSocket)
            eventCipherField.set(session, senderCipher)

            // Fire 16 concurrent setNightMode + sendCommand calls from multiple threads
            val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
            val futures = (0 until expectedMessages).map { index ->
                pool.submit {
                    if (index % 2 == 0) {
                        session.setNightMode(index % 4 == 0)
                    } else {
                        session.sendCommand(mapOf("type" to "requestSiri", "params" to mapOf("siriAction" to 1L)))
                    }
                }
            }
            futures.forEach { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            pool.shutdown()

            assertTrue(
                "All concurrent encrypted /event RTSP commands must decrypt without AEAD tag failure",
                readerDone.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )
            assertEquals(expectedMessages, receivedPlaintexts.size)
            session.close()
            acceptedSocket.close()
        }
    }

    @Test
    fun airPlayPeerValidationAcceptsDualStackLanIpv4AndIpv6UnicastPeers() {
        val ipv4ControlPeer = java.net.InetAddress.getByName("192.168.1.25")
        val ipv6LinkLocalStreamPeer = java.net.InetAddress.getByName("fe80::1a2b:3c4d:5e6f:7788")
        val ipv6UlaStreamPeer = java.net.InetAddress.getByName("fd12:3456:789a::42")
        val ipv6GlobalSlaacStreamPeer = java.net.InetAddress.getByName("240e:390:1000:1::88")
        val cgnatLanPeer = java.net.InetAddress.getByName("100.64.10.22")
        val loopbackPeer = java.net.InetAddress.getByName("127.0.0.1")

        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv4ControlPeer, ipv4ControlPeer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv6LinkLocalStreamPeer, ipv4ControlPeer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv6UlaStreamPeer, ipv4ControlPeer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(ipv6GlobalSlaacStreamPeer, ipv4ControlPeer))
        assertTrue(isSameOrAuthorizedAirPlayPeer(cgnatLanPeer, ipv4ControlPeer))
        assertFalse(isSameOrAuthorizedAirPlayPeer(loopbackPeer, ipv4ControlPeer))
    }

    @Test
    fun carPlayMediaEngineStopsAudioAndMicrophoneOnAbruptSessionCloseAndIsolatesStaleSessions() {
        val screenActiveState = mutableMapOf<Int, Boolean>()
        val stoppedAudioIds = mutableListOf<AudioStreamId>()
        val stoppedMicIds = mutableListOf<AudioStreamId>()

        val sink = object : MediaSink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                screenActiveState[type] = active
            }
            override fun onAudioStopped(id: AudioStreamId) {
                stoppedAudioIds += id
            }
            override fun onMicrophoneStopped(id: AudioStreamId) {
                stoppedMicIds += id
            }
        }

        val engine = CarPlayMediaEngine(sink = sink, microphoneEnabled = true)
        val config = TvStreamServerConfig(
            deviceName = "TVPlay",
            deviceId = "02:11:22:33:44:55",
            btMac = "02:11:22:33:44:55",
            sourceVersion = "950.7.1",
            main = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080),
        )

        java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val client1 = java.net.Socket("127.0.0.1", server.localPort)
            val peer1 = server.accept()
            val client2 = java.net.Socket("127.0.0.1", server.localPort)
            val peer2 = server.accept()
            try {
                val session1 = AirPlaySession(
                    socket = peer1,
                    config = config,
                    identity = AccessoryIdentity.generate(),
                    pairings = app.tvplay.engine.auth.PairingRegistry(),
                    mfi = null,
                    listener = object : AirPlaySessionListener {},
                    media = engine,
                )
                val session2 = AirPlaySession(
                    socket = peer2,
                    config = config,
                    identity = AccessoryIdentity.generate(),
                    pairings = app.tvplay.engine.auth.PairingRegistry(),
                    mfi = null,
                    listener = object : AirPlaySessionListener {},
                    media = engine,
                )
                val sharedField = session1.pairVerify.javaClass.getDeclaredField("sharedSecret").apply {
                    isAccessible = true
                }
                sharedField.set(session1.pairVerify, ByteArray(32) { (it + 1).toByte() })
                sharedField.set(session2.pairVerify, ByteArray(32) { (it + 33).toByte() })

                // 1. Abrupt disconnect without TEARDOWN must stop both audio and microphone on sink
                val telephonyStream = mapOf<String, Any?>(
                    "audioType" to "telephony",
                    "audioFormat" to 0x1000000L,
                    "shk" to ByteArray(32) { 0x11 },
                    "input" to true,
                    "dataPort" to 50100,
                    "controlPort" to 50101,
                    "streamConnectionID" to 101L,
                )
                val screenDesc = mapOf<String, Any?>(
                    "shk" to ByteArray(32) { 0x22 },
                    "streamConnectionID" to 102L,
                )
                assertNotNull(engine.onAudio(session1, 100, telephonyStream))
                assertNotNull(engine.onScreen(session1, 110, screenDesc))
                assertEquals(true, screenActiveState[110])

                stoppedAudioIds.clear()
                stoppedMicIds.clear()
                engine.onSessionClosed(session1)

                assertEquals(false, screenActiveState[110])
                assertTrue(
                    "onSessionClosed must stop active audio streams on sink",
                    AudioStreamId(100, "telephony") in stoppedAudioIds,
                )
                assertTrue(
                    "onSessionClosed must stop active microphone streams on sink",
                    AudioStreamId(100, "telephony") in stoppedMicIds,
                )

                // 2. Overlapping session2 setup before stale session1 closes must NOT tear down session2
                val mediaStream = mapOf<String, Any?>(
                    "audioType" to "media",
                    "audioFormat" to 0x40000L,
                    "shk" to ByteArray(32) { 0x33 },
                    "streamConnectionID" to 201L,
                )
                assertNotNull(engine.onScreen(session1, 110, screenDesc))
                assertNotNull(engine.onAudio(session1, 100, mediaStream))
                assertNotNull(engine.onScreen(session2, 110, screenDesc))
                assertNotNull(engine.onAudio(session2, 100, mediaStream))
                assertEquals(true, screenActiveState[110])

                stoppedAudioIds.clear()
                engine.onSessionClosed(session1)
                assertEquals(
                    "Closing stale session1 must not mark session2's active screen stream inactive",
                    true,
                    screenActiveState[110],
                )
                assertTrue(
                    "Closing stale session1 must not stop session2's active media audio stream",
                    stoppedAudioIds.isEmpty(),
                )

                engine.onSessionClosed(session2)
                assertEquals(false, screenActiveState[110])
                assertTrue(AudioStreamId(100, "media") in stoppedAudioIds)
            } finally {
                runCatching { client1.close() }
                runCatching { peer1.close() }
                runCatching { client2.close() }
                runCatching { peer2.close() }
            }
        }
    }
}
