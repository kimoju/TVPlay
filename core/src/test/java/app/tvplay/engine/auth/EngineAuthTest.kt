package app.tvplay.engine.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAuthTest {

    @Test
    fun accessoryIdentityGeneratesValidEd25519KeyPair() {
        val identity = AccessoryIdentity.generate()
        assertEquals(32, identity.privateKey.size)
        assertEquals(32, identity.publicKey.size)
        assertEquals(64, identity.publicKeyHex.length)
        assertTrue(identity.pairingId.isNotBlank())
    }

    @Test
    fun pairingRegistrySavesAndDefensivelyCopiesPublicKeys() {
        var savedId: String? = null
        val registry = PairingRegistry { id, _ -> savedId = id }
        val key = ByteArray(32) { it.toByte() }
        registry.save("controller-1", key)
        key[0] = 99
        assertEquals("controller-1", savedId)
        val retrieved = registry.get("controller-1")
        assertNotNull(retrieved)
        assertEquals(0.toByte(), retrieved!![0])
        registry.clear()
        assertNull(registry.get("controller-1"))
    }

    @Test
    fun ed25519SignAndVerifyRoundTrip() {
        val pair = AirPlayCrypto.ed25519Generate()
        val message = "tvplay-homekit-test".asciiBytes()
        val signature = AirPlayCrypto.ed25519Sign(pair.privateKey, message)
        assertEquals(64, signature.size)
        assertTrue(AirPlayCrypto.ed25519Verify(pair.publicKey, message, signature))
        val tampered = message.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        assertFalse(AirPlayCrypto.ed25519Verify(pair.publicKey, tampered, signature))
    }

    @Test
    fun x25519KeyAgreementProducesMatchingSharedSecret() {
        val alice = AirPlayCrypto.x25519Generate()
        val bob = AirPlayCrypto.x25519Generate()
        val secretAlice = AirPlayCrypto.x25519SharedSecret(alice.privateKey, bob.publicKey)
        val secretBob = AirPlayCrypto.x25519SharedSecret(bob.privateKey, alice.publicKey)
        assertEquals(32, secretAlice.size)
        assertArrayEquals(secretAlice, secretBob)
    }

    @Test
    fun chacha20Poly1305EncryptAndDecryptRoundTrip() {
        val key = ByteArray(32) { (it * 3 + 1).toByte() }
        val nonce = AirPlayCrypto.nonce64(1L)
        val plaintext = "Hello TVPlay HomeKit Pairing".asciiBytes()
        val aad = "header".asciiBytes()
        val ciphertext = AirPlayCrypto.chachaSeal(key, nonce, plaintext, aad)
        assertEquals(plaintext.size + 16, ciphertext.size)
        val decrypted = AirPlayCrypto.chachaOpen(key, nonce, ciphertext, aad)
        assertArrayEquals(plaintext, decrypted)

        // Verify zero-copy slice decryption (chachaOpenInto) with non-zero offsets
        val wire = ByteArray(12 + ciphertext.size + 8)
        aad.copyInto(wire, 4, 0, minOf(aad.size, 8))
        ciphertext.copyInto(wire, 12)
        val output = ByteArray(12 + plaintext.size)
        val written = AirPlayCrypto.chachaOpenInto(
            key = key,
            nonce = nonce,
            ciphertextAndTag = wire,
            ciphertextOffset = 12,
            ciphertextLength = ciphertext.size,
            aad = wire,
            aadOffset = 4,
            aadLength = minOf(aad.size, 8),
            output = output,
            outputOffset = 12,
        )
        assertEquals(plaintext.size, written)
        assertArrayEquals(plaintext, output.copyOfRange(12, output.size))

        // Verify zero-copy slice encryption (chachaSealInto) with non-zero offsets
        val paddedPlaintext = ByteArray(6 + plaintext.size + 4)
        plaintext.copyInto(paddedPlaintext, 6)
        val sealedBuffer = ByteArray(10 + ciphertext.size + 4)
        aad.copyInto(sealedBuffer, 2, 0, minOf(aad.size, 8))
        val sealedBytes = AirPlayCrypto.chachaSealInto(
            key = key,
            nonce = nonce,
            plaintext = paddedPlaintext,
            plaintextOffset = 6,
            plaintextLength = plaintext.size,
            aad = sealedBuffer,
            aadOffset = 2,
            aadLength = minOf(aad.size, 8),
            output = sealedBuffer,
            outputOffset = 10,
        )
        assertEquals(ciphertext.size, sealedBytes)
        val openedFromSealedInto = ByteArray(plaintext.size)
        AirPlayCrypto.chachaOpenInto(
            key = key,
            nonce = nonce,
            ciphertextAndTag = sealedBuffer,
            ciphertextOffset = 10,
            ciphertextLength = ciphertext.size,
            aad = sealedBuffer,
            aadOffset = 2,
            aadLength = minOf(aad.size, 8),
            output = openedFromSealedInto,
            outputOffset = 0,
        )
        assertArrayEquals(plaintext, openedFromSealedInto)
    }

    @Test
    fun controlCipherSingleAllocationEncryptAndDecryptMultiFrameRoundTrip() {
        val writeKey = ByteArray(32) { (it * 5 + 3).toByte() }
        val readKey = ByteArray(32) { (it * 7 + 9).toByte() }
        val sender = ControlCipher(readKey = readKey, writeKey = writeKey)
        val receiver = ControlCipher(readKey = writeKey, writeKey = readKey)

        // Multi-frame payload (> 0x4000 bytes) spanning 2 AEAD frames
        val largePlaintext = ByteArray(18_500) { ((it * 13) and 0xff).toByte() }
        val encrypted = sender.encrypt(largePlaintext)
        assertEquals(2 * (2 + 16) + largePlaintext.size, encrypted.size)

        val decrypted = receiver.decrypt(encrypted)
        assertArrayEquals(largePlaintext, decrypted.data)
        assertEquals(0, decrypted.rest.size)
    }

    @Test
    fun tlv8CodecFragmentsAndReassemblesLargePayloads() {
        val largePayload = ByteArray(600) { (it and 0xff).toByte() }
        val encoded = Tlv8Codec.encode(
            0x06 to byteArrayOf(0x03),
            0x05 to largePayload,
        )
        val decoded = Tlv8Codec.decode(encoded)
        assertArrayEquals(byteArrayOf(0x03), decoded[0x06])
        assertArrayEquals(largePayload, decoded[0x05])
    }
}
