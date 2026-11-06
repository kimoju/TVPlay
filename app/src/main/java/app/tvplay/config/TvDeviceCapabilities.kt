package app.tvplay.config

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import app.tvplay.R
import app.tvplay.engine.session.TvWirelessMode

/**
 * Consolidated device capability detection and runtime permission helpers for Android TV,
 * TV boxes, projectors, and large-screen displays.
 */
object TvDeviceCapabilities {
    fun isTelevision(context: Context): Boolean = AndroidTvInputMode.isTelevision(context)

    fun shouldUseKnobAsPrimaryInput(context: Context): Boolean =
        AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context)

    fun isHevcUsable(
        requested: Boolean,
        softwareChosen: Boolean = false,
        decoders: List<HevcSupport.Decoder> = HevcSupport.decoders(),
    ): Boolean = HevcSupport.usable(requested, softwareChosen, decoders)

    fun localBluetoothAddress(context: Context): String? = TvPlayBluetooth.localAddress(context)

    fun localBluetoothName(context: Context): String? = TvPlayBluetooth.localName(context)

    fun requiredWiredPermissions(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        WiredPermissions.required(sdkInt)

    fun requiredWirelessPermissions(
        wirelessMode: TvWirelessMode,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> = WirelessPermissions.required(wirelessMode, sdkInt)
}

/**
 * HEVC is worth asking for only when a hardware decoder plays it. Without one, Android picks its own
 * software decoder, which a low-end device cannot keep up with while music plays (#727).
 */
object HevcSupport {
    data class Decoder(val name: String, val hardware: Boolean)

    /** The user's software-HEVC choice keeps HEVC whenever any decoder exists. */
    fun usable(
        requested: Boolean,
        softwareChosen: Boolean = false,
        decoders: List<Decoder> = decoders(),
    ): Boolean =
        requested && (decoders.any { it.hardware } || softwareChosen && decoders.isNotEmpty())

    fun decoders(): List<Decoder> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }.map { info ->
            Decoder(
                info.name,
                if (Build.VERSION.SDK_INT >= 29) {
                    info.isHardwareAccelerated
                } else {
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                },
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Capability-based detection for devices whose primary interaction model is a TV remote/D-pad,
 * plus explicit TVPlay product mode configuration.
 *
 * No manufacturer/model checks are used. Android TV feature flags and UI mode are preferred,
 * with a no-touchscreen fallback for other non-touch displays.
 */
object AndroidTvInputMode {
    @Volatile
    var forceTvPlayModeForTest: Boolean? = null

    fun isTvPlayConfigEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean =
        forceTvPlayModeForTest ?: true

    fun isTelevision(context: Context): Boolean {
        val packageManager = context.packageManager
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) return true

        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true

        return (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun shouldUseKnobAsPrimaryInput(context: Context): Boolean {
        if (isTvPlayConfigEnabled(context)) return true
        if (isTelevision(context)) return true

        return context.resources.configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH
    }

    fun isTvPlayMode(context: Context): Boolean = shouldUseKnobAsPrimaryInput(context)
}

/**
 * Local Bluetooth adapter address and display name lookup for TVPlay connection guides and AirPlay /info.
 */
object TvPlayBluetooth {
    private const val IAP2_UUID = "00000000-deca-fade-deca-deafdecacafe"

    data class PairedDevice(
        val name: String,
        val address: String,
        val isLikelyIphone: Boolean = false,
        val isAccessory: Boolean = false,
    )

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun bondedDevices(context: Context): List<PairedDevice> = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter()
        adapter?.bondedDevices.orEmpty().mapNotNull { device ->
            val address = runCatching { device.address }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawName = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            val name = rawName ?: "蓝牙设备"
            val majorClass = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
            val hasIap2Uuid = runCatching {
                device.uuids?.any { it.uuid.toString().equals(IAP2_UUID, ignoreCase = true) } == true
            }.getOrDefault(false)
            val nameHasIphone = name.contains("iPhone", ignoreCase = true)
            val isAccessory = !nameHasIphone && !hasIap2Uuid &&
                app.tvplay.engine.session.isNonPhoneBluetoothAccessory(rawName, majorClass)
            val isLikelyIphone = !isAccessory && (
                nameHasIphone ||
                    hasIap2Uuid ||
                    majorClass == android.bluetooth.BluetoothClass.Device.Major.PHONE
                )
            PairedDevice(
                name = name,
                address = address,
                isLikelyIphone = isLikelyIphone,
                isAccessory = isAccessory,
            )
        }.sortedWith(
            compareBy<PairedDevice> { !it.isLikelyIphone }
                .thenBy { it.isAccessory }
                .thenBy { it.name.lowercase() },
        )
    }.getOrDefault(emptyList())

    /**
     * Clears any saved target phone address if it is no longer paired or if it points to a
     * non-phone TV peripheral (such as a Bluetooth TV remote control).
     */
    fun sanitizeSavedPhone(context: Context, bonded: List<PairedDevice> = bondedDevices(context)) {
        val savedAddress = TvPreferencesStore.loadPairedPhoneAddress(context) ?: return
        val savedName = TvPreferencesStore.loadPairedPhoneName(context)
        if (app.tvplay.engine.session.isNonPhoneBluetoothAccessory(savedName, null)) {
            TvPreferencesStore.clearPairedPhone(context)
            return
        }
        if (bonded.isEmpty()) return
        val match = bonded.firstOrNull { it.address.equals(savedAddress, ignoreCase = true) }
        if (match == null || (match.isAccessory && !match.isLikelyIphone)) {
            TvPreferencesStore.clearPairedPhone(context)
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    @Suppress("DEPRECATION")
    fun localAddress(context: Context): String? {
        val adapter = runCatching {
            (context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter())?.address
        }.getOrNull()
        val setting = runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun localName(context: Context): String? {
        val adapterName = runCatching {
            (context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter())?.name
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val globalName = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return adapterName ?: globalName ?: Build.MODEL
    }
}

/**
 * Runtime permissions a wired CarPlay start needs per Android release. On Android 17 the iPhone's
 * AirPlay connection over the USB network is local traffic too: on emteria's Android 17 for
 * Raspberry Pi 5 the iPhone's SYN from its link-local address to `[fe80::2]:7000` arrived and was
 * never answered until [Manifest.permission.ACCESS_LOCAL_NETWORK] was granted, so link-local IPv6
 * cannot be relied on to stay exempt (see [WirelessPermissions]).
 */
object WiredPermissions {
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            emptyList()
        }

    fun missing(context: Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
        required(sdkInt).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}

/**
 * Runtime permissions a wireless CarPlay start needs per Android release. Android 17 requires
 * [Manifest.permission.ACCESS_LOCAL_NETWORK] for local traffic, including the inbound AirPlay
 * listener.
 */
object WirelessPermissions {
    fun required(
        hotspotMode: TvWirelessMode,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> = when {
        hotspotMode == TvWirelessMode.LAN_WIFI ->
            if (sdkInt >= Build.VERSION_CODES.S) {
                listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            } else {
                listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            }
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        sdkInt >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }.let { permissions ->
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            permissions + Manifest.permission.ACCESS_LOCAL_NETWORK
        } else {
            permissions
        }
    }

    fun missing(
        context: Context,
        wirelessMode: TvWirelessMode,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> = required(wirelessMode, sdkInt).filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * What the CarPlay screen asks for once the wireless start permissions were refused (#290): Location,
     * or Nearby devices for Bluetooth and Nearby Wi-Fi devices. Anything else, such as Android 17's local
     * network access, is not under Nearby devices, so the screen only says to check the app permissions.
     */
    @StringRes
    fun refusalMessage(missing: Collection<String>): Int = when {
        Manifest.permission.ACCESS_FINE_LOCATION in missing ||
            Manifest.permission.ACCESS_COARSE_LOCATION in missing ->
            R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p
        Manifest.permission.BLUETOOTH_CONNECT in missing ||
            Manifest.permission.BLUETOOTH_ADVERTISE in missing ||
            Manifest.permission.NEARBY_WIFI_DEVICES in missing ->
            R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per
        else -> R.string.could_not_start_carplay_return_to_diplay_and_check_app_per
    }
}
