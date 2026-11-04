package app.tvplay.engine.auth

import java.util.UUID

/** Long-term Ed25519 identity of the TVPlay accessory, persisted across sessions. */
data class AccessoryIdentity(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val pairingId: String,
) {
    val publicKeyHex: String
        get() = publicKey.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        fun generate(): AccessoryIdentity {
            val keyPair = AirPlayCrypto.ed25519Generate()
            return AccessoryIdentity(keyPair.privateKey, keyPair.publicKey, UUID.randomUUID().toString())
        }
    }
}

/** Registry of paired iPhone controllers keyed by their long-term Ed25519 identifier. */
class PairingRegistry(private val onSave: ((String, ByteArray) -> Unit)? = null) {
    private val entries = HashMap<String, ByteArray>()

    @Synchronized
    fun save(identifier: String, longTermPublicKey: ByteArray) {
        val copy = longTermPublicKey.copyOf()
        entries[identifier] = copy
        onSave?.invoke(identifier, copy)
    }

    @Synchronized
    fun get(identifier: String): ByteArray? = entries[identifier]?.copyOf()

    @Synchronized
    fun clear() = entries.clear()
}
