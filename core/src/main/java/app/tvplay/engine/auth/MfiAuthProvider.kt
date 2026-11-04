package app.tvplay.engine.auth

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Full-duplex blocking byte stream used across USB, Bluetooth RFCOMM, TLS Lockdown, and iAP2 links.
 *
 * [recv] returns `null` when its timeout expires and an empty array on clean EOF.
 */
interface DuplexByteStream : Closeable {
    fun send(data: ByteArray)

    fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray?

    override fun close()
}

/** Portable Base64 codec safe on Android API 24+ and JVM unit tests. */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(triple ushr 18) and 0x3f])
            out.append(ALPHABET[(triple ushr 12) and 0x3f])
            out.append(if (i + 1 < bytes.size) ALPHABET[(triple ushr 6) and 0x3f] else '=')
            out.append(if (i + 2 < bytes.size) ALPHABET[triple and 0x3f] else '=')
            i += 3
        }
        return out.toString()
    }

    fun decode(input: String): ByteArray {
        val clean = input.filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return ByteArray(0)
        require(clean.length % 4 == 0) { "Invalid Base64 length: ${clean.length}" }
        val lookup = IntArray(128) { -1 }
        for (idx in ALPHABET.indices) {
            lookup[ALPHABET[idx].code] = idx
        }
        val pad = when {
            clean.endsWith("==") -> 2
            clean.endsWith("=") -> 1
            else -> 0
        }
        val out = ByteArray((clean.length / 4) * 3 - pad)
        var outIndex = 0
        var i = 0
        while (i < clean.length) {
            val c0 = decodeChar(clean[i], lookup)
            val c1 = decodeChar(clean[i + 1], lookup)
            val c2 = if (clean[i + 2] == '=') 0 else decodeChar(clean[i + 2], lookup)
            val c3 = if (clean[i + 3] == '=') 0 else decodeChar(clean[i + 3], lookup)
            val triple = (c0 shl 18) or (c1 shl 12) or (c2 shl 6) or c3
            if (outIndex < out.size) out[outIndex++] = ((triple ushr 16) and 0xff).toByte()
            if (outIndex < out.size) out[outIndex++] = ((triple ushr 8) and 0xff).toByte()
            if (outIndex < out.size) out[outIndex++] = (triple and 0xff).toByte()
            i += 4
        }
        return out
    }

    private fun decodeChar(ch: Char, lookup: IntArray): Int {
        val code = ch.code
        require(code in 0..127 && lookup[code] >= 0) { "Invalid Base64 character: '$ch'" }
        return lookup[code]
    }
}

enum class MfiCertificateType {
    MFI,
    BAA,
}

open class MfiAuthException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Contract for accessory certificate retrieval and digest challenge signing. */
interface MfiAuthProvider {
    val certificateType: MfiCertificateType
        get() = MfiCertificateType.MFI

    fun protocolMajor(): Int

    fun readCertificate(maximumOutputLength: Int = DEFAULT_MAX_CERTIFICATE_BYTES): ByteArray

    fun signChallenge(challenge: ByteArray): ByteArray

    companion object {
        const val DEFAULT_MAX_CERTIFICATE_BYTES = 1800
    }
}

/**
 * Pure Kotlin/Java P-256 ECDSA MFi v3 provider backed by `offline-mfi/{identity.pk8,certificate.p7b}`.
 */
class OfflineMfiAuthProvider private constructor(
    private val privateKey: PrivateKey,
    private val certificate: ByteArray,
    private val onSignature: (Int) -> Unit,
) : MfiAuthProvider {
    override fun protocolMajor(): Int = 3

    override fun readCertificate(maximumOutputLength: Int): ByteArray {
        require(maximumOutputLength > 0 && certificate.size <= maximumOutputLength) {
            "Offline MFi certificate (${certificate.size} bytes) exceeds limit ($maximumOutputLength)"
        }
        return certificate.copyOf()
    }

    override fun signChallenge(challenge: ByteArray): ByteArray {
        require(challenge.size == 32) { "MFi v3 expects a 32-byte digest challenge" }
        val signer = Signature.getInstance("NONEwithECDSA")
        signer.initSign(privateKey)
        signer.update(challenge)
        val rawSignature = derToRaw(signer.sign())
        onSignature(challenge.size)
        return rawSignature
    }

    companion object {
        const val DIRECTORY = "offline-mfi"
        private const val MAX_FILE_BYTES = 16 * 1024

        fun load(directory: File, onSignature: (Int) -> Unit = {}): OfflineMfiAuthProvider {
            require(directory.isDirectory) { "Offline MFi directory is missing or invalid: $directory" }
            val encodedKey = readBounded(File(directory, "identity.pk8"))
            val privateKey = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encodedKey))
            } finally {
                encodedKey.fill(0)
            }
            val certificate = readBounded(File(directory, "certificate.p7b"))
            val certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(certificate.inputStream())
            require(certificates.size == 1) { "Expected exactly one accessory certificate" }
            val publicKey = certificates.single().publicKey as? ECPublicKey
                ?: error("Expected an EC accessory certificate")
            require(
                publicKey.params.order.toString(16) ==
                    "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            ) {
                "Expected a NIST P-256 accessory certificate"
            }
            val probe = ByteArray(32).also(SecureRandom()::nextBytes)
            val signer = Signature.getInstance("NONEwithECDSA")
            signer.initSign(privateKey)
            signer.update(probe)
            val signature = signer.sign()
            val verifier = Signature.getInstance("NONEwithECDSA")
            verifier.initVerify(publicKey)
            verifier.update(probe)
            require(verifier.verify(signature)) { "Offline MFi private key does not match certificate" }
            return OfflineMfiAuthProvider(privateKey, certificate, onSignature)
        }

        private fun readBounded(file: File): ByteArray = file.inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_FILE_BYTES) { "Identity file exceeds $MAX_FILE_BYTES bytes" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().also { require(it.isNotEmpty()) { "Identity file is empty: ${file.name}" } }
        }

        internal fun derToRaw(der: ByteArray): ByteArray {
            val sequence = ASN1Sequence.getInstance(der)
            require(sequence.size() == 2) { "Invalid ECDSA DER signature sequence" }
            val result = ByteArray(64)
            for (index in 0..1) {
                val value = ASN1Integer.getInstance(sequence.getObjectAt(index)).value
                require(value.signum() > 0 && value.bitLength() <= 256) { "Invalid ECDSA integer component" }
                val bytes = value.toByteArray()
                val skip = if (bytes.size == 33 && bytes[0] == 0.toByte()) 1 else 0
                val length = bytes.size - skip
                bytes.copyInto(result, index * 32 + 32 - length, skip)
            }
            return result
        }
    }
}
