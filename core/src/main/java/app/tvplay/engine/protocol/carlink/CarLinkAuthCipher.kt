package app.tvplay.engine.protocol.carlink

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ICCOA CarLink (`com.ucar.protocol.security`) cryptographic handshake and session cipher:
 *
 * - Curve: `secp256r1` (`NIST P-256`) with 64-byte uncompressed raw public key `(X[32] || Y[32])`.
 * - PIN-salted HMAC: `HmacSHA256` with key `SHA-256(salt || pinAsciiBytes)` over `message`.
 * - Signature: `SHA256withECDSA` over `agreementPk || suffix`.
 * - Shared secret: `ECDH` (`secp256r1`) -> `HKDF-SHA256(ikm = secret, salt = SHA256(clientNonce || serverNonce), info = "session_key", length = 16)`.
 * - Frame/UIBC encryption (`SecurityManager`): `AES/GCM/NoPadding` (128-bit tag, 12-byte IV),
 *   wire format `[ivLen:4B BE][iv][cipherLen:4B BE][ciphertext+tag]`.
 */
object CarLinkAuthCipher {
    const val PROTOCOL_VERSION_1_5 = 1281 // 0x0501 -> major 1, minor 5
    private const val CURVE_NAME = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12
    private const val SESSION_KEY_BYTES = 16
    private val SESSION_KEY_INFO = "session_key".toByteArray(Charsets.UTF_8)
    private val secureRandom = SecureRandom()

    fun generateSixDigitPin(): String {
        val value = secureRandom.nextInt(900_000) + 100_000
        return value.toString()
    }

    fun generateNonce(length: Int = 16): ByteArray =
        ByteArray(length).also { secureRandom.nextBytes(it) }

    fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE_NAME), secureRandom)
        return generator.generateKeyPair()
    }

    /**
     * Encodes an [ECPublicKey] to the 64-byte raw `(X[32] || Y[32])` representation used by `KeyNegotiator`.
     */
    fun encodeRawPublicKey(publicKey: PublicKey): ByteArray {
        val ecKey = publicKey as ECPublicKey
        val xBytes = toFixed32Bytes(ecKey.w.affineX)
        val yBytes = toFixed32Bytes(ecKey.w.affineY)
        return xBytes + yBytes
    }

    /**
     * Decodes a 64-byte raw `(X[32] || Y[32])` (or 65-byte `0x04 || X || Y`) public key on `secp256r1`.
     */
    fun decodeRawPublicKey(rawBytes: ByteArray): PublicKey {
        val clean = when {
            rawBytes.size == 65 && rawBytes[0] == 0x04.toByte() -> rawBytes.copyOfRange(1, 65)
            rawBytes.size == 64 -> rawBytes
            else -> throw IllegalArgumentException("Invalid EC raw public key length=${rawBytes.size}")
        }
        val x = BigInteger(1, clean.copyOfRange(0, 32))
        val y = BigInteger(1, clean.copyOfRange(32, 64))
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec(CURVE_NAME))
        }.getParameterSpec(ECParameterSpec::class.java)
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /**
     * Computes `HmacSHA256` with key = `SHA256(salt || pinBytes)` over `message` (`KeyNegotiator.generateHmac`).
     */
    fun generatePinHmac(
        message: ByteArray,
        salt: ByteArray,
        pinCode: String,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pinCode.toByteArray(Charsets.UTF_8))
        val hmacKey = digest.digest()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun verifyPinHmac(
        expectedMac: ByteArray,
        message: ByteArray,
        salt: ByteArray,
        pinCode: String,
    ): Boolean {
        val computed = generatePinHmac(message, salt, pinCode)
        return MessageDigest.isEqual(expectedMac, computed)
    }

    /**
     * Signs `message || suffix` with `SHA256withECDSA` (`KeyNegotiator.generateSign`).
     */
    fun signEcdsa(
        keyPair: KeyPair,
        message: ByteArray,
        suffix: ByteArray,
    ): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private, secureRandom)
        sig.update(message)
        sig.update(suffix)
        return sig.sign()
    }

    /**
     * Verifies `SHA256withECDSA` signature over `message || suffix` using `peerRawAuthPublicKey`.
     */
    fun verifyEcdsa(
        peerRawAuthPublicKey: ByteArray,
        message: ByteArray,
        suffix: ByteArray,
        signatureBytes: ByteArray,
    ): Boolean = runCatching {
        val pubKey = decodeRawPublicKey(peerRawAuthPublicKey)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(pubKey)
        sig.update(message)
        sig.update(suffix)
        sig.verify(signatureBytes)
    }.getOrDefault(false)

    /**
     * Performs `secp256r1` ECDH and derives the 16-byte AES-GCM session key via HKDF-SHA256.
     */
    fun deriveSessionKey(
        localAgreementKeyPair: KeyPair,
        peerRawAgreementPublicKey: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        val peerPub = decodeRawPublicKey(peerRawAgreementPublicKey)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localAgreementKeyPair.private)
        agreement.doPhase(peerPub, true)
        val sharedSecret = agreement.generateSecret()

        val saltDigest = MessageDigest.getInstance("SHA-256")
        saltDigest.update(clientNonce)
        saltDigest.update(serverNonce)
        val salt = saltDigest.digest()

        return hkdfSha256(
            ikm = sharedSecret,
            salt = salt,
            info = SESSION_KEY_INFO,
            outputLength = SESSION_KEY_BYTES,
        )
    }

    /**
     * RFC 5869 HKDF-SHA256 implementation (`Extract` + `Expand`).
     */
    fun hkdfSha256(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLength: Int = SESSION_KEY_BYTES,
    ): ByteArray {
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val extractMac = Mac.getInstance("HmacSHA256")
        extractMac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = extractMac.doFinal(ikm)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(outputLength)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outputLength) {
            expandMac.reset()
            expandMac.update(t)
            expandMac.update(info)
            expandMac.update(counter.toByte())
            t = expandMac.doFinal()
            val toCopy = minOf(t.size, outputLength - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    /**
     * Encrypts [plaintext] using `AES/GCM/NoPadding` into ICCOA `SecurityManager` wire format:
     * `[ivLen:4B BE][iv][cipherLen:4B BE][ciphertext+tag]`.
     */
    fun encryptGcmWire(
        sessionKey: ByteArray,
        plaintext: ByteArray,
        iv: ByteArray = generateNonce(GCM_IV_BYTES),
    ): ByteArray {
        require(sessionKey.size == 16 || sessionKey.size == 32) {
            "Invalid AES-GCM session key size=${sessionKey.size}"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        val ciphertext = cipher.doFinal(plaintext)
        val out = ByteArray(8 + iv.size + ciphertext.size)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(iv.size)
        buf.put(iv)
        buf.putInt(ciphertext.size)
        buf.put(ciphertext)
        return out
    }

    /**
     * Checks whether [wireBytes] matches the structural layout of an ICCOA `SecurityManager`
     * AES-GCM frame: `[ivLen:4B BE (12..16)][iv][cipherLen:4B BE (>=16)][ciphertext+tag]`.
     */
    fun looksLikeGcmWire(wireBytes: ByteArray): Boolean {
        if (wireBytes.size < 28) return false
        val buf = ByteBuffer.wrap(wireBytes).order(ByteOrder.BIG_ENDIAN)
        val ivLen = buf.int
        if (ivLen !in 12..16 || wireBytes.size < 8 + ivLen + 16) return false
        val cipherLen = buf.getInt(4 + ivLen)
        return cipherLen >= 16 && (8 + ivLen + cipherLen) == wireBytes.size
    }

    /**
     * Decrypts an ICCOA `SecurityManager` wire payload:
     * `[ivLen:4B BE][iv][cipherLen:4B BE][ciphertext+tag]`.
     */
    fun decryptGcmWire(
        sessionKey: ByteArray,
        wireBytes: ByteArray,
    ): ByteArray {
        require(wireBytes.size >= 8) { "GCM wire payload too short: ${wireBytes.size}" }
        val buf = ByteBuffer.wrap(wireBytes).order(ByteOrder.BIG_ENDIAN)
        val ivLen = buf.int
        require(ivLen in 1..64 && buf.remaining() >= ivLen + 4) { "Invalid GCM IV length=$ivLen" }
        val iv = ByteArray(ivLen)
        buf.get(iv)
        val cipherLen = buf.int
        require(cipherLen in 16..buf.remaining()) { "Invalid GCM ciphertext length=$cipherLen" }
        val ciphertext = ByteArray(cipherLen)
        buf.get(ciphertext)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(sessionKey, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    data class PeerAuthRecord(
        val clientAuthPk: ByteArray,
        val serverAuthKeyPair: KeyPair,
    )

    private val peerAuthRecords = ConcurrentHashMap<String, PeerAuthRecord>()

    fun clearPeerAuthRecords() {
        peerAuthRecords.clear()
    }

    /**
     * High-level server handshake state machine (`ServerKeyNegotiator`).
     *
     * Implements the 2-phase ICCOA CarLink handshake:
     * 1. **Quick Connection** (`request.authPk.isEmpty()`):
     *    - If `clientId` (`request.id`) has no cached [PeerAuthRecord], returns `result = 1`
     *      (`"quick connection rejected, need normal connection"`), which triggers the phone
     *      (Xiaomi CarWith / vivo Jovi InCar / OPPO Car+) to display the 6-digit PIN input dialog.
     *    - If `clientId` was previously paired, verifies ECDSA signature, derives `sessionKey`,
     *      and returns `result = 0`.
     * 2. **Normal Connection** (`request.authPk.size >= 64`):
     *    - Verifies the phone's PIN HMAC (`result = 2` on PIN mismatch), derives `sessionKey` via
     *      ECDH + HKDF-SHA256, caches [PeerAuthRecord] for future quick connections, and returns `result = 0`.
     */
    class ServerHandshakeSession(
        val pinCode: String,
        val authKeyPair: KeyPair = generateEcKeyPair(),
        val agreementKeyPair: KeyPair = generateEcKeyPair(),
        val serverNonce: ByteArray = generateNonce(16),
    ) {
        var sessionKey: ByteArray? = null
            private set

        fun processAuthRequest(
            request: CarLinkProtoCodec.AuthRequestMsg,
            dayOrNightMode: Int = 2,
            screenInfo: String = "",
        ): CarLinkProtoCodec.AuthResponseMsg {
            val negotiatedWorkModes = (request.workModes and 0x0F).takeIf { it != 0 } ?: 1
            val clientId = request.id.toString(Charsets.UTF_8).trim()

            // Phase 1: Quick connection attempt (phone sends empty authPk before prompting user for PIN)
            if (request.authPk.isEmpty()) {
                val peerRecord = if (clientId.isNotEmpty()) peerAuthRecords[clientId] else null
                if (peerRecord == null || request.agreementPk.size < 64 || request.random.isEmpty()) {
                    return CarLinkProtoCodec.AuthResponseMsg(
                        version = PROTOCOL_VERSION_1_5,
                        authPk = ByteArray(0),
                        authPkHmac = ByteArray(0),
                        agreementPk = ByteArray(0),
                        agreementPkSig = ByteArray(0),
                        random = ByteArray(0),
                        result = 1,
                        workModes = negotiatedWorkModes,
                        dayOrNightMode = dayOrNightMode,
                        screenInfo = screenInfo,
                    )
                }
                if (
                    request.agreementPkSig.isNotEmpty() &&
                    !verifyEcdsa(
                        peerRawAuthPublicKey = peerRecord.clientAuthPk,
                        message = request.agreementPk,
                        suffix = request.random,
                        signatureBytes = request.agreementPkSig,
                    )
                ) {
                    peerAuthRecords.remove(clientId)
                    return CarLinkProtoCodec.AuthResponseMsg(
                        version = PROTOCOL_VERSION_1_5,
                        result = 1,
                        workModes = negotiatedWorkModes,
                        dayOrNightMode = dayOrNightMode,
                        screenInfo = screenInfo,
                    )
                }
                val serverAgreementPk = encodeRawPublicKey(agreementKeyPair.public)
                runCatching {
                    sessionKey = deriveSessionKey(
                        localAgreementKeyPair = agreementKeyPair,
                        peerRawAgreementPublicKey = request.agreementPk,
                        clientNonce = request.random,
                        serverNonce = serverNonce,
                    )
                }
                val sigSuffix = request.agreementPk + serverNonce + request.random
                val agreementPkSig = signEcdsa(
                    keyPair = peerRecord.serverAuthKeyPair,
                    message = serverAgreementPk,
                    suffix = sigSuffix,
                )
                return CarLinkProtoCodec.AuthResponseMsg(
                    version = PROTOCOL_VERSION_1_5,
                    authPk = ByteArray(0),
                    authPkHmac = ByteArray(0),
                    agreementPk = serverAgreementPk,
                    agreementPkSig = agreementPkSig,
                    random = serverNonce,
                    result = 0,
                    workModes = negotiatedWorkModes,
                    dayOrNightMode = dayOrNightMode,
                    screenInfo = screenInfo,
                )
            }

            // Phase 2: Normal connection with 6-digit PIN
            // In ucar-sdk (ClientKeyNegotiator / ServerKeyNegotiator), the phone computes:
            //   authPkHmac = generateHmac(Bytes.concat(clientAuthPk, clientNonce), new byte[0])
            // Support both the ucar-sdk formula (message = authPk + random, salt = empty)
            // and the salted variant (message = authPk, salt = random).
            if (request.authPkHmac.isNotEmpty()) {
                val ucarPinValid = verifyPinHmac(
                    expectedMac = request.authPkHmac,
                    message = request.authPk + request.random,
                    salt = ByteArray(0),
                    pinCode = pinCode,
                )
                val legacyPinValid = verifyPinHmac(
                    expectedMac = request.authPkHmac,
                    message = request.authPk,
                    salt = request.random,
                    pinCode = pinCode,
                )
                if (!ucarPinValid && !legacyPinValid) {
                    return CarLinkProtoCodec.AuthResponseMsg(
                        version = PROTOCOL_VERSION_1_5,
                        result = 2,
                        workModes = negotiatedWorkModes,
                        dayOrNightMode = dayOrNightMode,
                        screenInfo = screenInfo,
                    )
                }
            }

            if (
                request.authPk.size == 64 &&
                request.agreementPk.size == 64 &&
                request.agreementPkSig.isNotEmpty() &&
                request.random.isNotEmpty() &&
                !verifyEcdsa(
                    peerRawAuthPublicKey = request.authPk,
                    message = request.agreementPk,
                    suffix = request.random,
                    signatureBytes = request.agreementPkSig,
                )
            ) {
                return CarLinkProtoCodec.AuthResponseMsg(
                    version = PROTOCOL_VERSION_1_5,
                    result = 1,
                    workModes = negotiatedWorkModes,
                    dayOrNightMode = dayOrNightMode,
                    screenInfo = screenInfo,
                )
            }

            val serverAuthPk = encodeRawPublicKey(authKeyPair.public)
            val serverAgreementPk = encodeRawPublicKey(agreementKeyPair.public)

            if (request.authPk.size >= 64 && request.agreementPk.size >= 64 && request.random.isNotEmpty()) {
                runCatching {
                    sessionKey = deriveSessionKey(
                        localAgreementKeyPair = agreementKeyPair,
                        peerRawAgreementPublicKey = request.agreementPk,
                        clientNonce = request.random,
                        serverNonce = serverNonce,
                    )
                }
                if (clientId.isNotEmpty()) {
                    peerAuthRecords[clientId] = PeerAuthRecord(
                        clientAuthPk = request.authPk.copyOf(),
                        serverAuthKeyPair = authKeyPair,
                    )
                }
            }

            val combinedAuthPk = serverAuthPk + request.authPk
            val combinedNonce = serverNonce + request.random
            val authPkHmac = generatePinHmac(
                message = combinedAuthPk,
                salt = combinedNonce,
                pinCode = pinCode,
            )
            val sigSuffix = request.agreementPk + serverNonce + request.random
            val agreementPkSig = signEcdsa(
                keyPair = authKeyPair,
                message = serverAgreementPk,
                suffix = sigSuffix,
            )

            return CarLinkProtoCodec.AuthResponseMsg(
                version = PROTOCOL_VERSION_1_5,
                authPk = serverAuthPk,
                authPkHmac = authPkHmac,
                agreementPk = serverAgreementPk,
                agreementPkSig = agreementPkSig,
                random = serverNonce,
                result = 0,
                workModes = negotiatedWorkModes,
                dayOrNightMode = dayOrNightMode,
                screenInfo = screenInfo,
            )
        }

        /**
         * Decrypts `AuthConfirm.cipher` (`"<phoneIp>:<wfdPort>"`) using [sessionKey],
         * falling back to [fallbackAddress] and port `7236` (`ChannelType.RTSP`).
         */
        fun parseAuthConfirmEndpoint(
            confirm: CarLinkProtoCodec.AuthConfirmMsg,
            fallbackAddress: String,
        ): Pair<String, Int> {
            val key = sessionKey
            if (key != null && confirm.cipher.isNotEmpty()) {
                val plain = runCatching {
                    String(decryptGcmWire(key, confirm.cipher), Charsets.UTF_8).trim()
                }.getOrNull()
                if (!plain.isNullOrEmpty()) {
                    val parts = plain.split(':')
                    val ip = parts[0].trim().ifEmpty { fallbackAddress }
                    val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: CarLinkChannelType.RTSP.port
                    return ip to port
                }
            }
            return fallbackAddress to CarLinkChannelType.RTSP.port
        }
    }

    private fun toFixed32Bytes(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> {
                val padded = ByteArray(32)
                System.arraycopy(raw, 0, padded, 32 - raw.size, raw.size)
                padded
            }
        }
    }
}
