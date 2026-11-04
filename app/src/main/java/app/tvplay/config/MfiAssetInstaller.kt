package app.tvplay.config

import android.content.Context
import app.tvplay.R
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.auth.OfflineMfiAuthProvider
import app.tvplay.engine.av.OpusEncoderSupport
import app.tvplay.engine.av.TvDisplaySpec
import app.tvplay.engine.session.TvAccessoryIdentitySpec
import app.tvplay.engine.session.TvIconAsset
import app.tvplay.engine.session.TvSessionConfig
import app.tvplay.engine.session.TvStreamServerConfig
import app.tvplay.engine.session.TvTransportMode
import app.tvplay.engine.session.TvWirelessIdentitySpec
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.engine.session.UsbDeviceFilter
import app.tvplay.ui.theme.AirPlayIconSizing
import java.io.File
import java.security.MessageDigest

typealias TvPlayBootstrap = MfiAssetInstaller

/**
 * Installs offline MFi authentication assets (`offline-mfi/{identity.pk8,certificate.p7b}`) from
 * the APK into `filesDir/offline-mfi` and builds [TvSessionConfig] and [TvStreamServerConfig].
 */
object MfiAssetInstaller {
    const val DEFAULT_SOURCE_VERSION = "950.7.1"
    const val DEFAULT_STREAM_PORT = 7000
    const val DEFAULT_ENTERTAINMENT_SAMPLE_RATE = 48_000
    private const val STAGING_DIRECTORY = "${OfflineMfiAuthProvider.DIRECTORY}-staging"
    private val ASSET_FILES = listOf("identity.pk8", "certificate.p7b")

    @Volatile
    private var ready = false

    /** Directory under [Context.getFilesDir] where offline MFi assets are installed. */
    fun directory(context: Context): File =
        File(context.filesDir, OfflineMfiAuthProvider.DIRECTORY)

    /**
     * Ensures `offline-mfi/{identity.pk8,certificate.p7b}` are installed under `filesDir/offline-mfi`
     * and verified with [OfflineMfiAuthProvider.load].
     */
    @Synchronized
    fun ensure(context: Context) {
        if (ready && directory(context).isDirectory) return
        installAndVerify(context) { }
        ready = true
    }

    /**
     * Ensures the offline MFi assets are installed and returns a loaded [OfflineMfiAuthProvider].
     */
    @Synchronized
    fun install(
        context: Context,
        onSignature: (Int) -> Unit = {},
    ): OfflineMfiAuthProvider {
        val provider = installAndVerify(context, onSignature)
        ready = true
        return provider
    }

    /**
     * Loads the [OfflineMfiAuthProvider] from `filesDir/offline-mfi`, installing from APK assets first
     * if not yet extracted.
     */
    fun loadProvider(
        context: Context,
        onSignature: (Int) -> Unit = {},
    ): OfflineMfiAuthProvider = install(context, onSignature)

    private fun installAndVerify(
        context: Context,
        onSignature: (Int) -> Unit,
    ): OfflineMfiAuthProvider {
        val target = directory(context)
        if (!target.exists()) {
            val staging = File(context.filesDir, STAGING_DIRECTORY)
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare offline MFi staging directory" }
            staging.setReadable(false, false)
            staging.setReadable(true, true)
            staging.setExecutable(false, false)
            staging.setExecutable(true, true)
            try {
                for (name in ASSET_FILES) {
                    val file = File(staging, name)
                    context.assets.open("${OfflineMfiAuthProvider.DIRECTORY}/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false)
                    file.setReadable(true, true)
                    file.setWritable(false, false)
                    file.setWritable(true, true)
                }
                OfflineMfiAuthProvider.load(staging)
                check(staging.renameTo(target)) { "Could not install offline MFi directory" }
            } finally {
                staging.deleteRecursively()
            }
            TvPreferencesStore.saveDebugLogsEnabled(context, false)
        }
        return OfflineMfiAuthProvider.load(target, onSignature)
    }

    /**
     * Derives the 6-byte locally administered MAC string (`XX:XX:XX:XX:XX:XX`) from [identity].
     */
    fun deviceId(identity: AccessoryIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(identity.publicKey)
            .take(6)
            .toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }

    /**
     * Derives the 6-byte locally administered host MAC address from [identity].
     */
    fun hostMacBytes(identity: AccessoryIdentity): ByteArray =
        deviceId(identity).split(":").map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Builds the [TvSessionConfig] from persisted [TvPreferencesStore] settings and optional overrides.
     */
    fun buildSessionConfig(
        context: Context,
        identity: AccessoryIdentity = TvPreferencesStore.loadIdentity(context),
        iphoneDevices: List<UsbDeviceFilter> = emptyList(),
        manufacturer: String = TvPreferencesStore.loadManufacturer(context),
        model: String = TvPreferencesStore.loadModel(context),
        transport: TvTransportMode = TvPreferencesStore.loadTransportMode(context),
        wirelessMode: TvWirelessMode = TvPreferencesStore.loadWirelessMode(context),
        wirelessBluetoothAddress: String? = TvPreferencesStore.loadPairedPhoneAddress(context),
        wifiDirectPreferredChannel: Int = TvPreferencesStore.loadWifiDirectChannel(context),
        lanWifiSsid: String = TvPreferencesStore.loadLanSsid(context),
        lanWifiPassphrase: String = TvPreferencesStore.loadLanPassphrase(context),
        lanNetworkInterface: String? = TvPreferencesStore.loadLanInterface(context)
            .trim()
            .takeIf { it.isNotEmpty() },
        carPlayUsbInterfaceNumber: Int = 3,
        wirelessIdentity: TvWirelessIdentitySpec? = null,
    ): TvSessionConfig {
        val deviceIdHex = deviceId(identity)
        val compactId = deviceIdHex.replace(":", "")
        val resolvedManufacturer = manufacturer.trim().ifBlank { TvPreferencesStore.DEFAULT_MANUFACTURER }
        val resolvedModel = model.trim().ifBlank { TvPreferencesStore.DEFAULT_MODEL }
        val effectiveLanSsid = lanWifiSsid.trim()
        return TvSessionConfig(
            iphoneDevices = iphoneDevices,
            hostMac = hostMacBytes(identity),
            identification = TvAccessoryIdentitySpec(
                name = "TVPlay",
                modelIdentifier = resolvedModel,
                manufacturer = resolvedManufacturer,
                serialNumber = "TVPLAY-$compactId",
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = carPlayUsbInterfaceNumber,
                wireless = wirelessIdentity,
            ),
            label = "TVPlay",
            hostName = "tvplay-${compactId.lowercase()}",
            transport = transport,
            wirelessMode = wirelessMode,
            wirelessBluetoothAddress = wirelessBluetoothAddress,
            wifiDirectPreferredChannel = wifiDirectPreferredChannel,
            lanWifiSsid = effectiveLanSsid,
            lanWifiPassphrase = lanWifiPassphrase,
            lanNetworkInterface = lanNetworkInterface,
        )
    }

    /**
     * Builds the [TvStreamServerConfig] consumed by the RTSP control session.
     */
    fun buildStreamServerConfig(
        context: Context,
        main: TvDisplaySpec,
        identity: AccessoryIdentity = TvPreferencesStore.loadIdentity(context),
        deviceName: String = "TVPlay",
        btMac: String = TvPlayBluetooth.localAddress(context) ?: deviceId(identity),
        sourceVersion: String = DEFAULT_SOURCE_VERSION,
        port: Int = DEFAULT_STREAM_PORT,
        entertainmentSampleRate: Int = DEFAULT_ENTERTAINMENT_SAMPLE_RATE,
        hevc: Boolean = TvPreferencesStore.loadHevcEnabled(context),
        disableAudioOutput: Boolean = TvPreferencesStore.loadDisableAudioOutput(context),
        microphone: Boolean = TvPreferencesStore.loadMicrophoneEnabled(context),
        microphoneOpus: Boolean = OpusEncoderSupport.isAvailable(),
        manufacturer: String = TvPreferencesStore.loadManufacturer(context),
        model: String = TvPreferencesStore.loadModel(context),
        oemLabel: String = TvPreferencesStore.loadOemLabel(context),
        icons: List<TvIconAsset> = loadDefaultIcons(context),
        mainBufferedAudio: Boolean = TvPreferencesStore.loadMainBufferedAudio(context),
        rightHandDrive: Boolean = TvPreferencesStore.loadRightHandDrive(context),
    ): TvStreamServerConfig {
        val resolvedManufacturer = manufacturer.trim().ifBlank { TvPreferencesStore.DEFAULT_MANUFACTURER }
        val resolvedModel = model.trim().ifBlank { TvPreferencesStore.DEFAULT_MODEL }
        val resolvedOemLabel = oemLabel.trim().ifBlank { TvPreferencesStore.DEFAULT_OEM_LABEL }
        val resolvedIcons = icons.ifEmpty { loadDefaultIcons(context) }
        return TvStreamServerConfig(
            deviceName = deviceName,
            deviceId = deviceId(identity),
            btMac = btMac,
            sourceVersion = sourceVersion,
            main = main,
            port = port,
            entertainmentSampleRate = entertainmentSampleRate,
            hevc = hevc,
            disableAudioOutput = disableAudioOutput,
            microphone = microphone,
            microphoneOpus = microphoneOpus,
            manufacturer = resolvedManufacturer,
            model = resolvedModel,
            oemLabel = resolvedOemLabel,
            icons = resolvedIcons,
            mainBufferedAudio = mainBufferedAudio,
            rightHandDrive = rightHandDrive,
        )
    }

    /**
     * Loads the CarPlay home-button icon (custom user icon if valid, otherwise packaged `R.raw.ic_car_home`).
     */
    fun loadDefaultIcons(context: Context): List<TvIconAsset> {
        val customBytes = runCatching {
            TvPreferencesStore.loadCustomAirPlayIconFile(context)?.readBytes()
        }.getOrNull()
        if (customBytes != null) {
            decodeIconAsset(customBytes)?.let { return listOf(it) }
            runCatching { TvPreferencesStore.clearCustomAirPlayIcon(context) }
        }
        val rawBytes = runCatching {
            context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        }.getOrNull() ?: return emptyList()
        val decoded = decodeIconAsset(rawBytes) ?: TvIconAsset(120, 120, rawBytes)
        return listOf(decoded)
    }

    private fun decodeIconAsset(encoded: ByteArray): TvIconAsset? {
        val (size, bytes) = runCatching { AirPlayIconSizing.fit(encoded) }.getOrNull() ?: return null
        return TvIconAsset(size, size, bytes)
    }
}

/**
 * Compatibility facade delegating phone & connection mode preferences to [TvPreferencesStore].
 */
object TvPlayPreferences {
    fun phoneAddress(context: Context): String? = TvPreferencesStore.loadPairedPhoneAddress(context)
    fun phoneName(context: Context): String = TvPreferencesStore.loadPairedPhoneName(context)
    fun savePhone(context: Context, address: String, name: String) =
        TvPreferencesStore.savePairedPhone(context, address, name)

    fun defaultConnectionMode(context: Context): DefaultConnectionMode =
        TvPreferencesStore.loadDefaultConnectionMode(context)

    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) =
        TvPreferencesStore.saveDefaultConnectionMode(context, mode)

    fun autoConnectWireless(context: Context): Boolean =
        TvPreferencesStore.loadAutoConnectWireless(context)

    fun autoConnect(context: Context): Boolean = TvPreferencesStore.loadAutoConnect(context)
    fun saveAutoConnect(context: Context, value: Boolean) =
        TvPreferencesStore.saveAutoConnect(context, value)

    fun connectOnPhoneBluetooth(context: Context): Boolean =
        TvPreferencesStore.loadConnectOnPhoneBluetooth(context)

    fun saveConnectOnPhoneBluetooth(context: Context, value: Boolean) =
        TvPreferencesStore.saveConnectOnPhoneBluetooth(context, value)
}
