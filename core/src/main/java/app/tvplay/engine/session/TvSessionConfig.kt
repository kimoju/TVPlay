package app.tvplay.engine.session

import app.tvplay.engine.av.TvDisplaySpec
import java.net.Inet6Address
import java.net.InetAddress

typealias CarPlayTransport = TvTransportMode
typealias WirelessHotspotMode = TvWirelessMode
typealias AirPlayIcon = TvIconAsset
typealias AirPlayConfig = TvStreamServerConfig
typealias Iap2WirelessTransportConfig = TvWirelessIdentitySpec
typealias Iap2IdentificationConfig = TvAccessoryIdentitySpec
typealias CarPlayRuntimeConfig = TvSessionConfig

/**
 * Projection protocol mode supported by TVPlay.
 *
 * - [AUTO]: Concurrently runs Apple CarPlay + ICCOA CarLink / Android Auto / HiCar discovery and listeners.
 * - [APPLE_CARPLAY]: Apple CarPlay only (iPhone wireless & USB).
 * - [ICCOA_CARLINK]: ICCOA CarLink (Xiaomi HyperOS CarWith, vivo Jovi InCar, OPPO/OnePlus/Realme Car+).
 * - [ANDROID_AUTO]: Google Android Auto (AOA2 USB & Wireless GAL over TLS).
 * - [HUAWEI_HICAR]: Huawei HiCar (HarmonyOS BLE 0xFE35 + Wi-Fi P2P / USB).
 */
enum class TvProjectionProtocol(
    val key: String,
    val displayName: String,
    val shortName: String,
) {
    AUTO("auto", "双模并发 (CarPlay + 安卓 CarLink)", "双模并发"),
    APPLE_CARPLAY("carplay", "Apple CarPlay (iPhone 专属)", "CarPlay"),
    ICCOA_CARLINK("carlink", "ICCOA CarLink (小米 / vivo / OPPO)", "CarLink"),
    ANDROID_AUTO("android_auto", "Google Android Auto (AOA2 / 无线)", "Android Auto"),
    HUAWEI_HICAR("hicar", "华为 HiCar 智行 (HarmonyOS)", "HiCar");

    val enablesCarPlay: Boolean
        get() = this == AUTO || this == APPLE_CARPLAY

    val enablesCarLink: Boolean
        get() = this == AUTO || this == ICCOA_CARLINK

    val enablesAndroidAuto: Boolean
        get() = this == AUTO || this == ANDROID_AUTO

    val enablesHiCar: Boolean
        get() = this == AUTO || this == HUAWEI_HICAR

    val enablesAndroidProjection: Boolean
        get() = this != APPLE_CARPLAY

    companion object {
        fun fromKey(key: String?): TvProjectionProtocol =
            entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: AUTO
    }
}

enum class TvTransportMode {
    WIRED,
    WIRELESS,
}

enum class TvWirelessMode {
    WIFI_DIRECT,
    LAN_WIFI,
}

data class UsbDeviceFilter(
    val vendorId: Int,
    val productId: Int,
) {
    init {
        require(vendorId in 0..0xffff) { "vendorId must be in 0..0xffff" }
        require(productId in 0..0xffff) { "productId must be in 0..0xffff" }
    }
}

/** One OEM homescreen icon advertised to CarPlay. */
data class TvIconAsset(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable stream server configuration consumed by the RTSP control session. */
data class TvStreamServerConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: TvDisplaySpec,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    val microphoneOpus: Boolean = true,
    val manufacturer: String = "TVPlay",
    val model: String = "TVPlay",
    val oemLabel: String = "TVPlay",
    val icons: List<TvIconAsset> = emptyList(),
    val rightHandDrive: Boolean = false,
    val mainBufferedAudio: Boolean = false,
) {
    val bufferedAudioOutputEnabled: Boolean
        get() = mainBufferedAudio && !disableAudioOutput
}

/** Wireless Bluetooth + SSID identification advertised over the iAP2 Bluetooth channel. */
class TvWirelessIdentitySpec(
    val bluetoothMac: String,
    val ssid: String,
) {
    private val macBytes: ByteArray

    init {
        require(MAC_ADDRESS.matches(bluetoothMac)) {
            "bluetoothMac must be six colon-separated hexadecimal bytes"
        }
        require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }
        require('\u0000' !in ssid) { "Wireless SSID must not contain U+0000" }
        macBytes = ByteArray(6) { index ->
            bluetoothMac.substring(index * 3, index * 3 + 2).toInt(16).toByte()
        }
    }

    fun bluetoothMacBytes(): ByteArray = macBytes.copyOf()

    private companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** Accessory identification parameters exchanged during iAP2 Identification. */
data class TvAccessoryIdentitySpec(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    val carPlayUsbInterfaceNumber: Int = 0,
    val language: String = "en",
    val externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    val wireless: TvWirelessIdentitySpec? = null,
) {
    init {
        listOf(
            name,
            modelIdentifier,
            manufacturer,
            serialNumber,
            firmwareVersion,
            hardwareVersion,
            language,
            externalAccessoryProtocol,
        ).forEach { value ->
            require(value.isNotBlank()) { "Identification string must not be blank" }
            require('\u0000' !in value) { "Identification string must not contain U+0000" }
        }
        require(carPlayUsbInterfaceNumber in 0..0xff) {
            "carPlayUsbInterfaceNumber must be in 0..255"
        }
    }
}

/** Session engine runtime configuration for TVPlay. */
class TvSessionConfig(
    val iphoneDevices: List<UsbDeviceFilter> = emptyList(),
    val hostMac: ByteArray = DEFAULT_HOST_MAC,
    val linkLocal: String = "fe80::2",
    val identification: TvAccessoryIdentitySpec,
    val availableCurrentMilliAmps: Int = DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS,
    val label: String = "TVPlay",
    val hostName: String = "TVPlay",
    val transport: TvTransportMode = TvTransportMode.WIRELESS,
    val wirelessMode: TvWirelessMode = TvWirelessMode.WIFI_DIRECT,
    val wirelessBluetoothAddress: String? = null,
    val wifiDirectPreferredChannel: Int = 0,
    val lanWifiSsid: String = "",
    val lanWifiPassphrase: String = "",
    val lanNetworkInterface: String? = null,
    val projectionProtocol: TvProjectionProtocol = TvProjectionProtocol.AUTO,
    val carLinkPinCode: String = DEFAULT_CARLINK_PIN,
    val carLinkModelId: String = DEFAULT_CARLINK_MODEL_ID,
    val carLinkVendorData: String = DEFAULT_CARLINK_VENDOR_DATA,
    val carLinkCertBytes: ByteArray = ByteArray(0),
) {
    val wirelessHotspotMode: TvWirelessMode get() = wirelessMode
    val wirelessBluetoothDeviceAddress: String? get() = wirelessBluetoothAddress
    val wirelessP2pChannel: Int get() = wifiDirectPreferredChannel
    val wifiP2pPreferredChannel: Int get() = wifiDirectPreferredChannel
    val existingWifiSsid: String get() = lanWifiSsid
    val existingWifiPassphrase: String get() = lanWifiPassphrase
    val existingWifiInterface: String? get() = lanNetworkInterface

    init {
        require(iphoneDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        require(isLinkLocalIpv6(linkLocal)) { "linkLocal must be a link-local IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05ac
        const val DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS = 2400
        const val DEFAULT_CARLINK_PIN = "864209"
        const val DEFAULT_CARLINK_MODEL_ID = "00010001"
        const val DEFAULT_CARLINK_VENDOR_DATA = "0000"
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)

        fun validateLanCredentials(ssid: String, passphrase: String): String? {
            val trimmedSsid = ssid.trim()
            if (trimmedSsid.isEmpty()) return "SSID is required"
            if ('\u0000' in trimmedSsid || '\u0000' in passphrase) return "Null characters are not allowed"
            if (trimmedSsid.toByteArray(Charsets.UTF_8).size > 32) return "SSID exceeds 32 UTF-8 bytes"
            if (passphrase.isNotEmpty() && passphrase.length !in 8..63) {
                return "Passphrase must be empty or 8..63 characters"
            }
            return null
        }

        fun validateExistingWifiCredentials(ssid: String, passphrase: String): String? =
            validateLanCredentials(ssid, passphrase)

        private fun isLinkLocalIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                address is Inet6Address && address.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }
    }
}
