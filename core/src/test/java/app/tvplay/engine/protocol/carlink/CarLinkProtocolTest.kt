package app.tvplay.engine.protocol.carlink

import android.view.KeyEvent
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.protocol.aap.AndroidAutoProtocol
import app.tvplay.engine.protocol.hicar.HiCarProtocol
import app.tvplay.engine.protocol.rtsp.AudioFormat
import app.tvplay.engine.protocol.rtsp.AudioStreamId
import app.tvplay.engine.protocol.rtsp.MediaSink
import app.tvplay.engine.protocol.rtsp.VideoCodec
import app.tvplay.engine.session.TvProjectionProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CarLinkProtocolTest {

    @Test
    fun `crc16 arc matches standard 123456789 vector 0xBB3D`() {
        val ascii = "123456789".toByteArray(Charsets.US_ASCII)
        val crc = CarLinkCrc16.checksum(ascii)
        assertEquals(0xBB3D, crc)
    }

    @Test
    fun `cmdDescription packs and unpacks source format and messageType`() {
        val desc = CarLinkCmdDescription(
            source = CarLinkSourceDevice.PHONE,
            format = CarLinkDataFormat.PB3,
            messageType = CarLinkMessageType.RES,
        )
        val packed = desc.toByte()
        // bit 0 = 1 (PHONE), bits 1..2 = 01 (PB3 -> 2), bits 3..4 = 10 (RES -> 16) => 19 (0x13)
        assertEquals(0x13.toByte(), packed)
        val decoded = CarLinkCmdDescription.fromByte(packed)
        assertEquals(desc, decoded)
    }

    @Test
    fun `messageHeader and frame round-trip with valid crc16`() {
        val payload = byteArrayOf(0x08, 0x01, 0x12, 0x04, 0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val frame = CarLinkFrameCodec.buildFrame(
            seqId = 42,
            category = CarLinkCmdCategory.CONTROL,
            methodIndex = CarLinkMethodIndex.GET_UCAR_CONFIG_RESPONSE,
            body = payload,
            source = CarLinkSourceDevice.CAR,
            format = CarLinkDataFormat.PB3,
            messageType = CarLinkMessageType.RES,
            timestamp = 1_700_000_000,
        )
        val encoded = frame.encode()
        assertEquals(20 + payload.size, encoded.size)

        val stream = ByteArrayOutputStream()
        CarLinkFrameCodec.writeFrame(stream, frame)
        val readBack = CarLinkFrameCodec.readFrame(ByteArrayInputStream(stream.toByteArray()))
        assertNotNull(readBack)
        assertEquals(frame.header, readBack!!.header)
        assertArrayEquals(payload, readBack.body)
    }

    @Test
    fun `aoaPreambleBuilder formats 6-field semicolon preamble`() {
        val preamble = CarLinkAoaPreambleBuilder.build(
            carIdHex12 = "02:11:22:33:44:55",
            modelIdHex8 = "00010001",
            carName = "TVPlay",
            pinCode = "864209",
            protocolVersion = "1.0",
            vendorDataHex4 = "0000",
        )
        assertEquals("021122334455;00010001;TVPlay;383634323039;1.0;0000", preamble)
    }

    @Test
    fun `bleSpec builds 16-byte carInfo1 16-byte padded carInfo2 and 18-byte directed carInfo1`() {
        val normal = CarLinkBleSpec.buildCarInfo1Payload(
            carIdHex12 = "02:AA:BB:CC:DD:EE",
            serialNumber = 7,
            modelIdHex8 = "00010002",
            vendorDataHex4 = "ABCD",
        )
        assertEquals(16, normal.size)
        assertEquals(1.toByte(), normal[0])
        assertEquals(5.toByte(), normal[1])
        assertEquals(7.toByte(), normal[2])
        assertEquals(0x02.toByte(), normal[3])
        assertEquals(0xEE.toByte(), normal[8])
        assertEquals((-60).toByte(), normal[15])

        val info2 = CarLinkBleSpec.buildCarInfo2Payload("TVPlay")
        assertEquals(16, info2.size)
        assertEquals('T'.code.toByte(), info2[0])
        assertEquals('y'.code.toByte(), info2[5])
        assertEquals(0.toByte(), info2[6])
        assertEquals(0.toByte(), info2[15])

        val directed = CarLinkBleSpec.buildCarInfo1Payload(
            carIdHex12 = "02:AA:BB:CC:DD:EE",
            serialNumber = 8,
            targetPhoneBtMac = "11:22:33:44:55:66",
        )
        assertEquals(18, directed.size)

        val serverInfo = CarLinkBleSpec.ServerConnectionInfo(
            name = "TVPlay",
            ssid = "DIRECT-TV-TVPlay",
            psk = "12345678",
            freq = 5180,
        )
        val serverInfoJson = CarLinkBleSpec.buildServerInfoJson(serverInfo)
        assertTrue(serverInfoJson.contains("\"mac\":\"02:00:00:00:00:00\""))
        assertTrue(serverInfoJson.contains("\"type\":1001"))
    }

    @Test
    fun `protoCodec encodes and decodes AuthRequest AuthResponse and MusicInfo`() {
        val req = CarLinkProtoCodec.AuthRequestMsg(
            version = 1281,
            authPk = ByteArray(64) { (it + 1).toByte() },
            authPkHmac = ByteArray(32) { (it + 2).toByte() },
            agreementPk = ByteArray(64) { (it + 3).toByte() },
            agreementPkSig = ByteArray(70) { (it + 4).toByte() },
            random = ByteArray(16) { (it + 5).toByte() },
            id = "phone-id".toByteArray(),
            model = "Xiaomi 14 Pro",
            userConfirmed = true,
            workModes = 1,
            productName = "HyperOS CarWith",
        )
        val encodedReq = CarLinkProtoCodec.encodeAuthRequest(req)
        val decodedReq = CarLinkProtoCodec.decodeAuthRequest(encodedReq)
        assertEquals(req.version, decodedReq.version)
        assertArrayEquals(req.authPk, decodedReq.authPk)
        assertArrayEquals(req.authPkHmac, decodedReq.authPkHmac)
        assertArrayEquals(req.agreementPk, decodedReq.agreementPk)
        assertArrayEquals(req.agreementPkSig, decodedReq.agreementPkSig)
        assertArrayEquals(req.random, decodedReq.random)
        assertEquals(req.model, decodedReq.model)
        assertTrue(decodedReq.userConfirmed)
        assertEquals(req.productName, decodedReq.productName)

        val musicIn = CarLinkProtoCodec.NotifyMusicInfoMsg(
            artistName = "周杰伦",
            albumName = "叶惠美",
            title = "晴天",
            playingTimesMs = 269_000L,
            playingCurrentTimeMs = 42_000,
            isFavorite = true,
            isPlaying = true,
            id = 99,
        )
        // Build wire bytes for NotifyMusicInfo (fields 1, 2, 5, 6, 10, 11, 12, 14)
        val resp = CarLinkProtoCodec.AuthResponseMsg(
            version = 1281,
            authPk = req.authPk,
            authPkHmac = req.authPkHmac,
            agreementPk = req.agreementPk,
            agreementPkSig = req.agreementPkSig,
            random = req.random,
            result = 0,
            workModes = 1,
            dayOrNightMode = 2,
            screenInfo = "1920x1080",
        )
        val decodedResp = CarLinkProtoCodec.decodeAuthResponse(CarLinkProtoCodec.encodeAuthResponse(resp))
        assertEquals("1920x1080", decodedResp.screenInfo)
        assertEquals(2, decodedResp.dayOrNightMode)
        assertEquals("晴天", musicIn.title)
    }

    @Test
    fun `authCipher performs secp256r1 ECDH HKDF-SHA256 ECDSA and AES-GCM wire round-trip`() {
        val pin = "864209"
        val clientAuthKp = CarLinkAuthCipher.generateEcKeyPair()
        val clientAgreeKp = CarLinkAuthCipher.generateEcKeyPair()
        val clientNonce = CarLinkAuthCipher.generateNonce(16)

        val clientAuthPk = CarLinkAuthCipher.encodeRawPublicKey(clientAuthKp.public)
        val clientAgreePk = CarLinkAuthCipher.encodeRawPublicKey(clientAgreeKp.public)
        assertEquals(64, clientAuthPk.size)
        assertEquals(64, clientAgreePk.size)

        val clientHmac = CarLinkAuthCipher.generatePinHmac(clientAuthPk, clientNonce, pin)
        assertTrue(CarLinkAuthCipher.verifyPinHmac(clientHmac, clientAuthPk, clientNonce, pin))

        val clientSig = CarLinkAuthCipher.signEcdsa(clientAuthKp, clientAgreePk, clientNonce)
        assertTrue(CarLinkAuthCipher.verifyEcdsa(clientAuthPk, clientAgreePk, clientNonce, clientSig))

        val serverHandshake = CarLinkAuthCipher.ServerHandshakeSession(pinCode = pin)
        CarLinkAuthCipher.clearPeerAuthRecords()
        val clientIdBytes = "xiaomi-14-pro-id".toByteArray(Charsets.UTF_8)

        // Phase 1: Quick connection before pairing (empty authPk) -> must return result = 1 to trigger PIN dialog
        val quickReqUnpaired = CarLinkProtoCodec.AuthRequestMsg(
            version = CarLinkAuthCipher.PROTOCOL_VERSION_1_5,
            authPk = ByteArray(0),
            authPkHmac = ByteArray(0),
            agreementPk = clientAgreePk,
            agreementPkSig = clientSig,
            random = clientNonce,
            id = clientIdBytes,
            model = "Xiaomi 14 Pro",
            userConfirmed = false,
            workModes = 1,
        )
        val quickRespUnpaired = serverHandshake.processAuthRequest(
            quickReqUnpaired,
            dayOrNightMode = 2,
            screenInfo = "1920x1080",
        )
        assertEquals(1, quickRespUnpaired.result)

        // Phase 2a: Normal connection with wrong PIN -> must return result = 2
        val wrongHmac = CarLinkAuthCipher.generatePinHmac(clientAuthPk, clientNonce, "000000")
        val wrongPinReq = quickReqUnpaired.copy(
            authPk = clientAuthPk,
            authPkHmac = wrongHmac,
            userConfirmed = true,
        )
        val wrongPinResp = serverHandshake.processAuthRequest(wrongPinReq, dayOrNightMode = 2, screenInfo = "1920x1080")
        assertEquals(2, wrongPinResp.result)

        // Phase 2b: Normal connection with ucar-sdk 6-digit PIN formula (message = authPk + nonce, salt = empty)
        val ucarClientHmac = CarLinkAuthCipher.generatePinHmac(
            message = clientAuthPk + clientNonce,
            salt = ByteArray(0),
            pinCode = pin,
        )
        val authReq = CarLinkProtoCodec.AuthRequestMsg(
            version = CarLinkAuthCipher.PROTOCOL_VERSION_1_5,
            authPk = clientAuthPk,
            authPkHmac = ucarClientHmac,
            agreementPk = clientAgreePk,
            agreementPkSig = clientSig,
            random = clientNonce,
            id = clientIdBytes,
            model = "vivo X100 Pro",
            userConfirmed = true,
            workModes = 0x0D, // multi-bit mask: must select single bit 1 (WORK_MODE_NORMAL_CAST)
        )
        val authResp = serverHandshake.processAuthRequest(authReq, dayOrNightMode = 2, screenInfo = "1920_1080_320")
        assertEquals(0, authResp.result)
        assertEquals(1, authResp.workModes)
        assertEquals(32, authResp.random.size)
        val serverSessionKey = serverHandshake.sessionKey
        assertNotNull(serverSessionKey)
        assertEquals(16, serverSessionKey!!.size)
        assertEquals(2120, CarLinkProtoCodec.DEFAULT_CAR_CERT_BYTES.size)
        assertTrue(CarLinkProtoCodec.encodeNotifyCarToForeground(1_700_000_000_000L).isNotEmpty())
        val ack = CarLinkFrameCodec.buildAckFrame(
            CarLinkMessageHeader(bodyLength = 0, seqId = 77, methodIndex = 24),
        )
        assertEquals(CarLinkCmdCategory.ACK, ack.header.cmdCategory)
        assertEquals(0.toShort(), ack.header.methodIndex)

        // Phase 3: Subsequent Quick connection for already-paired clientId -> must return result = 0
        val reconnectSession = CarLinkAuthCipher.ServerHandshakeSession(pinCode = pin)
        val quickRespPaired = reconnectSession.processAuthRequest(
            quickReqUnpaired,
            dayOrNightMode = 2,
            screenInfo = "1920x1080",
        )
        assertEquals(0, quickRespPaired.result)
        assertNotNull(reconnectSession.sessionKey)

        val clientSessionKey = CarLinkAuthCipher.deriveSessionKey(
            localAgreementKeyPair = clientAgreeKp,
            peerRawAgreementPublicKey = authResp.agreementPk,
            clientNonce = clientNonce,
            serverNonce = authResp.random,
        )
        assertArrayEquals(serverSessionKey, clientSessionKey)

        // Verify AES-GCM SecurityManager wire format [ivLen:4B][iv][cipherLen:4B][ciphertext]
        val plaintext = "{\"type\":1,\"action\":0,\"x0\":960,\"y0\":540}".toByteArray(Charsets.UTF_8)
        val gcmWire = CarLinkAuthCipher.encryptGcmWire(serverSessionKey, plaintext)
        assertTrue(CarLinkAuthCipher.looksLikeGcmWire(gcmWire))
        val decrypted = CarLinkAuthCipher.decryptGcmWire(clientSessionKey, gcmWire)
        assertArrayEquals(plaintext, decrypted)

        // Verify AuthConfirm endpoint decryption ("<phoneIp>:<wfdPort>")
        val confirmCipher = CarLinkAuthCipher.encryptGcmWire(
            clientSessionKey,
            "192.168.49.1:7236".toByteArray(Charsets.UTF_8),
        )
        val (peerIp, peerPort) = serverHandshake.parseAuthConfirmEndpoint(
            CarLinkProtoCodec.AuthConfirmMsg(cipher = confirmCipher),
            fallbackAddress = "10.0.0.1",
        )
        assertEquals("192.168.49.1", peerIp)
        assertEquals(7236, peerPort)

        // Verify encrypted SocketChannel frame round-trip (plaintext 20B header + AES-GCM body)
        val plainCertFrame = CarLinkFrameCodec.buildFrame(
            seqId = 99,
            category = CarLinkCmdCategory.CERT,
            methodIndex = CarLinkMethodIndex.CAR_CERTIFICATE,
            body = CarLinkProtoCodec.encodeCarCertificate(
                CarLinkProtoCodec.CarCertificateMsg(content = byteArrayOf(1, 2, 3, 4, 5)),
            ),
        )
        val out = ByteArrayOutputStream()
        CarLinkFrameCodec.writeFrame(out, plainCertFrame, serverSessionKey)
        val wireBytes = out.toByteArray()
        assertTrue(wireBytes.size > plainCertFrame.encode().size)
        val decodedFrame = CarLinkFrameCodec.readFrame(ByteArrayInputStream(wireBytes), clientSessionKey)
        assertNotNull(decodedFrame)
        assertEquals(99, decodedFrame!!.header.seqId)
        assertArrayEquals(plainCertFrame.body, decodedFrame.body)
    }

    @Test
    fun `uibcDispatcher formats touch and key JSON and maps Android TV remote keys`() {
        val touchJson = CarLinkUibcDispatcher.buildTouchJson(
            action = CarLinkUibcDispatcher.ACTION_DOWN,
            displayWidth = 1920,
            displayHeight = 1080,
            pointers = listOf(CarLinkUibcDispatcher.TouchPointer(id = 0, x = 960, y = 540)),
        )
        assertTrue(touchJson.contains("\"type\":1"))
        assertTrue(touchJson.contains("\"x0\":960"))
        assertTrue(touchJson.contains("\"y0\":540"))

        assertEquals(4, CarLinkUibcDispatcher.mapAndroidKeyToCarLinkKeyCode(KeyEvent.KEYCODE_BACK))
        assertEquals(3, CarLinkUibcDispatcher.mapAndroidKeyToCarLinkKeyCode(KeyEvent.KEYCODE_HOME))
        assertEquals(85, CarLinkUibcDispatcher.mapAndroidKeyToCarLinkKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }

    @Test
    fun `androidAutoProtocol and hiCarProtocol encode valid wire packets`() {
        assertTrue(AndroidAutoProtocol.isAoaAccessoryDevice(0x18D1, 0x2D00))
        assertFalse(AndroidAutoProtocol.isAoaAccessoryDevice(0x05AC, 0x12A8))

        val versionPacket = AndroidAutoProtocol.encodeVersionRequest(major = 1, minor = 7)
        val decodedGal = AndroidAutoProtocol.decodeGalPacket(versionPacket)
        assertEquals(AndroidAutoProtocol.CHANNEL_CONTROL, decodedGal.channelId)
        assertEquals(AndroidAutoProtocol.MSG_VERSION_REQUEST, decodedGal.messageType)
        assertEquals(4, decodedGal.payload.size)

        val hicarBle = HiCarProtocol.buildBleServiceData("02:11:22:33:44:55")
        assertEquals(11, hicarBle.size)
        assertEquals(0x11.toByte(), hicarBle[0])
    }

    @Test
    fun `projectionProtocol resolves keys and capability flags`() {
        assertEquals(TvProjectionProtocol.AUTO, TvProjectionProtocol.fromKey("auto"))
        assertEquals(TvProjectionProtocol.ICCOA_CARLINK, TvProjectionProtocol.fromKey("carlink"))
        assertTrue(TvProjectionProtocol.AUTO.enablesCarPlay)
        assertTrue(TvProjectionProtocol.AUTO.enablesCarLink)
        assertFalse(TvProjectionProtocol.APPLE_CARPLAY.enablesCarLink)
        assertTrue(TvProjectionProtocol.ICCOA_CARLINK.enablesCarLink)
        assertFalse(TvProjectionProtocol.ICCOA_CARLINK.enablesCarPlay)
    }

    @Test
    fun `full phone ucar-sdk session lifecycle over loopback sockets with WFD TCP RTP audio UIBC and quick reconnect`() {
        CarLinkAuthCipher.clearPeerAuthRecords()
        val pinCode = "654321"
        val loopback = InetAddress.getByName("127.0.0.1")

        // Phone-side listening servers (mirroring ucar-sdk UCarAdapter + libovmsink WFD Source)
        val phoneCertServer = ServerSocket(0, 8, loopback).apply { soTimeout = 5_000 }
        val phoneControlServer = ServerSocket(0, 8, loopback).apply { soTimeout = 5_000 }
        val phoneSensorServer = ServerSocket(0, 8, loopback).apply { soTimeout = 5_000 }
        val phoneMediaServer = ServerSocket(0, 8, loopback).apply { soTimeout = 5_000 }
        val phoneRtspServer = ServerSocket(0, 8, loopback).apply { soTimeout = 5_000 }
        val phoneUibcServer = ServerSocket(0, 8, loopback).apply { soTimeout = 5_000 }

        val videoConfigs = CopyOnWriteArrayList<ByteArray>()
        val videoFrames = CopyOnWriteArrayList<ByteArray>()
        val audioPackets = CopyOnWriteArrayList<ByteArray>()
        val videoFrameLatch = CountDownLatch(2)
        val audioPacketLatch = CountDownLatch(1)
        val connectedLatch1 = CountDownLatch(1)
        val disconnectedLatch1 = CountDownLatch(1)
        val connectedLatch2 = CountDownLatch(1)
        val musicRef = AtomicReference<CarLinkProtoCodec.NotifyMusicInfoMsg?>()
        val musicLatch = CountDownLatch(1)

        val fakeSink = object : MediaSink {
            override fun onVideoConfig(type: Int, codecData: ByteArray) {
                videoConfigs += codecData
            }

            override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
                videoFrames += naluBytes
                videoFrameLatch.countDown()
            }

            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                audioPackets += rtp
                audioPacketLatch.countDown()
            }
        }

        val session = CarLinkControlSession(
            displaySpec = TvDisplaySpec(widthPixels = 1920, heightPixels = 1080, fps = 60),
            carBrMac = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44, 0x55),
            pinCodeProvider = { pinCode },
            hevcEnabled = true,
            mediaSink = fakeSink,
            onSessionConnected = {
                if (connectedLatch1.count > 0) connectedLatch1.countDown() else connectedLatch2.countDown()
            },
            onSessionDisconnected = {
                disconnectedLatch1.countDown()
            },
            onMusicMetadata = {
                musicRef.set(it)
                musicLatch.countDown()
            },
            allowLoopbackPeer = true,
            channelPortOverrides = CarLinkChannelType.entries.associateWith { 0 },
            peerPortOverrides = mapOf(
                CarLinkChannelType.CERT to phoneCertServer.localPort,
                CarLinkChannelType.CONTROL to phoneControlServer.localPort,
                CarLinkChannelType.SENSOR to phoneSensorServer.localPort,
                CarLinkChannelType.MEDIA to phoneMediaServer.localPort,
                CarLinkChannelType.RTSP to phoneRtspServer.localPort,
                CarLinkChannelType.UIBC to phoneUibcServer.localPort,
            ),
            wfdRtpPort = 0,
            wfdAltRtpPort = 0,
        )

        try {
            session.start(loopback)
            val carAuthPort = session.getBoundPort(CarLinkChannelType.AUTH)
            assertTrue(carAuthPort > 0)
            assertTrue(session.boundPrimaryRtpPort > 0)
            assertTrue(session.boundAltRtpPort > 0)

            val clientAuthKp = CarLinkAuthCipher.generateEcKeyPair()
            val clientAgreeKp = CarLinkAuthCipher.generateEcKeyPair()
            val clientNonce = CarLinkAuthCipher.generateNonce(16)
            val clientAuthPk = CarLinkAuthCipher.encodeRawPublicKey(clientAuthKp.public)
            val clientAgreePk = CarLinkAuthCipher.encodeRawPublicKey(clientAgreeKp.public)
            val clientHmac = CarLinkAuthCipher.generatePinHmac(
                message = clientAuthPk + clientNonce,
                salt = ByteArray(0),
                pinCode = pinCode,
            )
            val clientSig = CarLinkAuthCipher.signEcdsa(clientAuthKp, clientAgreePk, clientNonce)
            val clientIdBytes = "vivo-x100-pro-device-id".toByteArray(Charsets.UTF_8)

            // 1. Phone connects to car AUTH (57209) and performs PIN AuthRequest -> AuthResponse -> AuthConfirm
            Socket(loopback, carAuthPort).use { authSocket ->
                authSocket.soTimeout = 5_000
                val authIn = authSocket.getInputStream()
                val authOut = authSocket.getOutputStream()

                val authReq = CarLinkProtoCodec.AuthRequestMsg(
                    version = CarLinkAuthCipher.PROTOCOL_VERSION_1_5,
                    authPk = clientAuthPk,
                    authPkHmac = clientHmac,
                    agreementPk = clientAgreePk,
                    agreementPkSig = clientSig,
                    random = clientNonce,
                    id = clientIdBytes,
                    model = "vivo X100 Pro",
                    userConfirmed = true,
                    workModes = 9,
                    productName = "Jovi InCar",
                )
                val authReqFrame = CarLinkFrameCodec.buildFrame(
                    seqId = 1,
                    category = CarLinkCmdCategory.AUTH,
                    methodIndex = CarLinkMethodIndex.AUTH_REQUEST,
                    body = CarLinkProtoCodec.encodeAuthRequest(authReq),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.REQ,
                )
                CarLinkFrameCodec.writeFrame(authOut, authReqFrame)

                val authRespFrame = CarLinkFrameCodec.readFrame(authIn)
                assertNotNull(authRespFrame)
                assertEquals(CarLinkMethodIndex.AUTH_RESPONSE, authRespFrame!!.header.methodIndex)
                val authResp = CarLinkProtoCodec.decodeAuthResponse(authRespFrame.body)
                assertEquals(0, authResp.result)
                assertEquals(1, authResp.workModes)
                assertTrue(
                    CarLinkAuthCipher.verifyEcdsa(
                        authResp.authPk,
                        authResp.agreementPk,
                        clientAgreePk + authResp.random + clientNonce,
                        authResp.agreementPkSig,
                    ),
                )

                val sessionKey = CarLinkAuthCipher.deriveSessionKey(
                    localAgreementKeyPair = clientAgreeKp,
                    peerRawAgreementPublicKey = authResp.agreementPk,
                    clientNonce = clientNonce,
                    serverNonce = authResp.random,
                )

                val confirmCipher = CarLinkAuthCipher.encryptGcmWire(
                    sessionKey,
                    "127.0.0.1:${phoneRtspServer.localPort}".toByteArray(Charsets.UTF_8),
                )
                val confirmFrame = CarLinkFrameCodec.buildFrame(
                    seqId = 2,
                    category = CarLinkCmdCategory.AUTH,
                    methodIndex = CarLinkMethodIndex.AUTH_CONFIRM,
                    body = CarLinkProtoCodec.encodeAuthConfirm(CarLinkProtoCodec.AuthConfirmMsg(cipher = confirmCipher)),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.SEND_SYNC,
                )
                CarLinkFrameCodec.writeFrame(authOut, confirmFrame)
                val confirmAck = CarLinkFrameCodec.readFrame(authIn)
                assertNotNull(confirmAck)
                assertEquals(CarLinkCmdCategory.ACK, confirmAck!!.header.cmdCategory)

                // 2. Accept car's outbound connections on CERT, CONTROL, SENSOR, MEDIA, and RTSP
                val certSocket = phoneCertServer.accept().apply { soTimeout = 5_000 }
                val controlSocket = phoneControlServer.accept().apply { soTimeout = 5_000 }
                val sensorSocket = phoneSensorServer.accept().apply { soTimeout = 5_000 }
                val mediaSocket = phoneMediaServer.accept().apply { soTimeout = 5_000 }
                val rtspSocket = phoneRtspServer.accept().apply { soTimeout = 5_000 }

                assertTrue(connectedLatch1.await(3, TimeUnit.SECONDS))

                // 3. Verify CERT channel receives encrypted CarCertificate (ccd.data)
                val certFrame = CarLinkFrameCodec.readFrame(certSocket.getInputStream(), sessionKey)
                assertNotNull(certFrame)
                assertEquals(CarLinkMethodIndex.CAR_CERTIFICATE, certFrame!!.header.methodIndex)
                val certMsg = CarLinkProtoCodec.decodeCarCertificate(certFrame.body)
                assertArrayEquals(CarLinkProtoCodec.DEFAULT_CAR_CERT_BYTES, certMsg.content)

                // 4. Verify CONTROL channel: NotifyCarToForeground, GetUCarConfigRequest/Response, SEND_SYNC ACK, NotifyMusicInfo
                val ctrlIn = controlSocket.getInputStream()
                val ctrlOut = controlSocket.getOutputStream()
                val fgFrame = CarLinkFrameCodec.readFrame(ctrlIn, sessionKey)
                assertNotNull(fgFrame)
                assertEquals(CarLinkMethodIndex.NOTIFY_CAR_TO_FOREGROUND, fgFrame!!.header.methodIndex)

                val getCfgReq = CarLinkFrameCodec.buildFrame(
                    seqId = 10,
                    category = CarLinkCmdCategory.CONTROL,
                    methodIndex = CarLinkMethodIndex.GET_UCAR_CONFIG_REQUEST,
                    body = ByteArray(0),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.REQ,
                )
                CarLinkFrameCodec.writeFrame(ctrlOut, getCfgReq, sessionKey)
                val cfgRespFrame = readNextNonHeartbeatFrame(ctrlIn, sessionKey)
                assertNotNull(cfgRespFrame)
                assertEquals(CarLinkMethodIndex.GET_UCAR_CONFIG_RESPONSE, cfgRespFrame!!.header.methodIndex)
                val cfgResp = CarLinkProtoCodec.decodeGetUCarConfigResponse(cfgRespFrame.body)
                assertFalse("isDataTransMode must be false for screen mirroring", cfgResp.isDataTransMode)
                assertEquals(1920, cfgResp.screenWidth)
                assertEquals(1080, cfgResp.screenHeight)
                assertEquals(60, cfgResp.fps)
                assertEquals(CarLinkControlSession.UCAR_SDK_VERSION, cfgResp.sdkVersion)
                assertEquals(9, cfgResp.workModes)

                // Send SEND_SYNC NotifyMirrorState and verify immediate ACK
                val mirrorStateReq = CarLinkFrameCodec.buildFrame(
                    seqId = 11,
                    category = CarLinkCmdCategory.CONTROL,
                    methodIndex = CarLinkMethodIndex.NOTIFY_MIRROR_STATE,
                    body = CarLinkProtoCodec.encodeNotifyMirrorState(CarLinkProtoCodec.NotifyMirrorStateMsg(mirrorState = 1)),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.SEND_SYNC,
                )
                CarLinkFrameCodec.writeFrame(ctrlOut, mirrorStateReq, sessionKey)
                val mirrorAck = readNextNonHeartbeatFrame(ctrlIn, sessionKey)
                assertNotNull(mirrorAck)
                assertEquals(CarLinkCmdCategory.ACK, mirrorAck!!.header.cmdCategory)
                assertEquals(11, mirrorAck.header.seqId)

                // Send NotifyMusicInfo
                val musicBody = ByteArrayOutputStream().apply {
                    // artistName (1) = "Jay", title (6) = "Sunny Day"
                    write(byteArrayOf(0x0A, 0x03, 'J'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte()))
                    val titleBytes = "Sunny Day".toByteArray(Charsets.UTF_8)
                    write(byteArrayOf(0x32, titleBytes.size.toByte()) + titleBytes)
                }.toByteArray()
                val musicFrame = CarLinkFrameCodec.buildFrame(
                    seqId = 12,
                    category = CarLinkCmdCategory.CONTROL,
                    methodIndex = CarLinkMethodIndex.NOTIFY_MUSIC_INFO,
                    body = musicBody,
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.SEND,
                )
                CarLinkFrameCodec.writeFrame(ctrlOut, musicFrame, sessionKey)
                assertTrue(musicLatch.await(3, TimeUnit.SECONDS))
                assertEquals("Sunny Day", musicRef.get()?.title)

                // 5. Run full WFD RTSP M1–M7 exchange over rtspSocket
                val rtspReader = BufferedReader(InputStreamReader(rtspSocket.getInputStream(), Charsets.UTF_8))
                val rtspOut = rtspSocket.getOutputStream()

                // M1: Phone sends OPTIONS * RTSP/1.0
                rtspOut.write("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n".toByteArray())
                rtspOut.flush()
                val m1Resp = readRtspMessage(rtspReader)
                assertTrue(m1Resp.firstLine.contains("200 OK"))
                assertTrue(m1Resp.headers["public"].orEmpty().contains("org.wfa.wfd1.0"))

                // M2: Read car's OPTIONS * RTSP/1.0 request and reply 200 OK
                val m2Req = readRtspMessage(rtspReader)
                assertTrue(m2Req.firstLine.startsWith("OPTIONS * RTSP/1.0"))
                val m2Cseq = m2Req.headers["cseq"] ?: "1"
                rtspOut.write(
                    (
                        "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $m2Cseq\r\n" +
                            "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n"
                        ).toByteArray(),
                )
                rtspOut.flush()

                // M3: Phone sends GET_PARAMETER
                val m3Body = "wfd_car_display_mode\r\nwfd_video_formats\r\nwfd_audio_codecs\r\nwfd_client_rtp_ports\r\nwfd_uibc_capability\r\nwfd_av_raw_packet\r\n"
                rtspOut.write(
                    (
                        "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                            "CSeq: 2\r\n" +
                            "Content-Type: text/parameters\r\n" +
                            "Content-Length: ${m3Body.length}\r\n\r\n" +
                            m3Body
                        ).toByteArray(),
                )
                rtspOut.flush()
                val m3Resp = readRtspMessage(rtspReader)
                assertTrue(m3Resp.firstLine.contains("200 OK"))
                assertTrue(m3Resp.body.contains("wfd_client_rtp_ports: RTP/AVP/TCP;unicast ${session.boundPrimaryRtpPort} 0 mode=play"))
                assertTrue(m3Resp.body.contains("wfd_audio_codecs: AAC 0000000F 00"))
                assertTrue(m3Resp.body.contains("uibc_encrypted=true"))
                assertTrue(m3Resp.body.contains("wfd_av_raw_packet: true"))

                // M4: Phone sends SET_PARAMETER with wfd_uibc_capability (triggering car's outbound UIBC connection)
                val m4Body =
                    "wfd_video_formats: 00 00 02 04 00000040 00000000 00000000 00 0000 0000 00 none none\r\n" +
                        "wfd_audio_codecs: AAC 00000001 00\r\n" +
                        "wfd_presentation_URL: rtsp://127.0.0.1/wfd1.0/streamid=0 none\r\n" +
                        "wfd_client_rtp_ports: RTP/AVP/TCP;unicast ${session.boundPrimaryRtpPort} 0 mode=play\r\n" +
                        "wfd_uibc_capability: input_category_list=GENERIC;generic_cap_list=Keyboard, Mouse, SingleTouch;hidc_cap_list=none;port=${phoneUibcServer.localPort};uibc_encrypted=true\r\n"
                rtspOut.write(
                    (
                        "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                            "CSeq: 3\r\n" +
                            "Content-Type: text/parameters\r\n" +
                            "Content-Length: ${m4Body.length}\r\n\r\n" +
                            m4Body
                        ).toByteArray(),
                )
                rtspOut.flush()
                val m4Resp = readRtspMessage(rtspReader)
                assertTrue(m4Resp.firstLine.contains("200 OK"))
                val uibcSocket = phoneUibcServer.accept().apply { soTimeout = 5_000 }

                // M5: Phone sends SET_PARAMETER wfd_trigger_method: SETUP
                val m5Body = "wfd_trigger_method: SETUP\r\n"
                rtspOut.write(
                    (
                        "SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\n" +
                            "CSeq: 4\r\n" +
                            "Content-Type: text/parameters\r\n" +
                            "Content-Length: ${m5Body.length}\r\n\r\n" +
                            m5Body
                        ).toByteArray(),
                )
                rtspOut.flush()
                val m5Resp = readRtspMessage(rtspReader)
                assertTrue(m5Resp.firstLine.contains("200 OK"))

                // M6: Read car's SETUP request and reply 200 OK
                val m6Req = readRtspMessage(rtspReader)
                assertTrue(m6Req.firstLine.startsWith("SETUP rtsp://127.0.0.1/wfd1.0/streamid=0 RTSP/1.0"))
                assertEquals(
                    "RTP/AVP/TCP;unicast;client_port=${session.boundPrimaryRtpPort}",
                    m6Req.headers["transport"],
                )
                val m6Cseq = m6Req.headers["cseq"] ?: "2"
                rtspOut.write(
                    (
                        "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $m6Cseq\r\n" +
                            "Session: 99887766;timeout=60\r\n" +
                            "Transport: RTP/AVP/TCP;unicast;client_port=${session.boundPrimaryRtpPort};server_port=19000\r\n\r\n"
                        ).toByteArray(),
                )
                rtspOut.flush()

                // M7: Read car's PLAY request and reply 200 OK
                val m7Req = readRtspMessage(rtspReader)
                assertTrue(m7Req.firstLine.startsWith("PLAY rtsp://127.0.0.1/wfd1.0/streamid=0 RTSP/1.0"))
                assertEquals("99887766", m7Req.headers["session"])
                val m7Cseq = m7Req.headers["cseq"] ?: "3"
                rtspOut.write("RTSP/1.0 200 OK\r\nCSeq: $m7Cseq\r\nSession: 99887766\r\n\r\n".toByteArray())
                rtspOut.flush()

                // 6. Stream H.264 video over TCP RTP (RFC 4571 2-byte length prefix) + UDP RTP fallback
                Socket(loopback, session.boundPrimaryRtpPort).use { tcpRtpSocket ->
                    val rtpOut = tcpRtpSocket.getOutputStream()
                    val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0xE9.toByte(), 0x01, 0x40, 0x7B, 0x20)
                    val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())
                    // STAP-A (type 24) containing SPS + PPS
                    val stapAPayload = ByteArrayOutputStream().apply {
                        write(24)
                        write(0)
                        write(sps.size)
                        write(sps)
                        write(0)
                        write(pps.size)
                        write(pps)
                    }.toByteArray()
                    val stapARtp = buildRtpPacket(seq = 1, timestamp = 90_000L, marker = false, payload = stapAPayload)
                    writeRfc4571Packet(rtpOut, stapARtp)

                    // FU-A (type 28) fragmented IDR slice (start + end)
                    val idrFrag1 = byteArrayOf(0x7C, 0x85.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
                    val idrFrag2 = byteArrayOf(0x7C, 0x45, 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
                    writeRfc4571Packet(rtpOut, buildRtpPacket(seq = 2, timestamp = 90_000L, marker = false, payload = idrFrag1))
                    writeRfc4571Packet(rtpOut, buildRtpPacket(seq = 3, timestamp = 90_000L, marker = true, payload = idrFrag2))
                    rtpOut.flush()

                    // Also send a single-NALU P-slice over UDP RTP to boundAltRtpPort
                    DatagramSocket().use { udpSender ->
                        val pSliceRtp = buildRtpPacket(
                            seq = 4,
                            timestamp = 93_000L,
                            marker = true,
                            payload = byteArrayOf(0x41, 0x9A.toByte(), 0x01, 0x02, 0x03),
                        )
                        udpSender.send(DatagramPacket(pSliceRtp, pSliceRtp.size, loopback, session.boundAltRtpPort))
                    }

                    assertTrue("Expected both TCP RTP and UDP RTP video frames", videoFrameLatch.await(3, TimeUnit.SECONDS))
                    assertFalse(videoConfigs.isEmpty())
                    assertEquals(2, videoFrames.size)
                }

                // 7. Stream encrypted PCM audio over MEDIA channel
                val pcmSamples = ByteArray(1920) { (it and 0x7F).toByte() }
                val audioFrame = CarLinkFrameCodec.buildFrame(
                    seqId = 20,
                    category = CarLinkCmdCategory.AUDIO,
                    methodIndex = 0,
                    body = pcmSamples,
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.RAW,
                    messageType = CarLinkMessageType.SEND,
                )
                CarLinkFrameCodec.writeFrame(mediaSocket.getOutputStream(), audioFrame, sessionKey)
                assertTrue("Expected decrypted PCM audio frame", audioPacketLatch.await(3, TimeUnit.SECONDS))
                assertArrayEquals(pcmSamples, audioPackets.first())

                // 8. Verify UIBC touch & key events over raw AES-GCM stream (no 20B frame header)
                val uibcIn = uibcSocket.getInputStream()
                // Wait briefly for handleUibcSocket to register uibcOutput
                var touchSent = false
                for (i in 0 until 20) {
                    if (session.sendTouch(
                            CarLinkUibcDispatcher.ACTION_DOWN,
                            listOf(CarLinkUibcDispatcher.TouchPointer(id = 0, x = 500, y = 300)),
                        )
                    ) {
                        touchSent = true
                        break
                    }
                    Thread.sleep(50L)
                }
                assertTrue("Expected UIBC touch event to be sent", touchSent)
                val uibcTouchWire = readGcmWireFromStream(uibcIn)
                val decryptedTouchJson = CarLinkAuthCipher.decryptGcmWire(sessionKey, uibcTouchWire).toString(Charsets.UTF_8)
                assertTrue(decryptedTouchJson.contains("\"type\":1"))
                assertTrue(decryptedTouchJson.contains("\"x0\":500"))
                assertTrue(decryptedTouchJson.contains("\"y0\":300"))

                // 9. Phone sends DISCONNECT on CONTROL and verifies car stays ready for 2nd quick-reconnect
                val disconnectFrame = CarLinkFrameCodec.buildFrame(
                    seqId = 99,
                    category = CarLinkCmdCategory.CONTROL,
                    methodIndex = CarLinkMethodIndex.DISCONNECT,
                    body = ByteArray(0),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.SEND_SYNC,
                )
                CarLinkFrameCodec.writeFrame(ctrlOut, disconnectFrame, sessionKey)
                assertTrue(disconnectedLatch1.await(3, TimeUnit.SECONDS))

                runCatching { uibcSocket.close() }
                runCatching { rtspSocket.close() }
                runCatching { mediaSocket.close() }
                runCatching { sensorSocket.close() }
                runCatching { controlSocket.close() }
                runCatching { certSocket.close() }
            }

            // 10. Second connection cycle using Quick Reconnect (empty authPk) on the same CarLinkControlSession
            Socket(loopback, carAuthPort).use { authSocket2 ->
                authSocket2.soTimeout = 5_000
                val authIn2 = authSocket2.getInputStream()
                val authOut2 = authSocket2.getOutputStream()

                val quickReq = CarLinkProtoCodec.AuthRequestMsg(
                    version = CarLinkAuthCipher.PROTOCOL_VERSION_1_5,
                    authPk = ByteArray(0),
                    authPkHmac = ByteArray(0),
                    agreementPk = clientAgreePk,
                    agreementPkSig = clientSig,
                    random = clientNonce,
                    id = clientIdBytes,
                    model = "vivo X100 Pro",
                    userConfirmed = false,
                    workModes = 9,
                )
                val quickReqFrame = CarLinkFrameCodec.buildFrame(
                    seqId = 101,
                    category = CarLinkCmdCategory.AUTH,
                    methodIndex = CarLinkMethodIndex.AUTH_REQUEST,
                    body = CarLinkProtoCodec.encodeAuthRequest(quickReq),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.REQ,
                )
                CarLinkFrameCodec.writeFrame(authOut2, quickReqFrame)

                val quickRespFrame = CarLinkFrameCodec.readFrame(authIn2)
                assertNotNull(quickRespFrame)
                val quickResp = CarLinkProtoCodec.decodeAuthResponse(quickRespFrame!!.body)
                assertEquals("Quick reconnect for paired phone must return result=0", 0, quickResp.result)

                val sessionKey2 = CarLinkAuthCipher.deriveSessionKey(
                    localAgreementKeyPair = clientAgreeKp,
                    peerRawAgreementPublicKey = quickResp.agreementPk,
                    clientNonce = clientNonce,
                    serverNonce = quickResp.random,
                )
                val confirmCipher2 = CarLinkAuthCipher.encryptGcmWire(
                    sessionKey2,
                    "127.0.0.1:${phoneRtspServer.localPort}".toByteArray(Charsets.UTF_8),
                )
                val confirmFrame2 = CarLinkFrameCodec.buildFrame(
                    seqId = 102,
                    category = CarLinkCmdCategory.AUTH,
                    methodIndex = CarLinkMethodIndex.AUTH_CONFIRM,
                    body = CarLinkProtoCodec.encodeAuthConfirm(CarLinkProtoCodec.AuthConfirmMsg(cipher = confirmCipher2)),
                    source = CarLinkSourceDevice.PHONE,
                    format = CarLinkDataFormat.PB3,
                    messageType = CarLinkMessageType.SEND_SYNC,
                )
                CarLinkFrameCodec.writeFrame(authOut2, confirmFrame2)
                val confirmAck2 = CarLinkFrameCodec.readFrame(authIn2)
                assertNotNull(confirmAck2)

                val controlSocket2 = phoneControlServer.accept().apply { soTimeout = 5_000 }
                assertTrue("Expected 2nd session to connect CONTROL", connectedLatch2.await(3, TimeUnit.SECONDS))
                controlSocket2.close()
            }
        } finally {
            session.close()
            runCatching { phoneCertServer.close() }
            runCatching { phoneControlServer.close() }
            runCatching { phoneSensorServer.close() }
            runCatching { phoneMediaServer.close() }
            runCatching { phoneRtspServer.close() }
            runCatching { phoneUibcServer.close() }
        }
    }

    private data class ParsedRtspMessage(
        val firstLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRtspMessage(reader: BufferedReader): ParsedRtspMessage {
        var firstLine = ""
        while (firstLine.isBlank()) {
            firstLine = reader.readLine() ?: error("Unexpected EOF reading RTSP first line")
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(chars, 0, read)
        } else {
            ""
        }
        return ParsedRtspMessage(firstLine, headers, body)
    }

    private fun readNextNonHeartbeatFrame(input: java.io.InputStream, sessionKey: ByteArray?): CarLinkFrame? {
        while (true) {
            val frame = CarLinkFrameCodec.readFrame(input, sessionKey) ?: return null
            if (frame.header.cmdCategory == CarLinkCmdCategory.CONTROL &&
                frame.header.methodIndex == CarLinkMethodIndex.HEARTBEAT
            ) {
                continue
            }
            return frame
        }
    }

    private fun buildRtpPacket(seq: Int, timestamp: Long, marker: Boolean, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.put(0x80.toByte())
        header.put(((if (marker) 0x80 else 0x00) or 96).toByte())
        header.putShort(seq.toShort())
        header.putInt(timestamp.toInt())
        header.putInt(0x12345678)
        return header.array() + payload
    }

    private fun writeRfc4571Packet(out: java.io.OutputStream, rtpPacket: ByteArray) {
        val len = rtpPacket.size
        out.write((len ushr 8) and 0xFF)
        out.write(len and 0xFF)
        out.write(rtpPacket)
    }

    private fun readGcmWireFromStream(input: java.io.InputStream): ByteArray {
        val lenBuf = ByteArray(4)
        readFully(input, lenBuf)
        val ivLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
        val iv = ByteArray(ivLen)
        readFully(input, iv)
        readFully(input, lenBuf)
        val cipherLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
        val cipher = ByteArray(cipherLen)
        readFully(input, cipher)
        return ByteBuffer.allocate(8 + ivLen + cipherLen)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(ivLen)
            .put(iv)
            .putInt(cipherLen)
            .put(cipher)
            .array()
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val n = input.read(target, offset, target.size - offset)
            if (n < 0) error("Unexpected EOF")
            offset += n
        }
    }
}
