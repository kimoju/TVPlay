package app.tvplay.engine.protocol.carlink

import android.view.KeyEvent
import app.tvplay.engine.protocol.aap.AndroidAutoProtocol
import app.tvplay.engine.protocol.hicar.HiCarProtocol
import app.tvplay.engine.session.TvProjectionProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
        assertEquals((-65).toByte(), normal[15])

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
}
