package app.tvplay.engine.auth

import android.annotation.SuppressLint
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509TrustManager

/** USB/Lockdown transport failures distinct from MFi I2C failures. */
sealed class LockdownException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : LockdownException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : LockdownException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : LockdownException(message, cause)
    class Protocol(message: String, cause: Throwable? = null) : LockdownException(message, cause)
}

typealias IphoneUsbException = LockdownException

/** Opens a blocking TCP stream over USBMUX to [destinationPort] (e.g. 62078 for Lockdown). */
fun interface LockdownTcpConnector {
    fun connect(destinationPort: Int, timeoutMillis: Long): DuplexByteStream

    companion object {
        const val LOCKDOWN_PORT = 62078
    }
}

/**
 * Pairing material assembled locally from Lockdown values already obtained by the caller.
 *
 * It deliberately has no transport dependency: it neither requests values nor sends Pair.
 */
class LockdownPairRecord private constructor(
    val hostId: String,
    val systemBuid: String,
    val wifiMacAddress: String,
    devicePublicKeyPem: ByteArray,
    deviceCertificatePem: ByteArray,
    hostPrivateKeyPem: ByteArray,
    hostCertificatePem: ByteArray,
    rootPrivateKeyPem: ByteArray,
    rootCertificatePem: ByteArray,
) {
    private val storedDevicePublicKeyPem = devicePublicKeyPem.copyOf()
    private val storedDeviceCertificatePem = deviceCertificatePem.copyOf()
    private val storedHostPrivateKeyPem = hostPrivateKeyPem.copyOf()
    private val storedHostCertificatePem = hostCertificatePem.copyOf()
    private val storedRootPrivateKeyPem = rootPrivateKeyPem.copyOf()
    private val storedRootCertificatePem = rootCertificatePem.copyOf()

    val devicePublicKeyPem: ByteArray get() = storedDevicePublicKeyPem.copyOf()
    val deviceCertificatePem: ByteArray get() = storedDeviceCertificatePem.copyOf()
    val hostPrivateKeyPem: ByteArray get() = storedHostPrivateKeyPem.copyOf()
    val hostCertificatePem: ByteArray get() = storedHostCertificatePem.copyOf()
    val rootPrivateKeyPem: ByteArray get() = storedRootPrivateKeyPem.copyOf()
    val rootCertificatePem: ByteArray get() = storedRootCertificatePem.copyOf()

    /** Builds only the nested Pair request value; EscrowBag is intentionally unavailable yet. */
    fun toPairRequestDictionary(): LockdownPlistValue.Dictionary = LockdownPlistValue.Dictionary(
        linkedMapOf(
            "DeviceCertificate" to LockdownPlistValue.Data(deviceCertificatePem),
            "HostCertificate" to LockdownPlistValue.Data(hostCertificatePem),
            "HostID" to LockdownPlistValue.Text(hostId),
            "RootCertificate" to LockdownPlistValue.Data(rootCertificatePem),
            "SystemBUID" to LockdownPlistValue.Text(systemBuid),
        ),
    )

    override fun toString(): String = "LockdownPairRecord(redacted)"

    companion object {
        /** Rebuilds a record from previously persisted material; all PEM buffers are copied. */
        fun restore(
            hostId: String,
            systemBuid: String,
            wifiMacAddress: String,
            devicePublicKeyPem: ByteArray,
            deviceCertificatePem: ByteArray,
            hostPrivateKeyPem: ByteArray,
            hostCertificatePem: ByteArray,
            rootPrivateKeyPem: ByteArray,
            rootCertificatePem: ByteArray,
        ): LockdownPairRecord {
            require(hostId.isNotBlank()) { "hostId must not be blank" }
            require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }
            require(wifiMacAddress.isNotBlank()) { "wifiMacAddress must not be blank" }
            listOf(
                devicePublicKeyPem,
                deviceCertificatePem,
                hostPrivateKeyPem,
                hostCertificatePem,
                rootPrivateKeyPem,
                rootCertificatePem,
            ).forEach { require(it.isNotEmpty()) { "PEM material must not be empty" } }
            return LockdownPairRecord(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMacAddress,
                devicePublicKeyPem = devicePublicKeyPem,
                deviceCertificatePem = deviceCertificatePem,
                hostPrivateKeyPem = hostPrivateKeyPem,
                hostCertificatePem = hostCertificatePem,
                rootPrivateKeyPem = rootPrivateKeyPem,
                rootCertificatePem = rootCertificatePem,
            )
        }

        internal fun create(
            hostId: String,
            systemBuid: String,
            wifiMacAddress: String,
            devicePublicKeyPem: ByteArray,
            material: CertificateMaterial,
        ) = LockdownPairRecord(
            hostId = hostId,
            systemBuid = systemBuid,
            wifiMacAddress = wifiMacAddress,
            devicePublicKeyPem = devicePublicKeyPem,
            deviceCertificatePem = material.deviceCertificatePem,
            hostPrivateKeyPem = material.hostPrivateKeyPem,
            hostCertificatePem = material.hostCertificatePem,
            rootPrivateKeyPem = material.rootPrivateKeyPem,
            rootCertificatePem = material.rootCertificatePem,
        )
    }
}

/** Generates a Lockdown PairRecord from values supplied by the caller, without any USB I/O. */
object LockdownPairRecordGenerator {
    @Throws(GeneralSecurityException::class, IllegalArgumentException::class)
    fun generate(
        devicePublicKeyPkcs1Pem: ByteArray,
        wifiAddress: String,
        hostId: String,
        systemBuid: String,
    ): LockdownPairRecord {
        require(devicePublicKeyPkcs1Pem.isNotEmpty()) { "devicePublicKeyPkcs1Pem must not be empty" }
        require(wifiAddress.isNotBlank()) { "wifiAddress must not be blank" }
        require(hostId.isNotBlank()) { "hostId must not be blank" }
        require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }

        val copiedDeviceKey = devicePublicKeyPkcs1Pem.copyOf()
        val material = CertificateMaterialGenerator.generate(copiedDeviceKey)
        return LockdownPairRecord.create(
            hostId = hostId,
            systemBuid = systemBuid,
            wifiMacAddress = wifiAddress,
            devicePublicKeyPem = copiedDeviceKey,
            material = material,
        )
    }
}

internal class CertificateMaterial(
    deviceCertificatePem: ByteArray,
    hostPrivateKeyPem: ByteArray,
    hostCertificatePem: ByteArray,
    rootPrivateKeyPem: ByteArray,
    rootCertificatePem: ByteArray,
) {
    private val storedDeviceCertificatePem = deviceCertificatePem.copyOf()
    private val storedHostPrivateKeyPem = hostPrivateKeyPem.copyOf()
    private val storedHostCertificatePem = hostCertificatePem.copyOf()
    private val storedRootPrivateKeyPem = rootPrivateKeyPem.copyOf()
    private val storedRootCertificatePem = rootCertificatePem.copyOf()

    val deviceCertificatePem: ByteArray get() = storedDeviceCertificatePem.copyOf()
    val hostPrivateKeyPem: ByteArray get() = storedHostPrivateKeyPem.copyOf()
    val hostCertificatePem: ByteArray get() = storedHostCertificatePem.copyOf()
    val rootPrivateKeyPem: ByteArray get() = storedRootPrivateKeyPem.copyOf()
    val rootCertificatePem: ByteArray get() = storedRootCertificatePem.copyOf()
}

/** Minimal Android/JCA implementation of the certificate profile used by the locked dependency. */
private object CertificateMaterialGenerator {
    private const val RSA_KEY_BITS = 2048
    private const val CERTIFICATE_LIFETIME_SECONDS = 10L * 365 * 24 * 60 * 60
    private const val MILLIS_PER_SECOND = 1_000L
    private const val UTC_TIME_LAST_YEAR = 2049
    private val sha256WithRsa = algorithmIdentifier("1.2.840.113549.1.1.11")
    private val rsaEncryption = algorithmIdentifier("1.2.840.113549.1.1.1")

    fun generate(devicePublicKeyPem: ByteArray): CertificateMaterial {
        val devicePublicKey = parsePkcs1RsaPublicKey(devicePublicKeyPem)
        val rootKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()
        val hostKeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()
        val rootPublicKey = rootKeyPair.public as? RSAPublicKey
            ?: throw GeneralSecurityException("Generated root key is not RSA")
        val hostPublicKey = hostKeyPair.public as? RSAPublicKey
            ?: throw GeneralSecurityException("Generated host key is not RSA")
        val now = System.currentTimeMillis()
        val emptyName = distinguishedName(null)
        val rootDer = certificate(
            issuer = emptyName,
            subject = emptyName,
            certificatePublicKey = rootPublicKey,
            signingKey = rootKeyPair.private,
            signingPublicKey = rootPublicKey,
            nowMillis = now,
            extensions = rootExtensions(),
        )
        val hostDer = certificate(
            issuer = emptyName,
            subject = emptyName,
            certificatePublicKey = hostPublicKey,
            signingKey = rootKeyPair.private,
            signingPublicKey = rootPublicKey,
            nowMillis = now,
            extensions = leafExtensions(hostPublicKey, includeSubjectKeyIdentifier = false),
        )
        val deviceDer = certificate(
            issuer = emptyName,
            subject = emptyName,
            certificatePublicKey = devicePublicKey,
            signingKey = rootKeyPair.private,
            signingPublicKey = rootPublicKey,
            nowMillis = now,
            extensions = leafExtensions(devicePublicKey, includeSubjectKeyIdentifier = true),
        )
        return CertificateMaterial(
            deviceCertificatePem = pem("CERTIFICATE", deviceDer),
            hostPrivateKeyPem = pem("PRIVATE KEY", hostKeyPair.private.encoded),
            hostCertificatePem = pem("CERTIFICATE", hostDer),
            rootPrivateKeyPem = pem("PRIVATE KEY", rootKeyPair.private.encoded),
            rootCertificatePem = pem("CERTIFICATE", rootDer),
        )
    }

    private fun certificate(
        issuer: ByteArray,
        subject: ByteArray,
        certificatePublicKey: RSAPublicKey,
        signingKey: PrivateKey,
        signingPublicKey: PublicKey,
        nowMillis: Long,
        extensions: ByteArray,
    ): ByteArray {
        val publicKeyBits = pkcs1PublicKey(certificatePublicKey)
        val notAfterMillis = nowMillis + CERTIFICATE_LIFETIME_SECONDS * MILLIS_PER_SECOND
        require(nowMillis in 0 until notAfterMillis) { "Invalid certificate validity" }
        val tbs = sequence(
            explicit(0, integer(BigInteger.valueOf(2))),
            integer(BigInteger.ZERO),
            sha256WithRsa,
            issuer,
            sequence(
                certificateTime(nowMillis),
                certificateTime(notAfterMillis),
            ),
            subject,
            sequence(rsaEncryption, bitString(publicKeyBits)),
            explicit(3, extensions),
        )
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(signingKey)
            update(tbs)
        }
        val signature = signer.sign()
        val encoded = sequence(tbs, sha256WithRsa, bitString(signature))
        verifyGeneratedCertificate(tbs, signature, signingPublicKey)
        return encoded
    }

    /** JBR's CertificateFactory rejects the locked empty issuer DN, so verify the signed TBS directly. */
    private fun verifyGeneratedCertificate(tbs: ByteArray, signature: ByteArray, signingPublicKey: PublicKey) {
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(signingPublicKey)
            update(tbs)
        }
        if (!verifier.verify(signature)) throw GeneralSecurityException("Generated certificate signature does not verify")
    }

    private fun rootExtensions(): ByteArray = sequence(
        extension("2.5.29.19", critical = true, value = sequence(boolean(true))),
    )

    private fun leafExtensions(
        publicKey: RSAPublicKey,
        includeSubjectKeyIdentifier: Boolean,
    ): ByteArray {
        val extensions = mutableListOf(
            extension("2.5.29.19", critical = true, value = sequence()),
        )
        if (includeSubjectKeyIdentifier) {
            extensions += extension(
                "2.5.29.14",
                critical = false,
                value = octetString(MessageDigest.getInstance("SHA-1").digest(pkcs1PublicKey(publicKey))),
            )
        }
        extensions += extension(
            "2.5.29.15",
            critical = true,
            value = bitString(byteArrayOf(0xa0.toByte()), unusedBits = 5),
        )
        return sequence(*extensions.toTypedArray())
    }

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) sequence(objectIdentifier(oid), boolean(true), octetString(value))
        else sequence(objectIdentifier(oid), octetString(value))

    private fun parsePkcs1RsaPublicKey(pem: ByteArray): RSAPublicKey {
        val text = pem.toString(StandardCharsets.US_ASCII).trim()
        val begin = "-----BEGIN RSA PUBLIC KEY-----"
        val end = "-----END RSA PUBLIC KEY-----"
        require(text.startsWith(begin) && text.endsWith(end)) { "Expected a PKCS#1 RSA public key" }
        val encoded = text.substring(begin.length, text.length - end.length).filterNot(Char::isWhitespace)
        val der = Base64Codec.decode(encoded)
        val outer = DerReader(der)
        val sequence = outer.readConstructed(0x30)
        val modulus = sequence.readPositiveInteger()
        val exponent = sequence.readPositiveInteger()
        require(sequence.exhausted() && outer.exhausted()) { "Trailing data in RSA public key" }
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
    }

    private fun pkcs1PublicKey(publicKey: RSAPublicKey): ByteArray = sequence(
        integer(publicKey.modulus),
        integer(publicKey.publicExponent),
    )

    private fun distinguishedName(commonName: String?): ByteArray =
        if (commonName == null) sequence() else sequence(set(sequence(objectIdentifier("2.5.4.3"), utf8String(commonName))))

    private fun pem(label: String, der: ByteArray): ByteArray {
        val encoded = Base64Codec.encode(der).chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$encoded\n-----END $label-----\n".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun algorithmIdentifier(oid: String): ByteArray = sequence(objectIdentifier(oid), der(0x05, ByteArray(0)))
    private fun sequence(vararg values: ByteArray): ByteArray = der(0x30, concatenate(*values))
    private fun set(vararg values: ByteArray): ByteArray = der(0x31, concatenate(*values))
    private fun explicit(number: Int, value: ByteArray): ByteArray = der(0xa0 + number, value)
    private fun utf8String(value: String): ByteArray = der(0x0c, value.toByteArray(StandardCharsets.UTF_8))

    private fun certificateTime(valueMillis: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = SimpleDateFormat("yyyy", Locale.US).apply { timeZone = utc }
            .format(Date(valueMillis)).toInt()
        val utcTime = year <= UTC_TIME_LAST_YEAR
        val formatter = SimpleDateFormat(
            if (utcTime) "yyMMddHHmmss'Z'" else "yyyyMMddHHmmss'Z'",
            Locale.US,
        ).apply { timeZone = utc }
        return der(if (utcTime) 0x17 else 0x18, formatter.format(Date(valueMillis)).toByteArray(StandardCharsets.US_ASCII))
    }

    private fun integer(value: BigInteger): ByteArray {
        require(value.signum() >= 0) { "DER integer must be non-negative" }
        return der(0x02, value.toByteArray())
    }

    private fun boolean(value: Boolean): ByteArray = der(0x01, byteArrayOf(if (value) 0xff.toByte() else 0))
    private fun octetString(value: ByteArray): ByteArray = der(0x04, value)
    private fun bitString(value: ByteArray, unusedBits: Int = 0): ByteArray {
        require(unusedBits in 0..7) { "Invalid number of unused bits" }
        return der(0x03, byteArrayOf(unusedBits.toByte()) + value)
    }

    private fun objectIdentifier(value: String): ByteArray {
        val arcs = value.split('.').map(String::toLong)
        require(arcs.size >= 2 && arcs[0] in 0..2 && arcs[1] >= 0 && (arcs[0] == 2L || arcs[1] < 40)) {
            "Invalid object identifier"
        }
        val output = ByteArrayOutputStream()
        writeBase128(output, arcs[0] * 40 + arcs[1])
        arcs.drop(2).forEach { arc -> require(arc >= 0) { "Invalid object identifier" }; writeBase128(output, arc) }
        return der(0x06, output.toByteArray())
    }

    private fun writeBase128(output: ByteArrayOutputStream, value: Long) {
        var remaining = value
        val bytes = ByteArray(10)
        var offset = bytes.size
        bytes[--offset] = (remaining and 0x7f).toByte()
        remaining = remaining ushr 7
        while (remaining != 0L) {
            bytes[--offset] = ((remaining and 0x7f) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        output.write(bytes, offset, bytes.size - offset)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(1 + 5 + content.size)
        output.write(tag)
        if (content.size < 0x80) output.write(content.size) else {
            var length = content.size
            val bytes = ByteArray(4)
            var offset = bytes.size
            while (length != 0) { bytes[--offset] = length.toByte(); length = length ushr 8 }
            output.write(0x80 or (bytes.size - offset))
            output.write(bytes, offset, bytes.size - offset)
        }
        output.write(content)
        return output.toByteArray()
    }

    private fun concatenate(vararg values: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(values.sumOf(ByteArray::size))
        values.forEach(output::write)
        return output.toByteArray()
    }

    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readConstructed(expectedTag: Int): DerReader = DerReader(readValue(expectedTag))

        fun readPositiveInteger(): BigInteger {
            val value = readValue(0x02)
            require(value.isNotEmpty() && (value[0].toInt() and 0x80) == 0) { "Invalid positive DER integer" }
            require(value.size == 1 || value[0].toInt() != 0 || (value[1].toInt() and 0x80) != 0) {
                "Non-minimal positive DER integer"
            }
            return BigInteger(value)
        }

        fun exhausted(): Boolean = offset == bytes.size

        private fun readValue(expectedTag: Int): ByteArray {
            require(offset < bytes.size && bytes[offset++].toInt() and 0xff == expectedTag) { "Unexpected DER tag" }
            require(offset < bytes.size) { "Missing DER length" }
            val first = bytes[offset++].toInt() and 0xff
            val length = if (first < 0x80) first else {
                val count = first and 0x7f
                require(count in 1..4 && offset + count <= bytes.size) { "Invalid DER length" }
                require(bytes[offset].toInt() != 0) { "Non-minimal DER length" }
                var result = 0
                repeat(count) { result = (result shl 8) or (bytes[offset++].toInt() and 0xff) }
                val minimumCount = (Integer.SIZE - Integer.numberOfLeadingZeros(result) + 7) / 8
                require(result >= 0x80 && count == minimumCount) { "Non-minimal DER length" }
                result
            }
            require(length >= 0 && offset + length <= bytes.size) { "DER value is truncated" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }
    }
}

/** The small plist value set needed by Lockdown messages. */
sealed class LockdownPlistValue {
    data class Dictionary(val entries: Map<String, LockdownPlistValue>) : LockdownPlistValue()
    data class Text(val value: String) : LockdownPlistValue()
    data class Integer(val value: Long) : LockdownPlistValue()
    data class Boolean(val value: kotlin.Boolean) : LockdownPlistValue()

    class Data(bytes: ByteArray) : LockdownPlistValue() {
        private val value = bytes.copyOf()

        val bytes: ByteArray
            get() = value.copyOf()
    }
}

/**
 * Blocking Lockdown plist framing over one already-connected USBMUX TCP byte stream.
 *
 * A frame is a four-byte big-endian XML plist byte length followed by UTF-8 XML. This class does
 * not pair, start a session or service, negotiate TLS, or establish any CarPlay/carkit session.
 * Calls may block and must run away from Android's main thread.
 */
class LockdownPlistChannel(
    private val connection: DuplexByteStream,
    private val maximumMessageBytes: Int = DEFAULT_MAXIMUM_MESSAGE_BYTES,
    private val defaultTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : Closeable {
    private val stateLock = Any()
    private val ioLock = Any()
    private var closed = false
    private var detached = false

    init {
        require(maximumMessageBytes in 1..ABSOLUTE_MAXIMUM_MESSAGE_BYTES) {
            "maximumMessageBytes must be between 1 and $ABSOLUTE_MAXIMUM_MESSAGE_BYTES"
        }
        require(defaultTimeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "defaultTimeoutMillis must be between 1 and $MAXIMUM_TIMEOUT_MILLIS"
        }
    }

    /** Sends one root dictionary as a length-prefixed XML plist. */
    fun send(message: LockdownPlistValue.Dictionary) = synchronized(ioLock) {
        checkOpen()
        sendLocked(message)
    }

    /** Receives one root dictionary, consuming exactly one framed XML plist. */
    fun receive(timeoutMillis: Long = defaultTimeoutMillis): LockdownPlistValue.Dictionary = synchronized(ioLock) {
        checkOpen()
        receiveLocked(timeoutMillis)
    }

    /** Sends [message] and receives its corresponding response without interleaving another request. */
    fun request(
        message: LockdownPlistValue.Dictionary,
        timeoutMillis: Long = defaultTimeoutMillis,
    ): LockdownPlistValue.Dictionary = synchronized(ioLock) {
        validateTimeout(timeoutMillis)
        checkOpen()
        sendLocked(message)
        receiveLocked(timeoutMillis)
    }

    /**
     * Permanently closes this channel without closing its stream and transfers stream ownership.
     *
     * Detach is serialized after any active request and may succeed exactly once. Later channel
     * operations fail as closed; later [close] calls are no-ops for the detached stream.
     */
    fun detach(): DuplexByteStream = synchronized(ioLock) {
        synchronized(stateLock) {
            if (closed) {
                val state = if (detached) "already detached" else "closed"
                throw LockdownException.DeviceUnavailable("Lockdown plist channel is $state")
            }
            detached = true
            closed = true
            connection
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose && !detached) connection.close()
    }

    private fun sendLocked(message: LockdownPlistValue.Dictionary) {
        val xml = encode(message).toByteArray(StandardCharsets.UTF_8)
        if (xml.isEmpty() || xml.size > maximumMessageBytes) {
            throw LockdownException.Protocol("Lockdown plist message length ${xml.size} is outside 1..$maximumMessageBytes")
        }
        val frame = ByteArray(LENGTH_BYTES + xml.size)
        putU32(frame, 0, xml.size.toLong())
        xml.copyInto(frame, LENGTH_BYTES)
        connection.send(frame)
    }

    private fun receiveLocked(timeoutMillis: Long): LockdownPlistValue.Dictionary {
        validateTimeout(timeoutMillis)
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        val header = readFully(LENGTH_BYTES, deadlineNanos)
        val length = readU32(header, 0)
        if (length !in 1..maximumMessageBytes.toLong()) {
            throw LockdownException.Protocol("Invalid Lockdown plist message length $length (maximum $maximumMessageBytes)")
        }
        val xml = readFully(length.toInt(), deadlineNanos)
        return parse(xml)
    }

    private fun readFully(byteCount: Int, deadlineNanos: Long): ByteArray {
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < result.size) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw LockdownException.TimedOut("Timed out while reading Lockdown plist frame")
            }
            val timeoutMillis = maxOf(1L, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
            val chunk = connection.recv(result.size - offset, timeoutMillis)
                ?: throw LockdownException.TimedOut("Timed out while reading Lockdown plist frame")
            if (chunk.isEmpty()) {
                throw LockdownException.Protocol("USBMUX TCP stream ended inside a Lockdown plist frame")
            }
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        return result
    }

    private fun parse(xml: ByteArray): LockdownPlistValue.Dictionary {
        try {
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                setInput(StringReader(xml.toString(StandardCharsets.UTF_8)))
            }
            if (parser.nextTag() != XmlPullParser.START_TAG || parser.name != "plist") {
                throw LockdownException.Protocol("Lockdown plist root must be <plist>")
            }
            if (parser.nextTag() != XmlPullParser.START_TAG) {
                throw LockdownException.Protocol("Lockdown plist must contain exactly one root value")
            }
            val value = parseValue(parser, 0)
            if (parser.nextTag() != XmlPullParser.END_TAG || parser.name != "plist") {
                throw LockdownException.Protocol("Lockdown plist must contain exactly one root value")
            }
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        if (!parser.text.orEmpty().isBlank()) {
                            throw LockdownException.Protocol("Unexpected content after Lockdown plist")
                        }
                    }
                    else -> throw LockdownException.Protocol("Unexpected content after Lockdown plist")
                }
            }
            return value as? LockdownPlistValue.Dictionary
                ?: throw LockdownException.Protocol("Lockdown plist root value must be <dict>")
        } catch (error: LockdownException) {
            throw error
        } catch (error: Exception) {
            throw LockdownException.Protocol("Invalid Lockdown plist XML: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun parseValue(parser: XmlPullParser, depth: Int): LockdownPlistValue {
        if (depth > MAXIMUM_NESTING) throw LockdownException.Protocol("Lockdown plist nesting exceeds $MAXIMUM_NESTING")
        return when (parser.name) {
            "dict" -> {
                val entries = LinkedHashMap<String, LockdownPlistValue>()
                while (parser.nextTag() == XmlPullParser.START_TAG) {
                    if (parser.name != "key" || entries.size == MAXIMUM_DICTIONARY_ENTRIES) {
                        throw LockdownException.Protocol("Invalid Lockdown plist dictionary")
                    }
                    val keyText = simpleText(parser, "key")
                    if (entries.containsKey(keyText)) throw LockdownException.Protocol("Duplicate Lockdown plist key '$keyText'")
                    if (parser.nextTag() != XmlPullParser.START_TAG) {
                        throw LockdownException.Protocol("Lockdown plist dictionary value is missing")
                    }
                    entries[keyText] = parseValue(parser, depth + 1)
                }
                if (parser.name != "dict") throw LockdownException.Protocol("Invalid Lockdown plist dictionary")
                LockdownPlistValue.Dictionary(entries)
            }
            "string" -> LockdownPlistValue.Text(simpleText(parser, "string"))
            "integer" -> LockdownPlistValue.Integer(
                simpleText(parser, "integer").trim().toLongOrNull()
                    ?: throw LockdownException.Protocol("Invalid Lockdown plist integer"),
            )
            "true" -> {
                requireEmpty(parser, "true")
                LockdownPlistValue.Boolean(true)
            }
            "false" -> {
                requireEmpty(parser, "false")
                LockdownPlistValue.Boolean(false)
            }
            "data" -> {
                val encoded = simpleText(parser, "data").filterNot(Char::isWhitespace)
                try {
                    LockdownPlistValue.Data(Base64Codec.decode(encoded))
                } catch (error: IllegalArgumentException) {
                    throw LockdownException.Protocol("Invalid base64 Lockdown plist data")
                }
            }
            else -> throw LockdownException.Protocol("Unsupported Lockdown plist value <${parser.name}>")
        }
    }

    private fun encode(message: LockdownPlistValue.Dictionary): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
        append("<plist version=\"1.0\">")
        appendValue(message, 0)
        append("</plist>")
    }

    private fun StringBuilder.appendValue(value: LockdownPlistValue, depth: Int) {
        if (depth > MAXIMUM_NESTING) throw LockdownException.Protocol("Lockdown plist nesting exceeds $MAXIMUM_NESTING")
        when (value) {
            is LockdownPlistValue.Dictionary -> {
                if (value.entries.size > MAXIMUM_DICTIONARY_ENTRIES) {
                    throw LockdownException.Protocol("Lockdown plist dictionary exceeds $MAXIMUM_DICTIONARY_ENTRIES entries")
                }
                append("<dict>")
                value.entries.forEach { (key, entryValue) ->
                    append("<key>").appendEscaped(key).append("</key>")
                    appendValue(entryValue, depth + 1)
                }
                append("</dict>")
            }
            is LockdownPlistValue.Text -> append("<string>").appendEscaped(value.value).append("</string>")
            is LockdownPlistValue.Integer -> append("<integer>").append(value.value).append("</integer>")
            is LockdownPlistValue.Boolean -> append(if (value.value) "<true/>" else "<false/>")
            is LockdownPlistValue.Data -> append("<data>")
                .append(Base64Codec.encode(value.bytes))
                .append("</data>")
        }
    }

    private fun StringBuilder.appendEscaped(value: String): StringBuilder = apply {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character.toString()
                },
            )
        }
    }

    private fun simpleText(parser: XmlPullParser, name: String): String {
        val text = StringBuilder()
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(parser.text.orEmpty())
                XmlPullParser.ENTITY_REF -> text.append(
                    parser.text ?: throw LockdownException.Protocol("Unknown entity in Lockdown plist <$name>"),
                )
                XmlPullParser.END_TAG -> {
                    if (parser.name != name) throw LockdownException.Protocol("Invalid Lockdown plist <$name>")
                    return text.toString()
                }
                XmlPullParser.START_TAG -> throw LockdownException.Protocol("Lockdown plist <$name> must not contain elements")
                XmlPullParser.END_DOCUMENT -> throw LockdownException.Protocol("Lockdown plist ended inside <$name>")
            }
        }
    }

    private fun requireEmpty(parser: XmlPullParser, name: String) {
        if (parser.nextTag() != XmlPullParser.END_TAG || parser.name != name) {
            throw LockdownException.Protocol("Lockdown plist <$name> must be empty")
        }
    }

    private fun checkOpen() {
        synchronized(stateLock) {
            if (closed) throw LockdownException.DeviceUnavailable("Lockdown plist channel is closed")
        }
    }

    private fun validateTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be between 1 and $MAXIMUM_TIMEOUT_MILLIS"
        }
    }

    private companion object {
        const val LENGTH_BYTES = 4
        const val DEFAULT_MAXIMUM_MESSAGE_BYTES = 1 * 1024 * 1024
        const val ABSOLUTE_MAXIMUM_MESSAGE_BYTES = 4 * 1024 * 1024
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
        const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60_000L
        const val MAXIMUM_NESTING = 64
        const val MAXIMUM_DICTIONARY_ENTRIES = 1_024
        const val NANOS_PER_MILLISECOND = 1_000_000L

        fun putU32(target: ByteArray, offset: Int, value: Long) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        fun readU32(source: ByteArray, offset: Int): Long =
            ((source[offset].toLong() and 0xff) shl 24) or
                ((source[offset + 1].toLong() and 0xff) shl 16) or
                ((source[offset + 2].toLong() and 0xff) shl 8) or
                (source[offset + 3].toLong() and 0xff)
    }
}

/**
 * Builds an unstarted client TLS engine for the dedicated USB Lockdown channel.
 *
 * Matching the locked transport behavior, the engine does not authenticate the peer certificate.
 * It must not be reused for internet or general-purpose TLS connections.
 */
object LockdownTlsEngineFactory {
    @Throws(GeneralSecurityException::class)
    fun create(pairRecord: LockdownPairRecord): SSLEngine {
        val password = charArrayOf('l', 'o', 'c', 'k', 'd', 'o', 'w', 'n')
        // Lockdown presents the root identity from the pair record for both the session and
        // service TLS channels. HostCertificate is part of pairing, not this TLS identity.
        val privateKeyPem = pairRecord.rootPrivateKeyPem
        val certificatePem = pairRecord.rootCertificatePem
        var privateKeyDer: ByteArray? = null
        try {
            privateKeyDer = decodePkcs8Pem(privateKeyPem)
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyDer))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certificatePem)) as X509Certificate
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, password)
                setKeyEntry(KEY_ALIAS, privateKey, password, arrayOf(certificate))
            }
            val keyManagers = KeyManagerFactory.getInstance("PKIX").apply {
                init(keyStore, password)
            }.keyManagers
            val context = SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(UsbLockdownTrustManager), null)
            }
            return context.createSSLEngine(PEER_HOST, PEER_PORT).apply {
                useClientMode = true
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = null }
            }
        } finally {
            password.fill('\u0000')
            privateKeyPem.fill(0)
            certificatePem.fill(0)
            privateKeyDer?.fill(0)
        }
    }

    private fun decodePkcs8Pem(pem: ByteArray): ByteArray {
        val begin = pem.indexOf(BEGIN_PRIVATE_KEY)
        val end = pem.indexOf(END_PRIVATE_KEY, begin + BEGIN_PRIVATE_KEY.size)
        if (begin < 0 || end < 0) throw GeneralSecurityException("Invalid PKCS#8 private key PEM")
        val encoded = pem.copyOfRange(begin + BEGIN_PRIVATE_KEY.size, end)
        return try {
            Base64Codec.decode(encoded.toString(StandardCharsets.US_ASCII))
        } catch (error: IllegalArgumentException) {
            throw GeneralSecurityException("Invalid PKCS#8 private key PEM", error)
        } finally {
            encoded.fill(0)
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray, startIndex: Int = 0): Int {
        if (needle.isEmpty()) return startIndex.coerceIn(0, size)
        for (offset in startIndex.coerceAtLeast(0)..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return offset
        }
        return -1
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object UsbLockdownTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val KEY_ALIAS = "lockdown-host"
    private const val PEER_HOST = "Device"
    private const val PEER_PORT = 0
    private val BEGIN_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----".toByteArray(StandardCharsets.US_ASCII)
    private val END_PRIVATE_KEY = "-----END PRIVATE KEY-----".toByteArray(StandardCharsets.US_ASCII)
}

/**
 * Blocking plaintext stream carried by one client-mode TLS engine and one owned byte stream.
 *
 * [open] completes the TLS handshake before returning. Calls must run on worker threads. This
 * class does not send Lockdown StartSession/StartService messages and does not persist records.
 */
class TlsDuplexChannel private constructor(
    private val underlying: DuplexByteStream,
    private val engine: SSLEngine,
) : DuplexByteStream {
    private val stateLock = Any()
    private val engineLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private val encryptedInput = ByteQueue()
    private val plaintextInput = ByteQueue()
    private var closed = false
    private var inboundEnded = false
    private var failure: LockdownException? = null

    override fun send(data: ByteArray) = synchronized(writeLock) {
        checkWritable()
        if (data.isEmpty()) return@synchronized
        try {
            val source = ByteBuffer.wrap(data)
            while (source.hasRemaining()) {
                val outcome = wrap(source)
                sendEncrypted(outcome.bytes)
                if (outcome.result.status == SSLEngineResult.Status.CLOSED) {
                    throw LockdownException.Protocol("TLS outbound stream closed during send")
                }
                if (progressPostHandshake(outcome.result.handshakeStatus)) {
                    throw LockdownException.Protocol("TLS send requires inbound post-handshake processing")
                }
                if (outcome.result.bytesConsumed() == 0 && outcome.bytes.isEmpty()) {
                    throw LockdownException.Protocol("TLS send made no progress")
                }
            }
        } catch (error: Exception) {
            throw fail(error)
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        try {
            receiveLocked(maxBytes, Deadline(timeoutMillis))
        } catch (error: Exception) {
            throw fail(error)
        }
    }

    override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) false else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        try {
            synchronized(writeLock) {
                synchronized(engineLock) {
                    if (!engine.isOutboundDone) engine.closeOutbound()
                }
                var steps = 0
                while (steps++ < MAXIMUM_CLOSE_WRAP_STEPS &&
                    !synchronized(engineLock) { engine.isOutboundDone }
                ) {
                    val outcome = wrap(ByteBuffer.allocate(0))
                    sendEncrypted(outcome.bytes)
                    var status = outcome.result.handshakeStatus
                    if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                        status = runDelegatedTasks()
                    }
                    if (outcome.result.status == SSLEngineResult.Status.CLOSED ||
                        status != SSLEngineResult.HandshakeStatus.NEED_WRAP
                    ) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // close_notify is best effort; closing the owned transport is the final boundary.
        } finally {
            try {
                underlying.close()
            } catch (_: Exception) {
                // The local state remains closed.
            }
        }
    }

    private fun performHandshake(timeoutMillis: Long) {
        val deadline = Deadline(timeoutMillis)
        var status = synchronized(engineLock) {
            engine.beginHandshake()
            engine.handshakeStatus
        }
        var noProgress = 0
        while (!isHandshakeComplete(status)) {
            deadline.requireRemainingMillis()
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    status = runDelegatedTasks()
                    noProgress = 0
                }
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val outcome = synchronized(writeLock) {
                        wrap(ByteBuffer.allocate(0)).also { sendEncrypted(it.bytes) }
                    }
                    if (outcome.result.status == SSLEngineResult.Status.CLOSED) {
                        throw LockdownException.Protocol("TLS handshake closed while wrapping")
                    }
                    status = outcome.result.handshakeStatus
                    noProgress = checkProgress(outcome, noProgress)
                }
                isNeedUnwrap(status) -> {
                    val outcome = unwrap()
                    plaintextInput.append(outcome.bytes)
                    when (outcome.result.status) {
                        SSLEngineResult.Status.OK -> {
                            status = outcome.result.handshakeStatus
                            noProgress = checkProgress(outcome, noProgress)
                        }
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            val incoming = underlying.recv(RECEIVE_CHUNK_BYTES, deadline.requireRemainingMillis())
                                ?: throw LockdownException.TimedOut("TLS handshake timed out")
                            if (incoming.isEmpty()) {
                                throw LockdownException.Protocol("TLS transport ended during handshake")
                            }
                            encryptedInput.append(incoming)
                            noProgress = 0
                        }
                        SSLEngineResult.Status.CLOSED -> {
                            throw LockdownException.Protocol("TLS peer closed during handshake")
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW -> error("unwrap handles overflow internally")
                    }
                }
                else -> throw LockdownException.Protocol("Unsupported TLS handshake state ${status.name}")
            }
        }
        deadline.requireRemainingMillis()
    }

    private fun receiveLocked(maxBytes: Int, deadline: Deadline): ByteArray? {
        plaintextInput.take(maxBytes)?.let { return it }
        synchronized(stateLock) {
            failure?.let { throw it }
            if (inboundEnded) return ByteArray(0)
            if (closed) throw LockdownException.DeviceUnavailable("TLS channel is closed")
        }

        var noProgress = 0
        while (true) {
            if (deadline.remainingMillis() == null) return null
            val outcome = unwrap()
            plaintextInput.append(outcome.bytes)
            when (outcome.result.status) {
                SSLEngineResult.Status.OK -> {
                    noProgress = checkProgress(outcome, noProgress)
                    progressPostHandshake(outcome.result.handshakeStatus)
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                    plaintextInput.take(maxBytes)?.let { return it }
                    val remaining = deadline.remainingMillis() ?: return null
                    val incoming = underlying.recv(RECEIVE_CHUNK_BYTES, remaining) ?: return null
                    if (incoming.isEmpty()) {
                        if (encryptedInput.size != 0) {
                            throw LockdownException.Protocol("TLS transport ended inside a record")
                        }
                        throw LockdownException.Protocol("TLS transport ended without close_notify")
                    }
                    encryptedInput.append(incoming)
                    noProgress = 0
                    continue
                }
                SSLEngineResult.Status.CLOSED -> {
                    plaintextInput.take(maxBytes)?.let {
                        finishInbound()
                        return it
                    }
                    finishInbound()
                    return ByteArray(0)
                }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> error("unwrap handles overflow internally")
            }
            plaintextInput.take(maxBytes)?.let { return it }
        }
    }

    /** Returns true only when further inbound TLS processing is required. */
    private fun progressPostHandshake(initialStatus: SSLEngineResult.HandshakeStatus): Boolean {
        var status = initialStatus
        var steps = 0
        while (true) {
            if (++steps > MAXIMUM_CONTROL_STEPS) {
                throw LockdownException.Protocol("TLS post-handshake processing did not converge")
            }
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> status = runDelegatedTasks()
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val outcome = synchronized(writeLock) {
                        wrap(ByteBuffer.allocate(0)).also { sendEncrypted(it.bytes) }
                    }
                    if (outcome.result.status == SSLEngineResult.Status.CLOSED) {
                        throw LockdownException.Protocol("TLS outbound stream closed during post-handshake processing")
                    }
                    if (outcome.result.bytesConsumed() == 0 && outcome.bytes.isEmpty() &&
                        outcome.result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP
                    ) {
                        throw LockdownException.Protocol("TLS post-handshake wrap made no progress")
                    }
                    status = outcome.result.handshakeStatus
                }
                isNeedUnwrap(status) -> return true
                isHandshakeComplete(status) -> return false
                else -> throw LockdownException.Protocol("Unsupported TLS post-handshake state ${status.name}")
            }
        }
    }

    private fun runDelegatedTasks(): SSLEngineResult.HandshakeStatus = synchronized(engineLock) {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
        engine.handshakeStatus
    }

    private fun wrap(source: ByteBuffer): EngineOutcome {
        var capacity = synchronized(engineLock) { engine.session.packetBufferSize }.coerceAtLeast(1)
        while (true) {
            if (capacity > MAXIMUM_TLS_BUFFER_BYTES) {
                throw LockdownException.Protocol("TLS packet buffer exceeds the defensive limit")
            }
            val output = ByteBuffer.allocate(capacity)
            val result = synchronized(engineLock) { engine.wrap(source, output) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                if (result.bytesConsumed() != 0 || result.bytesProduced() != 0) {
                    throw LockdownException.Protocol("TLS wrap overflow consumed data")
                }
                capacity = grow(capacity)
                continue
            }
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                throw LockdownException.Protocol("TLS wrap reported buffer underflow")
            }
            output.flip()
            return EngineOutcome(result, ByteArray(output.remaining()).also(output::get))
        }
    }

    private fun unwrap(): EngineOutcome {
        var capacity = synchronized(engineLock) { engine.session.applicationBufferSize }.coerceAtLeast(1)
        while (true) {
            if (capacity > MAXIMUM_TLS_BUFFER_BYTES) {
                throw LockdownException.Protocol("TLS application buffer exceeds the defensive limit")
            }
            val input = encryptedInput.asByteBuffer()
            val output = ByteBuffer.allocate(capacity)
            val result = synchronized(engineLock) { engine.unwrap(input, output) }
            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                if (result.bytesConsumed() != 0 || result.bytesProduced() != 0) {
                    throw LockdownException.Protocol("TLS unwrap overflow consumed data")
                }
                capacity = grow(capacity)
                continue
            }
            encryptedInput.discard(result.bytesConsumed())
            output.flip()
            return EngineOutcome(result, ByteArray(output.remaining()).also(output::get))
        }
    }

    private fun sendEncrypted(bytes: ByteArray) {
        if (bytes.isNotEmpty()) underlying.send(bytes)
    }

    private fun finishInbound() {
        synchronized(stateLock) { inboundEnded = true }
        close()
    }

    private fun checkWritable() = synchronized(stateLock) {
        failure?.let { throw it }
        if (closed) throw LockdownException.DeviceUnavailable("TLS channel is closed")
    }

    private fun checkProgress(outcome: EngineOutcome, previous: Int): Int {
        if (outcome.result.bytesConsumed() != 0 || outcome.bytes.isNotEmpty()) return 0
        if (previous + 1 >= MAXIMUM_NO_PROGRESS_STEPS) {
            throw LockdownException.Protocol("TLS engine made no progress")
        }
        return previous + 1
    }

    private fun fail(error: Exception): LockdownException {
        val mapped = when (error) {
            is LockdownException -> error
            else -> LockdownException.Protocol("TLS channel failed (${error.javaClass.simpleName})", error)
        }
        val shouldClose = synchronized(stateLock) {
            if (failure == null) failure = mapped
            if (closed) false else {
                closed = true
                true
            }
        }
        if (shouldClose) {
            try {
                underlying.close()
            } catch (_: Exception) {
                // Preserve the original failure.
            }
        }
        return synchronized(stateLock) { failure ?: mapped }
    }

    private fun grow(capacity: Int): Int {
        if (capacity >= MAXIMUM_TLS_BUFFER_BYTES) return MAXIMUM_TLS_BUFFER_BYTES + 1
        return minOf(MAXIMUM_TLS_BUFFER_BYTES, capacity * 2)
    }

    private data class EngineOutcome(
        val result: SSLEngineResult,
        val bytes: ByteArray,
    )

    private class Deadline(timeoutMillis: Long) {
        private val startNanos = System.nanoTime()
        private val timeoutNanos = minOf(timeoutMillis, Long.MAX_VALUE / NANOS_PER_MILLISECOND) *
            NANOS_PER_MILLISECOND

        fun remainingMillis(): Long? {
            val remaining = timeoutNanos - (System.nanoTime() - startNanos)
            if (remaining <= 0) return null
            return (remaining - 1) / NANOS_PER_MILLISECOND + 1
        }

        fun requireRemainingMillis(): Long = remainingMillis()
            ?: throw LockdownException.TimedOut("TLS handshake timed out")
    }

    private class ByteQueue {
        private var bytes = ByteArray(0)
        private var offset = 0

        val size: Int
            get() = bytes.size - offset

        fun append(value: ByteArray) {
            if (value.isEmpty()) return
            if (size > MAXIMUM_TLS_BUFFER_BYTES - value.size) {
                throw LockdownException.Protocol("TLS buffered data exceeds the defensive limit")
            }
            val combined = ByteArray(size + value.size)
            bytes.copyInto(combined, 0, offset)
            value.copyInto(combined, size)
            bytes = combined
            offset = 0
        }

        fun asByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes, offset, size).slice()

        fun discard(count: Int) {
            require(count in 0..size) { "Invalid TLS queue discard" }
            offset += count
            if (offset == bytes.size) {
                bytes = ByteArray(0)
                offset = 0
            }
        }

        fun take(maxBytes: Int): ByteArray? {
            if (size == 0) return null
            val count = minOf(maxBytes, size)
            return bytes.copyOfRange(offset, offset + count).also { discard(count) }
        }
    }

    companion object {
        private val ALLOWED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")
        private const val RECEIVE_CHUNK_BYTES = 16 * 1024
        private const val MAXIMUM_TLS_BUFFER_BYTES = 1024 * 1024
        private const val MAXIMUM_CONTROL_STEPS = 32
        private const val MAXIMUM_NO_PROGRESS_STEPS = 8
        private const val MAXIMUM_CLOSE_WRAP_STEPS = 4
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Creates and fully handshakes a TLS client stream, taking ownership of [underlying]. */
        @JvmStatic
        @Throws(LockdownException::class, GeneralSecurityException::class)
        fun open(
            underlying: DuplexByteStream,
            pairRecord: LockdownPairRecord,
            handshakeTimeoutMillis: Long = 5_000,
        ): TlsDuplexChannel {
            require(handshakeTimeoutMillis > 0) { "handshakeTimeoutMillis must be positive" }
            try {
                val engine = LockdownTlsEngineFactory.create(pairRecord)
                val supported = engine.supportedProtocols.toSet()
                val enabled = ALLOWED_PROTOCOLS.filter(supported::contains).toTypedArray()
                if (enabled.isEmpty()) {
                    throw LockdownException.Protocol("TLS engine supports neither TLSv1.2 nor TLSv1.3")
                }
                engine.enabledProtocols = enabled
                return TlsDuplexChannel(underlying, engine).also {
                    it.performHandshake(handshakeTimeoutMillis)
                }
            } catch (error: Exception) {
                try {
                    underlying.close()
                } catch (_: Exception) {
                    // Preserve the handshake failure.
                }
                throw error
            }
        }

        private fun isHandshakeComplete(status: SSLEngineResult.HandshakeStatus): Boolean =
            status == SSLEngineResult.HandshakeStatus.FINISHED ||
                status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING

        private fun isNeedUnwrap(status: SSLEngineResult.HandshakeStatus): Boolean =
            status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || status.name == "NEED_UNWRAP_AGAIN"
    }
}

/**
 * Blocking, plaintext Lockdown pairing over an already-open USBMUX TCP connector.
 *
 * Call [pair] from a worker thread, never Android's main thread. This client owns only the
 * connection it creates: it closes the plist channel (and therefore that connection) after every
 * outcome, but leaves [connector] open for its caller.
 */
class LockdownPairingClient(
    private val connector: LockdownTcpConnector,
) {
    /**
     * Fetches the two pairing inputs, generates the local record, and sends plaintext Pair.
     *
     * [totalTimeoutMillis] covers the entire operation, including a pending pairing dialog.
     * [isCancelled] is checked between blocking protocol operations and while waiting to retry a
     * pending dialog; it cannot interrupt an already-blocking plist read, which is bounded to
     * five seconds.
     */
    @Throws(LockdownException::class, LockdownPairingException::class, GeneralSecurityException::class)
    fun pair(
        label: String,
        hostId: String,
        systemBuid: String,
        totalTimeoutMillis: Long,
        isCancelled: () -> Boolean = { false },
    ): PairedRecord {
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostId.isNotBlank()) { "hostId must not be blank" }
        require(systemBuid.isNotBlank()) { "systemBuid must not be blank" }
        require(totalTimeoutMillis in 1..MAXIMUM_TOTAL_TIMEOUT_MILLIS) {
            "totalTimeoutMillis must be between 1 and $MAXIMUM_TOTAL_TIMEOUT_MILLIS"
        }

        val deadline = Deadline(totalTimeoutMillis)
        var pairRecord: LockdownPairRecord? = null
        var attempt = 0
        while (true) {
            checkCancelled(isCancelled)
            attempt += 1
            val connection = connector.connect(
                destinationPort = LOCKDOWN_PORT,
                timeoutMillis = stepTimeoutMillis(deadline),
            )
            var pending = false
            LockdownPlistChannel(connection).use { channel ->
                setUntrustedHostBuid(channel, label, systemBuid, deadline, isCancelled)
                val record = pairRecord ?: generatePairRecord(
                    channel = channel,
                    label = label,
                    hostId = hostId,
                    systemBuid = systemBuid,
                    deadline = deadline,
                    isCancelled = isCancelled,
                ).also {
                    pairRecord = it
                    Log.i(
                        TAG,
                        "lockdown pair prepared hostId=uuid systemBuid=uuid " +
                            "deviceKey=${it.devicePublicKeyPem.size} deviceCert=${it.deviceCertificatePem.size} " +
                            "hostCert=${it.hostCertificatePem.size} rootCert=${it.rootCertificatePem.size}",
                    )
                }
                Log.i(TAG, "lockdown pair attempt=$attempt")
                val response = channel.request(pairRequest(label, record), stepTimeoutMillis(deadline))
                checkCancelled(isCancelled)
                when (val error = response.errorCodeOrNull()) {
                    null -> return PairedRecord(record, response.entries["EscrowBag"].asOptionalData())
                    "PairingDialogResponsePending" -> pending = true
                    "UserDeniedPairing" -> throw LockdownPairingException.UserDeniedPairing
                    "PasswordProtected" -> throw LockdownPairingException.PasswordProtected
                    else -> throw LockdownPairingException.RemoteError(error)
                }
            }
            if (pending) {
                Log.i(TAG, "lockdown trust pending; reconnecting before retry")
                waitForRetry(deadline, isCancelled)
            }
        }
    }

    private fun generatePairRecord(
        channel: LockdownPlistChannel,
        label: String,
        hostId: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPairRecord {
        val devicePublicKey = getValue(channel, label, "DevicePublicKey", deadline, isCancelled)
            as? LockdownPlistValue.Data
            ?: throw LockdownPairingException.InvalidResponse("DevicePublicKey was not data")
        val wifiAddress = getValue(channel, label, "WiFiAddress", deadline, isCancelled)
            as? LockdownPlistValue.Text
            ?: throw LockdownPairingException.InvalidResponse("WiFiAddress was not text")
        return LockdownPairRecordGenerator.generate(
            devicePublicKeyPkcs1Pem = devicePublicKey.bytes,
            wifiAddress = wifiAddress.value,
            hostId = hostId,
            systemBuid = systemBuid,
        )
    }

    private fun pairRequest(
        label: String,
        pairRecord: LockdownPairRecord,
    ): LockdownPlistValue.Dictionary = LockdownPlistValue.Dictionary(
        linkedMapOf(
            "Label" to LockdownPlistValue.Text(label),
            "PairRecord" to pairRecord.toPairRequestDictionary(),
            "Request" to LockdownPlistValue.Text("Pair"),
            "ProtocolVersion" to LockdownPlistValue.Text("2"),
            "PairingOptions" to LockdownPlistValue.Dictionary(
                linkedMapOf("ExtendedPairingErrors" to LockdownPlistValue.Boolean(true)),
            ),
        ),
    )

    private fun setUntrustedHostBuid(
        channel: LockdownPlistChannel,
        label: String,
        systemBuid: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ) {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("SetValue"),
                    "Key" to LockdownPlistValue.Text("UntrustedHostBUID"),
                    "Value" to LockdownPlistValue.Text(systemBuid),
                ),
            ),
            stepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let { throw LockdownPairingException.RemoteError(it) }
        Log.i(TAG, "lockdown UntrustedHostBUID set")
    }

    private fun getValue(
        channel: LockdownPlistChannel,
        label: String,
        key: String,
        deadline: Deadline,
        isCancelled: () -> Boolean,
    ): LockdownPlistValue {
        checkCancelled(isCancelled)
        val response = channel.request(
            LockdownPlistValue.Dictionary(
                linkedMapOf(
                    "Label" to LockdownPlistValue.Text(label),
                    "Request" to LockdownPlistValue.Text("GetValue"),
                    "Key" to LockdownPlistValue.Text(key),
                ),
            ),
            stepTimeoutMillis(deadline),
        )
        checkCancelled(isCancelled)
        response.errorCodeOrNull()?.let { throw LockdownPairingException.RemoteError(it) }
        return response.entries["Value"]
            ?: throw LockdownPairingException.InvalidResponse("GetValue response omitted Value")
    }

    private fun LockdownPlistValue?.asOptionalData(): ByteArray? = when (this) {
        null -> null
        is LockdownPlistValue.Data -> bytes
        else -> throw LockdownPairingException.InvalidResponse("EscrowBag was not data")
    }

    private fun LockdownPlistValue.Dictionary.errorCodeOrNull(): String? {
        return when (val error = entries["Error"] ?: return null) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw LockdownPairingException.InvalidResponse("Error was not text or integer")
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw LockdownPairingException.Cancelled
    }

    private fun stepTimeoutMillis(deadline: Deadline): Long =
        minOf(MAXIMUM_STEP_TIMEOUT_MILLIS, deadline.remainingMillis())

    private fun waitForRetry(deadline: Deadline, isCancelled: () -> Boolean) {
        var remaining = RETRY_INTERVAL_MILLIS
        while (remaining > 0) {
            checkCancelled(isCancelled)
            val sleepMillis = minOf(RETRY_CHECK_MILLIS, remaining, deadline.remainingMillis())
            try {
                Thread.sleep(sleepMillis)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw LockdownPairingException.Cancelled
            }
            remaining -= sleepMillis
        }
    }

    private class Deadline(timeoutMillis: Long) {
        private val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND

        fun remainingMillis(): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw LockdownPairingException.TimedOut
            return (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
        }
    }

    companion object {
        const val LOCKDOWN_PORT = LockdownTcpConnector.LOCKDOWN_PORT
        private const val TAG = "tvplay-usb"
        private const val RETRY_INTERVAL_MILLIS = 1_000L
        private const val RETRY_CHECK_MILLIS = 100L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAXIMUM_STEP_TIMEOUT_MILLIS = 5_000L
        private const val MAXIMUM_TOTAL_TIMEOUT_MILLIS = 5 * 60_000L
    }
}

/** Successful pairing material kept in memory only. Byte-array accessors return defensive copies. */
class PairedRecord internal constructor(
    val pairRecord: LockdownPairRecord,
    escrowBag: ByteArray?,
) {
    private val storedEscrowBag = escrowBag?.copyOf()

    val escrowBag: ByteArray?
        get() = storedEscrowBag?.copyOf()

    override fun toString(): String = "PairedRecord(redacted)"
}

/** Typed pairing failures; their messages never include pairing keys, certificates, or escrow data. */
sealed class LockdownPairingException(message: String) : IOException(message) {
    data object Cancelled : LockdownPairingException("Lockdown pairing was cancelled")
    data object TimedOut : LockdownPairingException("Lockdown pairing timed out")
    data object UserDeniedPairing : LockdownPairingException("The user denied Lockdown pairing")
    data object PasswordProtected : LockdownPairingException("The iPhone is password protected")
    class RemoteError(val code: String) : LockdownPairingException("Lockdown pairing failed: $code")
    class InvalidResponse(message: String) : LockdownPairingException("Invalid Lockdown pairing response: $message")
}

/**
 * Blocking minimum Lockdown path that opens the `com.apple.carkit.service` byte stream.
 *
 * Connect, plist receive, and TLS engine budgets are five seconds; writes retain the underlying
 * transport's bounded timeout. The returned stream is owned by the caller.
 */
class LockdownCarKitClient(
    private val connector: LockdownTcpConnector,
) {
    @Throws(LockdownException::class, GeneralSecurityException::class)
    fun open(pairedRecord: PairedRecord, label: String): DuplexByteStream =
        open(pairedRecord.pairRecord, label)

    @Throws(LockdownException::class, GeneralSecurityException::class)
    fun open(pairRecord: LockdownPairRecord, label: String): DuplexByteStream =
        openService(pairRecord, label, CARKIT_SERVICE)

    fun openService(pairRecord: LockdownPairRecord, label: String, serviceName: String): DuplexByteStream {
        require(label.isNotBlank()) { "label must not be blank" }
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }

        val lockdownConnection = connector.connect(
            destinationPort = LOCKDOWN_PORT,
            timeoutMillis = STEP_TIMEOUT_MILLIS,
        )
        val sessionTls = LockdownPlistChannel(lockdownConnection).use { plaintext ->
            val response = plaintext.request(
                LockdownPlistValue.Dictionary(
                    linkedMapOf(
                        "Label" to LockdownPlistValue.Text(label),
                        "Request" to LockdownPlistValue.Text("StartSession"),
                        "HostID" to LockdownPlistValue.Text(pairRecord.hostId),
                        "SystemBUID" to LockdownPlistValue.Text(pairRecord.systemBuid),
                    ),
                ),
                STEP_TIMEOUT_MILLIS,
            )
            rejectError(response, "StartSession")
            val enableSessionSsl = (response.entries["EnableSessionSSL"] as? LockdownPlistValue.Boolean)?.value
                ?: throw LockdownException.Protocol("StartSession response omitted boolean EnableSessionSSL")
            if (!enableSessionSsl) {
                throw LockdownException.Protocol("StartSession did not enable TLS")
            }
            TlsDuplexChannel.open(
                underlying = plaintext.detach(),
                pairRecord = pairRecord,
                handshakeTimeoutMillis = STEP_TIMEOUT_MILLIS,
            )
        }

        val secureLockdown = LockdownPlistChannel(sessionTls)
        var serviceStream: DuplexByteStream? = null
        var handoffComplete = false
        try {
            val response = secureLockdown.request(
                LockdownPlistValue.Dictionary(
                    linkedMapOf(
                        "Request" to LockdownPlistValue.Text("StartService"),
                        "Service" to LockdownPlistValue.Text(serviceName),
                    ),
                ),
                STEP_TIMEOUT_MILLIS,
            )
            rejectError(response, "StartService")
            val port = (response.entries["Port"] as? LockdownPlistValue.Integer)?.value
                ?: throw LockdownException.Protocol("StartService response omitted integer Port")
            if (port !in 1..0xffff) {
                throw LockdownException.Protocol("StartService returned an invalid Port")
            }
            val enableServiceSsl = (response.entries["EnableServiceSSL"] as? LockdownPlistValue.Boolean)?.value
                ?: false

            val serviceConnection = connector.connect(
                destinationPort = port.toInt(),
                timeoutMillis = STEP_TIMEOUT_MILLIS,
            )
            val readyStream = if (enableServiceSsl) {
                TlsDuplexChannel.open(
                    underlying = serviceConnection,
                    pairRecord = pairRecord,
                    handshakeTimeoutMillis = STEP_TIMEOUT_MILLIS,
                )
            } else {
                serviceConnection
            }
            serviceStream = readyStream
            val ownedStream = CarkitServiceStream(readyStream, secureLockdown)
            handoffComplete = true
            serviceStream = null
            return ownedStream
        } finally {
            if (!handoffComplete) {
                try {
                    serviceStream?.close()
                } finally {
                    secureLockdown.close()
                }
            }
        }
    }

    /** Keeps the Lockdown session that created carkit alive for the service stream's lifetime. */
    private class CarkitServiceStream(
        private val service: DuplexByteStream,
        private val lockdown: Closeable,
    ) : DuplexByteStream {
        private val closed = AtomicBoolean(false)

        override fun send(data: ByteArray) = service.send(data)

        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            service.recv(maxBytes, timeoutMillis)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                service.close()
            } finally {
                lockdown.close()
            }
        }
    }

    private fun rejectError(response: LockdownPlistValue.Dictionary, request: String) {
        val code = when (val error = response.entries["Error"] ?: return) {
            is LockdownPlistValue.Text -> error.value
            is LockdownPlistValue.Integer -> (response.entries["ErrorString"] as? LockdownPlistValue.Text)?.value
                ?: error.value.toString()
            else -> throw LockdownException.Protocol("$request response Error was not text or integer")
        }
        throw LockdownException.Protocol("Lockdown $request failed: $code")
    }

    companion object {
        const val LOCKDOWN_PORT = LockdownTcpConnector.LOCKDOWN_PORT
        const val STEP_TIMEOUT_MILLIS = 5_000L
        const val CARKIT_SERVICE = "com.apple.carkit.service"
    }
}
