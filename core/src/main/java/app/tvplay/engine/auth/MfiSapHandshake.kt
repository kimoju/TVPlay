package app.tvplay.engine.auth

import java.security.MessageDigest

class BaaCertificatePair(leaf: ByteArray, intermediate: ByteArray) {
    val leaf: ByteArray = leaf.copyOf()
    val intermediate: ByteArray = intermediate.copyOf()

    init {
        require(this.leaf.isNotEmpty()) { "BAA leaf certificate must not be empty" }
        require(this.intermediate.isNotEmpty()) { "BAA intermediate certificate must not be empty" }
    }
}

interface BaaMfiAuthProvider : MfiAuthProvider {
    override val certificateType: MfiCertificateType
        get() = MfiCertificateType.BAA

    fun baaCertificates(): BaaCertificatePair
}

/**
 * CarPlay `/auth-setup` MFiSAP responder (accessory side).
 *
 * Proves MFi licensing to the iPhone. The request is `[1-byte version=1][32-byte controller X25519
 * public key]`; the response is this accessory's ephemeral public key, its MFi certificate, and its
 * coprocessor signature over the two public keys, AES-128-CTR encrypted under a key derived from
 * the shared secret. It runs over the already-encrypted control channel.
 *
 * [MfiAuthProvider] performs blocking I/O and must be called off the main thread.
 */
class MfiSapHandshake(private val authProvider: MfiAuthProvider) {
    fun handleAuthSetup(requestBody: ByteArray): ByteArray {
        require(requestBody.size == 1 + PUBLIC_KEY_BYTES && requestBody[0].toInt() == VERSION) {
            "Malformed /auth-setup request (${requestBody.size} bytes)"
        }
        val peerPublicKey = requestBody.copyOfRange(1, requestBody.size)

        val pair = AirPlayCrypto.x25519Generate()
        val shared = AirPlayCrypto.x25519SharedSecret(pair.privateKey, peerPublicKey)
        require(!shared.all { it.toInt() == 0 }) {
            "Invalid X25519 peer public key in /auth-setup"
        }

        val aesKey = sha1("AES-KEY".asciiBytes(), shared).copyOf(16)
        val aesIv = sha1("AES-IV".asciiBytes(), shared).copyOf(16)

        return when (authProvider.certificateType) {
            MfiCertificateType.MFI -> {
                val certificate = authProvider.readCertificate()
                val digest = if (authProvider.protocolMajor() == 2) {
                    sha1(pair.publicKey, peerPublicKey)
                } else {
                    sha256(pair.publicKey, peerPublicKey)
                }
                val encryptedSignature = AirPlayCrypto.aesCtr128Transform(
                    aesKey,
                    aesIv,
                    authProvider.signChallenge(digest),
                )
                buildMfiResponse(pair.publicKey, certificate, encryptedSignature)
            }

            MfiCertificateType.BAA -> {
                val encryptedSignature = AirPlayCrypto.aesCtr128Transform(
                    aesKey,
                    aesIv,
                    authProvider.signChallenge(pair.publicKey + peerPublicKey),
                )
                if (authProvider is BaaMfiAuthProvider) {
                    val certificates = authProvider.baaCertificates()
                    buildBaaResponse(
                        pair.publicKey,
                        certificates.leaf,
                        encryptedSignature,
                        encodeBaaIntermediate(certificates.intermediate),
                    )
                } else {
                    val certificate = authProvider.readCertificate()
                    buildMfiResponse(pair.publicKey, certificate, encryptedSignature)
                }
            }
        }
    }

    fun respond(requestBody: ByteArray): ByteArray = handleAuthSetup(requestBody)

    fun handleOrNull(requestBody: ByteArray): ByteArray? = try {
        handleAuthSetup(requestBody)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        private const val VERSION = 0x01
        private const val PUBLIC_KEY_BYTES = 32

        /** Returns the raw binary response, or null when the request is malformed. */
        fun handle(body: ByteArray, authProvider: MfiAuthProvider): ByteArray? =
            MfiSapHandshake(authProvider).handleOrNull(body)

        private fun buildMfiResponse(
            publicKey: ByteArray,
            certificate: ByteArray,
            encryptedSignature: ByteArray,
        ): ByteArray {
            val response = ByteArray(32 + 4 + certificate.size + 4 + encryptedSignature.size)
            publicKey.copyInto(response, 0)
            putU32(response, 32, certificate.size)
            certificate.copyInto(response, 36)
            putU32(response, 36 + certificate.size, encryptedSignature.size)
            encryptedSignature.copyInto(response, 40 + certificate.size)
            return response
        }

        private fun buildBaaResponse(
            publicKey: ByteArray,
            leaf: ByteArray,
            encryptedSignature: ByteArray,
            baicBlob: ByteArray,
        ): ByteArray {
            val response = ByteArray(
                32 + 4 + leaf.size + 4 + encryptedSignature.size + 4 + baicBlob.size,
            )
            publicKey.copyInto(response, 0)
            var offset = 32
            putU32(response, offset, leaf.size)
            offset += 4
            leaf.copyInto(response, offset)
            offset += leaf.size
            putU32(response, offset, encryptedSignature.size)
            offset += 4
            encryptedSignature.copyInto(response, offset)
            offset += encryptedSignature.size
            putU32(response, offset, baicBlob.size)
            offset += 4
            baicBlob.copyInto(response, offset)
            return response
        }

        /** OPACK `{"baIC": intermediate}` used by Showcase and CarPlay Simulator. */
        private fun encodeBaaIntermediate(intermediate: ByteArray): ByteArray {
            val header = when {
                intermediate.size <= 0x20 -> byteArrayOf(
                    0xE1.toByte(),
                    0x44,
                    'b'.code.toByte(),
                    'a'.code.toByte(),
                    'I'.code.toByte(),
                    'C'.code.toByte(),
                    (0x70 + intermediate.size).toByte(),
                )

                intermediate.size <= 0xFF -> byteArrayOf(
                    0xE1.toByte(),
                    0x44,
                    'b'.code.toByte(),
                    'a'.code.toByte(),
                    'I'.code.toByte(),
                    'C'.code.toByte(),
                    0x91.toByte(),
                    intermediate.size.toByte(),
                )

                intermediate.size <= 0xFFFF -> byteArrayOf(
                    0xE1.toByte(),
                    0x44,
                    'b'.code.toByte(),
                    'a'.code.toByte(),
                    'I'.code.toByte(),
                    'C'.code.toByte(),
                    0x92.toByte(),
                    intermediate.size.toByte(),
                    (intermediate.size ushr 8).toByte(),
                )

                else -> byteArrayOf(
                    0xE1.toByte(),
                    0x44,
                    'b'.code.toByte(),
                    'a'.code.toByte(),
                    'I'.code.toByte(),
                    'C'.code.toByte(),
                    0x93.toByte(),
                    intermediate.size.toByte(),
                    (intermediate.size ushr 8).toByte(),
                    (intermediate.size ushr 16).toByte(),
                    (intermediate.size ushr 24).toByte(),
                )
            }
            return header + intermediate
        }

        private fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", parts)

        private fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)

        private fun digest(algorithm: String, parts: Array<out ByteArray>): ByteArray {
            val digest = MessageDigest.getInstance(algorithm)
            for (part in parts) digest.update(part)
            return digest.digest()
        }

        private fun putU32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }
    }
}
