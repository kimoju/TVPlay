package app.tvplay.engine.auth

import android.os.Build
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Concatenates multiple byte arrays into a single contiguous [ByteArray]. */
fun concatBytes(vararg arrays: ByteArray): ByteArray {
    val output = ByteArray(arrays.sumOf { it.size })
    var offset = 0
    for (array in arrays) {
        array.copyInto(output, offset)
        offset += array.size
    }
    return output
}

/** Encodes this string as US-ASCII bytes. */
fun String.asciiBytes(): ByteArray = toByteArray(Charsets.US_ASCII)

/** Formats this byte array as lowercase hexadecimal characters. */
fun ByteArray.toHexString(): String {
    val alphabet = "0123456789abcdef"
    val output = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xff
        output[index * 2] = alphabet[value ushr 4]
        output[index * 2 + 1] = alphabet[value and 0x0f]
    }
    return String(output)
}

/** Formats up to [maxBytes] of this byte array as lowercase hexadecimal characters. */
fun ByteArray.hexPrefix(maxBytes: Int = 16): String =
    copyOf(minOf(size, maxBytes)).toHexString()

/** Decodes an even-length hexadecimal string into a [ByteArray]. */
fun String.hexToBytes(): ByteArray {
    val clean = trim()
    require(clean.length % 2 == 0) { "Hex string must have even length: ${clean.length}" }
    return ByteArray(clean.length / 2) { index ->
        clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

/** Cryptographic primitives for the CarPlay HomeKit pairing and control channel. */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private const val LABEL_SIZE = 8
    private const val MAX_RETAINED_BUFFER = 1024 * 1024
    private val random = SecureRandom()
    // Android's Conscrypt name first, then a desktop JDK's (unit tests).
    private val PLATFORM_CHACHA_NAMES = listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305")

    /**
     * The platform's ChaCha20-Poly1305 (Conscrypt on Android), used before BouncyCastle's Java one;
     * every video frame goes through it. Conscrypt gained its native direct-buffer AEAD path in
     * Android 12. Older supported releases use the array API to avoid extra staging copies. Buffers
     * for unusually large frames are temporary, so a frame cannot permanently retain large native
     * allocations on a long-lived receive thread.
     */
    private class PlatformChacha(val cipher: Cipher) {
        var input: ByteBuffer = ByteBuffer.allocateDirect(0)
        var output: ByteBuffer = ByteBuffer.allocateDirect(0)
        var implementation: String = cipher.provider.name
    }

    // ThreadLocal.withInitial needs API 26.
    private val platformState = object : ThreadLocal<PlatformChacha?>() {
        override fun initialValue(): PlatformChacha? =
            PLATFORM_CHACHA_NAMES.firstNotNullOfOrNull { runCatching { Cipher.getInstance(it) }.getOrNull() }
                ?.let(::PlatformChacha)
    }

    /** Implementation used by this thread's last successful operation, for diagnostics. */
    val chachaImplementation: String
        get() = platformState.get()?.implementation ?: "BouncyCastle"

    data class KeyPairBytes(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): KeyPairBytes {
        val privateKey = X25519PrivateKeyParameters(random)
        return KeyPairBytes(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519SharedSecret(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray =
        x25519SharedSecret(privateKeyRaw, peerPublicKeyRaw)

    fun ed25519Generate(): KeyPairBytes {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return KeyPairBytes(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: String,
        info: String,
        length: Int = 32,
    ): ByteArray = hkdfSha512(inputKeyMaterial, salt.asciiBytes(), info.asciiBytes(), length)

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun aesCtr128Transform(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray = platformChacha(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad)
        ?: chachaSealBouncyCastle(key, nonce, plaintext, aad).also {
            platformState.get()?.implementation = "BouncyCastle"
        }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray = platformChacha(Cipher.DECRYPT_MODE, key, nonce, ciphertextAndTag, aad)
        ?: chachaOpenBouncyCastle(key, nonce, ciphertextAndTag, aad).also {
            platformState.get()?.implementation = "BouncyCastle"
        }

    /**
     * Null when the platform has no ChaCha20-Poly1305 or refuses this use of it (a desktop JDK rejects
     * reusing a key and nonce for encryption, which tests do); a failed tag still throws.
     */
    internal fun platformChacha(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
        useDirectBuffers: Boolean = Build.VERSION.SDK_INT >= 31,
    ): ByteArray? {
        val state = platformState.get() ?: return null
        return try {
            val cipher = state.cipher
            cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            if (!useDirectBuffers) return cipher.doFinal(input).also { state.implementation = cipher.provider.name }
            val source = sized(state.input, input.size).also {
                if (it.capacity() <= MAX_RETAINED_BUFFER) state.input = it
            }
            source.put(input).flip()
            val target = sized(state.output, cipher.getOutputSize(input.size).coerceAtLeast(0)).also {
                if (it.capacity() <= MAX_RETAINED_BUFFER) state.output = it
            }
            val written = cipher.doFinal(source, target)
            target.flip()
            ByteArray(written).also { target.get(it); state.implementation = cipher.provider.name }
        } catch (failure: AEADBadTagException) {
            throw failure
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** [buffer], or a larger direct one, cleared and limited to [size]. */
    private fun sized(buffer: ByteBuffer, size: Int): ByteBuffer =
        (if (buffer.capacity() >= size) buffer else ByteBuffer.allocateDirect(
            if (size > MAX_RETAINED_BUFFER) size else maxOf(size, buffer.capacity() * 2).coerceAtMost(MAX_RETAINED_BUFFER),
        ))
            .apply { clear(); limit(size) }

    internal fun chachaSealBouncyCastle(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    internal fun chachaOpenBouncyCastle(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    /** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        return nonce
    }

    /** 12-byte (96-bit) nonce from up to 8 label bytes placed after four zero bytes. */
    fun nonce96(label: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        label.copyInto(nonce, 4, 0, minOf(label.size, LABEL_SIZE))
        return nonce
    }

    /** 12-byte (96-bit) nonce from an eight-byte ASCII label placed after four zero bytes. */
    fun nonce96(label: String): ByteArray = nonce96(label.asciiBytes())

    /** Alias for [nonce96] from an ASCII label. */
    fun nonceLabel(label: String): ByteArray = nonce96(label)

    private fun digest(digest: Digest, parts: Array<out ByteArray>): ByteArray {
        for (part in parts) digest.update(part, 0, part.size)
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }
}

/** Standard HomeKit / CarPlay TLV8 tags. */
object TlvTag {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val RETRY_DELAY = 0x08
    const val CERTIFICATE = 0x09
    const val SIGNATURE = 0x0a
    const val PERMISSIONS = 0x0b
    const val FRAGMENT_DATA = 0x0c
    const val FRAGMENT_LAST = 0x0d
    const val FLAGS = 0x13
    const val SEPARATOR = 0xff
}

data class Tlv8Item(val type: Int, val value: ByteArray)

/** HomeKit/AirPlay-style TLV8 codec used by the pairing messages. */
object Tlv8Codec {
    private const val SEPARATOR_TYPE = TlvTag.SEPARATOR
    private const val MAX_FRAGMENT = 255

    fun encode(items: List<Tlv8Item>): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var previousType: Int? = null
        for (item in items) {
            if (previousType == item.type) {
                chunks.add(byteArrayOf(SEPARATOR_TYPE.toByte(), 0))
            }
            var offset = 0
            do {
                val length = minOf(MAX_FRAGMENT, item.value.size - offset)
                chunks.add(byteArrayOf(item.type.toByte(), length.toByte()))
                chunks.add(item.value.copyOfRange(offset, offset + length))
                offset += length
            } while (offset < item.value.size)
            previousType = item.type
        }
        return concatBytes(*chunks.toTypedArray())
    }

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray =
        encode(items.map { Tlv8Item(it.first, it.second) })

    fun encode(items: Map<Int, ByteArray>): ByteArray =
        encode(items.map { Tlv8Item(it.key, it.value) })

    fun decode(buffer: ByteArray): Map<Int, ByteArray> {
        val output = HashMap<Int, ByteArray>()
        var position = 0
        var lastType: Int? = null
        var lastLength = 0
        while (position + 2 <= buffer.size) {
            val type = buffer[position].toInt() and 0xff
            val length = buffer[position + 1].toInt() and 0xff
            position += 2
            if (position + length > buffer.size) break
            val value = buffer.copyOfRange(position, position + length)
            position += length
            if (type == lastType && lastLength == MAX_FRAGMENT) {
                val previous = output[type]
                output[type] = if (previous != null) previous + value else value
            } else {
                output[type] = value
            }
            lastType = type
            lastLength = length
        }
        return output
    }
}

data class Srp6aResult(
    val ok: Boolean,
    val sessionKey: ByteArray? = null,
    val serverProof: ByteArray? = null,
)

/**
 * SRP-6a server session (RFC 5054 3072-bit group, SHA-512), the AirPlay pair-setup variant.
 *
 * The username is the literal "Pair-Setup" and the password is the fixed setup code.
 */
class Srp6aServer internal constructor(
    val salt: ByteArray,
    private val publicKeyB: BigInteger,
    private val identifier: ByteArray,
    private val verifier: BigInteger,
    private val privateExponent: BigInteger,
) {
    constructor(
        username: String = DEFAULT_USERNAME,
        password: String = DEFAULT_PASSWORD,
    ) : this(createParams(username, password))

    private constructor(params: Params) : this(
        salt = params.salt,
        publicKeyB = params.publicKeyB,
        identifier = params.identifier,
        verifier = params.verifier,
        privateExponent = params.privateExponent,
    )

    val publicKey: ByteArray
        get() = pad(publicKeyB)

    val serverPublicKey: ByteArray
        get() = publicKey

    fun verify(publicKeyA: ByteArray, clientProof: ByteArray): Srp6aResult {
        val a = toBigInt(publicKeyA)
        if (a.mod(n).signum() == 0) return Srp6aResult(false)

        val u = toBigInt(AirPlayCrypto.sha512(pad(a), pad(publicKeyB)))
        val s = (a * verifier.modPow(u, n)).mod(n).modPow(privateExponent, n)
        val sessionKey = AirPlayCrypto.sha512(toBytes(s))

        val hashN = AirPlayCrypto.sha512(toBytes(n))
        val hashG = AirPlayCrypto.sha512(toBytes(g))
        val hashXor = ByteArray(hashN.size)
        for (index in hashN.indices) {
            hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
        }
        val expected = AirPlayCrypto.sha512(
            hashXor,
            AirPlayCrypto.sha512(identifier),
            salt,
            pad(a),
            pad(publicKeyB),
            sessionKey,
        )
        if (!expected.contentEquals(clientProof)) return Srp6aResult(false)

        val serverProof = AirPlayCrypto.sha512(pad(a), clientProof, sessionKey)
        return Srp6aResult(true, sessionKey, serverProof)
    }

    private class Params(
        val salt: ByteArray,
        val publicKeyB: BigInteger,
        val identifier: ByteArray,
        val verifier: BigInteger,
        val privateExponent: BigInteger,
    )

    companion object {
        const val DEFAULT_USERNAME = "Pair-Setup"
        const val DEFAULT_PASSWORD = "3939"
        private const val SALT_BYTES = 16
        private const val PRIVATE_BYTES = 32
        private const val N_BYTES = 384
        private val COLON = byteArrayOf(':'.code.toByte())
        private val random = SecureRandom()

        /** RFC 5054 3072-bit prime. */
        private const val N_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
                "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
                "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
                "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
                "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
                "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
                "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
                "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
                "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
                "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

        private val n = BigInteger(N_HEX, 16)
        private val g = BigInteger.valueOf(5)
        private val multiplier = toBigInt(AirPlayCrypto.sha512(toBytes(n), pad(g)))

        fun start(username: String = DEFAULT_USERNAME, password: String = DEFAULT_PASSWORD): Srp6aServer =
            Srp6aServer(createParams(username, password))

        private fun createParams(username: String, password: String): Params {
            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val identifier = username.toByteArray(Charsets.UTF_8)
            val pass = password.toByteArray(Charsets.UTF_8)
            val x = toBigInt(AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512(identifier, COLON, pass)))
            val verifier = g.modPow(x, n)
            val privateExponent = toBigInt(ByteArray(PRIVATE_BYTES).also(random::nextBytes))
            val publicKeyB = (multiplier * verifier + g.modPow(privateExponent, n)).mod(n)
            return Params(salt, publicKeyB, identifier, verifier, privateExponent)
        }

        internal fun toBytes(value: BigInteger): ByteArray {
            if (value.signum() == 0) return byteArrayOf(0)
            val encoded = value.toByteArray()
            return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
        }

        internal fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

        internal fun pad(value: BigInteger): ByteArray {
            val bytes = toBytes(value)
            if (bytes.size >= N_BYTES) return bytes
            return ByteArray(N_BYTES).also { bytes.copyInto(it, N_BYTES - bytes.size) }
        }

        internal fun modulus(): BigInteger = n
        internal fun generator(): BigInteger = g
    }
}

typealias Srp6aSession = Srp6aServer

object Srp6a {
    fun start(
        username: String = Srp6aServer.DEFAULT_USERNAME,
        password: String = Srp6aServer.DEFAULT_PASSWORD,
    ): Srp6aSession = Srp6aServer.start(username, password)

    internal fun toBytes(value: BigInteger): ByteArray = Srp6aServer.toBytes(value)
    internal fun toBigInt(bytes: ByteArray): BigInteger = Srp6aServer.toBigInt(bytes)
    internal fun pad(value: BigInteger): ByteArray = Srp6aServer.pad(value)
    internal fun modulus(): BigInteger = Srp6aServer.modulus()
    internal fun generator(): BigInteger = Srp6aServer.generator()
}

/**
 * CarPlay pair-setup responder (accessory/server side).
 *
 * Uses unauthenticated SRP-6a with the fixed PIN "3939", then exchanges encrypted long-term
 * Ed25519 keys. The controller's key is persisted for later pair-verify. TLV8 over
 * "application/pairing+tlv8".
 */
class PairSetup(
    private val identity: AccessoryIdentity,
    private val pairings: PairingRegistry,
) {
    private var srp: Srp6aServer? = null
    private var sessionKey: ByteArray? = null
    var complete: Boolean = false
        private set

    fun handle(body: ByteArray): ByteArray {
        val tlv = Tlv8Codec.decode(body)
        val state = tlv[TlvTag.STATE]?.firstOrNull()?.toInt()?.and(0xff)
        return try {
            when (state) {
                1 -> m2()
                3 -> m4(tlv)
                5 -> m6(tlv)
                else -> err(state ?: 0)
            }
        } catch (_: Exception) {
            err(state ?: 0)
        }
    }

    private fun m2(): ByteArray {
        val session = Srp6aServer.start(SETUP_USERNAME, SETUP_CODE)
        srp = session
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TlvTag.STATE, byteArrayOf(2)),
                Tlv8Item(TlvTag.PUBLIC_KEY, session.publicKey),
                Tlv8Item(TlvTag.SALT, session.salt),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val publicKeyA = tlv[TlvTag.PUBLIC_KEY]
        val proof = tlv[TlvTag.PROOF]
        val session = srp
        if (session == null || publicKeyA == null || proof == null) return err(4)
        val result = session.verify(publicKeyA, proof)
        if (!result.ok || result.sessionKey == null || result.serverProof == null) return err(4)
        sessionKey = result.sessionKey
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TlvTag.STATE, byteArrayOf(4)),
                Tlv8Item(TlvTag.PROOF, result.serverProof),
            ),
        )
    }

    private fun m6(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[TlvTag.ENCRYPTED_DATA]
        val key = sessionKey
        if (key == null || encrypted == null) return err(6)

        val encryptionKey = AirPlayCrypto.hkdfSha512(key, ENCRYPT_SALT.asciiBytes(), ENCRYPT_INFO.asciiBytes())
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonce96(MSG05), encrypted))
        val controllerId = sub[TlvTag.IDENTIFIER]
        val controllerLtpk = sub[TlvTag.PUBLIC_KEY]
        val controllerSignature = sub[TlvTag.SIGNATURE]
        if (controllerId == null || controllerLtpk == null || controllerSignature == null) return err(6)

        val controllerSignKey = AirPlayCrypto.hkdfSha512(
            key,
            CONTROLLER_SIGN_SALT.asciiBytes(),
            CONTROLLER_SIGN_INFO.asciiBytes(),
        )
        val controllerSignData = concatBytes(controllerSignKey, controllerId, controllerLtpk)
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, controllerSignData, controllerSignature)) return err(6)
        pairings.save(controllerId.toString(Charsets.UTF_8), controllerLtpk)

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val accessorySignKey = AirPlayCrypto.hkdfSha512(
            key,
            ACCESSORY_SIGN_SALT.asciiBytes(),
            ACCESSORY_SIGN_INFO.asciiBytes(),
        )
        val accessorySignData = concatBytes(accessorySignKey, accessoryId, identity.publicKey)
        val accessorySignature = AirPlayCrypto.ed25519Sign(identity.privateKey, accessorySignData)
        val subResponse = Tlv8Codec.encode(
            listOf(
                Tlv8Item(TlvTag.IDENTIFIER, accessoryId),
                Tlv8Item(TlvTag.PUBLIC_KEY, identity.publicKey),
                Tlv8Item(TlvTag.SIGNATURE, accessorySignature),
            ),
        )
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonce96(MSG06), subResponse)
        complete = true
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TlvTag.STATE, byteArrayOf(6)),
                Tlv8Item(TlvTag.ENCRYPTED_DATA, sealed),
            ),
        )
    }

    private fun err(state: Int): ByteArray = Tlv8Codec.encode(
        listOf(
            Tlv8Item(TlvTag.STATE, byteArrayOf(state.toByte())),
            Tlv8Item(TlvTag.ERROR, byteArrayOf(ERROR_AUTHENTICATION.toByte())),
        ),
    )

    private companion object {
        const val SETUP_USERNAME = "Pair-Setup"
        const val SETUP_CODE = "3939"
        const val ERROR_AUTHENTICATION = 2
        const val ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
        const val ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
        const val CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
        const val CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
        const val ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
        const val ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"
        const val MSG05 = "PS-Msg05"
        const val MSG06 = "PS-Msg06"
    }
}

/**
 * CarPlay pair-verify responder (accessory/server side).
 *
 * Uses ephemeral X25519 plus Ed25519 signatures against the controller's stored long-term key,
 * then derives the ChaCha20-Poly1305 control-channel keys.
 */
class PairVerify(
    private val identity: AccessoryIdentity,
    private val pairings: PairingRegistry,
) {
    data class ControlKeys(val readKey: ByteArray, val writeKey: ByteArray)

    private var ephemeralPublicKey: ByteArray? = null
    private var clientEphemeralPublicKey: ByteArray? = null
    private var sharedSecret: ByteArray? = null
    private var encryptionKey: ByteArray? = null
    private var keys: ControlKeys? = null
    private var verified = false
    private var controllerId: String? = null

    val isVerified: Boolean get() = verified
    val verifiedControllerId: String? get() = controllerId
    val controlKeys: ControlKeys? get() = keys
    val shared: ByteArray? get() = sharedSecret

    fun handle(body: ByteArray): ByteArray {
        val tlv = Tlv8Codec.decode(body)
        val state = tlv[TlvTag.STATE]?.firstOrNull()?.toInt()?.and(0xff)
        return try {
            when (state) {
                1 -> m2(tlv)
                3 -> m4(tlv)
                else -> err(state ?: 0)
            }
        } catch (_: Exception) {
            err(state ?: 0)
        }
    }

    private fun m2(tlv: Map<Int, ByteArray>): ByteArray {
        val clientEphemeralPublicKey = tlv[TlvTag.PUBLIC_KEY] ?: return err(2)
        val pair = AirPlayCrypto.x25519Generate()
        ephemeralPublicKey = pair.publicKey
        this.clientEphemeralPublicKey = clientEphemeralPublicKey

        val shared = AirPlayCrypto.x25519SharedSecret(pair.privateKey, clientEphemeralPublicKey)
        sharedSecret = shared
        val encryptionKey = AirPlayCrypto.hkdfSha512(
            shared,
            VERIFY_ENCRYPT_SALT.asciiBytes(),
            VERIFY_ENCRYPT_INFO.asciiBytes(),
        )
        this.encryptionKey = encryptionKey

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature = AirPlayCrypto.ed25519Sign(
            identity.privateKey,
            concatBytes(pair.publicKey, accessoryId, clientEphemeralPublicKey),
        )
        val sub = Tlv8Codec.encode(
            listOf(
                Tlv8Item(TlvTag.IDENTIFIER, accessoryId),
                Tlv8Item(TlvTag.SIGNATURE, signature),
            ),
        )
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonce96(MSG02), sub)
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TlvTag.STATE, byteArrayOf(2)),
                Tlv8Item(TlvTag.PUBLIC_KEY, pair.publicKey),
                Tlv8Item(TlvTag.ENCRYPTED_DATA, sealed),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[TlvTag.ENCRYPTED_DATA]
        val encryptionKey = this.encryptionKey
        val shared = sharedSecret
        val ownEphemeralPublicKey = ephemeralPublicKey
        val clientEphemeralPublicKey = this.clientEphemeralPublicKey
        if (
            encryptionKey == null || shared == null || ownEphemeralPublicKey == null ||
            clientEphemeralPublicKey == null || encrypted == null
        ) {
            return err(4)
        }

        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonce96(MSG03), encrypted))
        val controllerId = sub[TlvTag.IDENTIFIER]
        val controllerSignature = sub[TlvTag.SIGNATURE]
        if (controllerId == null || controllerSignature == null) return err(4)

        val controllerLtpk = pairings.get(controllerId.toString(Charsets.UTF_8)) ?: return err(4)
        val signatureData = concatBytes(clientEphemeralPublicKey, controllerId, ownEphemeralPublicKey)
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, signatureData, controllerSignature)) return err(4)

        val readKey = AirPlayCrypto.hkdfSha512(shared, CONTROL_SALT.asciiBytes(), CONTROL_WRITE_KEY_INFO.asciiBytes())
        val writeKey = AirPlayCrypto.hkdfSha512(shared, CONTROL_SALT.asciiBytes(), CONTROL_READ_KEY_INFO.asciiBytes())
        keys = ControlKeys(readKey, writeKey)
        verified = true
        this.controllerId = controllerId.toString(Charsets.UTF_8)
        return Tlv8Codec.encode(listOf(Tlv8Item(TlvTag.STATE, byteArrayOf(4))))
    }

    private fun err(state: Int): ByteArray = Tlv8Codec.encode(
        listOf(
            Tlv8Item(TlvTag.STATE, byteArrayOf(state.toByte())),
            Tlv8Item(TlvTag.ERROR, byteArrayOf(ERROR_AUTHENTICATION.toByte())),
        ),
    )

    private companion object {
        const val ERROR_AUTHENTICATION = 2
        const val VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
        const val VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"
        const val CONTROL_SALT = "Control-Salt"
        const val CONTROL_READ_KEY_INFO = "Control-Read-Encryption-Key"
        const val CONTROL_WRITE_KEY_INFO = "Control-Write-Encryption-Key"
        const val MSG02 = "PV-Msg02"
        const val MSG03 = "PV-Msg03"
    }
}

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output.add(AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad))
            readCounter++
            offset = frameEnd
        }
        return Decrypted(concatBytes(*output.toTypedArray()), buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
