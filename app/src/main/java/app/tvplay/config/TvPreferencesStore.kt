package app.tvplay.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.ui.theme.TvColorScheme
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.auth.LockdownPairRecord
import app.tvplay.engine.auth.PairingRegistry
import app.tvplay.engine.av.CarPlayPhysicalSizePreset
import app.tvplay.engine.av.DisplayMetricMath
import app.tvplay.engine.av.DisplaySafeAreaRect
import app.tvplay.engine.av.DisplayScaleMath
import app.tvplay.engine.av.MediaAudioBuffer
import app.tvplay.engine.av.PhysicalSizeBasis
import app.tvplay.engine.av.SafeAreaStringCodec
import app.tvplay.engine.av.UiDensityScale
import app.tvplay.engine.network.WifiP2pChannels
import app.tvplay.engine.session.TvTransportMode
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import app.tvplay.remote.CarPlayPicture
import java.io.File

typealias AirPlayPersistence = TvPreferencesStore

/**
 * CarPlay dark/light mode preference.
 */
enum class DarkMode(val key: String) {
    FOLLOW_CAR("system"),
    ALWAYS_LIGHT("day"),
    ALWAYS_DARK("night");

    fun toCarPlayNightMode(): CarPlayNightMode = when (this) {
        FOLLOW_CAR -> CarPlayNightMode.SYSTEM
        ALWAYS_LIGHT -> CarPlayNightMode.DAY
        ALWAYS_DARK -> CarPlayNightMode.NIGHT
    }

    companion object {
        fun fromKey(key: String?): DarkMode = when (key?.lowercase()) {
            "day", "always_light", "light" -> ALWAYS_LIGHT
            "night", "always_dark", "dark" -> ALWAYS_DARK
            else -> FOLLOW_CAR
        }

        fun fromCarPlayNightMode(mode: CarPlayNightMode): DarkMode = when (mode) {
            CarPlayNightMode.SYSTEM -> FOLLOW_CAR
            CarPlayNightMode.DAY -> ALWAYS_LIGHT
            CarPlayNightMode.NIGHT -> ALWAYS_DARK
        }
    }
}

/**
 * Returns the explicit night state carried by [uiMode], or null when the device reports
 * UI_MODE_NIGHT_UNDEFINED so callers keep their previous state instead of flipping to light.
 */
fun nightModeOrNull(uiMode: Int): Boolean? =
    when (uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> true
        Configuration.UI_MODE_NIGHT_NO -> false
        else -> null
    }

/**
 * Color preset for the fullscreen waiting stage before CarPlay video starts.
 */
enum class WaitingScreenColorPreset(
    val key: String,
    val background: Int,
    val text: Int,
    val secondary: Int,
) {
    DARK("dark", 0xFF0C111B.toInt(), 0xFFF1F5FC.toInt(), 0xFFA8B6CA.toInt()),
    LIGHT("light", 0xFFE9EEF6.toInt(), 0xFF1C1C1E.toInt(), 0xFF5A6474.toInt()),
    OLED_BLACK("oled_black", 0xFF000000.toInt(), 0xFFF1F5FC.toInt(), 0xFF94A3B8.toInt()),
    MIDNIGHT_BLUE("midnight_blue", 0xFF0B132B.toInt(), 0xFFF1F5FC.toInt(), 0xFFA8B6CA.toInt());

    fun toColors(): WaitingScreenColors = WaitingScreenColors(background, text, secondary)

    companion object {
        val THEME_DEFAULT: WaitingScreenColorPreset get() = DARK
        val PURE_BLACK: WaitingScreenColorPreset get() = OLED_BLACK
        val CHARCOAL: WaitingScreenColorPreset get() = DARK

        fun fromKey(key: String?): WaitingScreenColorPreset =
            entries.firstOrNull {
                it.key.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true)
            } ?: DARK
    }
}

/**
 * The screen before CarPlay's video follows the active theme / preset so it does not glare at night.
 */
data class WaitingScreenColors(
    val background: Int,
    val text: Int,
    val secondary: Int,
    val helpText: Int = 0xFFFDE68A.toInt(),
    val helpBackground: Int = 0x3378350F,
    val helpBorder: Int = 0x80F59E0B.toInt(),
    val buttonFill: Int = 0xFF1E293B.toInt(),
    val buttonBorder: Int = 0xFF475569.toInt(),
    val bannerBackground: Int = 0xCC1E293B.toInt(),
    val bannerBorder: Int = 0xFF3B82F6.toInt(),
) {
    val primaryText: Int get() = text
    val secondaryText: Int get() = secondary

    companion object {
        fun of(night: Boolean): WaitingScreenColors = TvColorScheme.of(night).let {
            WaitingScreenColors(it.background, it.primaryText, it.secondaryText)
        }

        fun fromPreset(preset: WaitingScreenColorPreset): WaitingScreenColors = preset.toColors()
    }
}

/**
 * Preferred video rendering surface view type for TVPlay projection.
 */
enum class VideoSurfaceType(val key: String) {
    AUTO("auto"),
    SURFACE_VIEW("surface_view"),
    TEXTURE_VIEW("texture_view");

    companion object {
        fun fromKey(key: String?): VideoSurfaceType =
            entries.firstOrNull {
                it.key.equals(key, ignoreCase = true) || it.name.equals(key, ignoreCase = true)
            } ?: TEXTURE_VIEW
    }
}

typealias TvVideoSurfaceType = VideoSurfaceType

/**
 * Startup connection transport preference.
 */
enum class DefaultConnectionMode(val key: String) {
    LAST_USED("last_used"),
    WIRELESS("wireless"),
    USB("usb");

    fun wireless(lastUsedWireless: Boolean): Boolean = when (this) {
        LAST_USED -> lastUsedWireless
        WIRELESS -> true
        USB -> false
    }

    companion object {
        fun fromKey(key: String?): DefaultConnectionMode =
            entries.firstOrNull { it.key == key || it.name == key } ?: LAST_USED
    }
}

/**
 * TV-only SharedPreferences store for TVPlay settings, accessory identity, and paired controllers.
 */
object TvPreferencesStore {
    private const val PREFS = "xcertplay_airplay"
    private const val DIPLAY_PREFS = "diplay"

    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"

    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_WIRELESS_MODE = "wireless_hotspot_mode"
    private const val KEY_WIFI_P2P_PREFERRED_CHANNEL = "wifi_p2p_preferred_channel"
    const val KEY_WIFI_DIRECT_SCAN_PAUSE = "wifi_direct_scan_pause"
    private const val KEY_LAN_WIFI_SSID = "existing_wifi_ssid"
    private const val KEY_LAN_WIFI_PASSPHRASE = "existing_wifi_passphrase"
    private const val KEY_LAN_WIFI_INTERFACE = "existing_wifi_interface"

    private const val KEY_PHONE_ADDRESS = "phone_address"
    private const val KEY_PHONE_NAME = "phone_name"
    private const val KEY_DEFAULT_CONNECTION_MODE = "default_connection_mode"
    private const val KEY_AUTO_CONNECT = "auto_connect"
    private const val KEY_CONNECT_ON_PHONE_BLUETOOTH = "connect_on_phone_bluetooth"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_EXTERNAL_CONTROLLER = "external_controller"

    private const val KEY_FPS = "display_fps"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_DISPLAY_SCALE_PERCENT = "display_scale_percent"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA = "main_display_disable_safe_area"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"

    private const val KEY_VIDEO_SURFACE_TYPE = "video_surface_type"
    private const val KEY_DIRECT_VIDEO_OUTPUT = "direct_video_output"
    private const val KEY_SMOOTH_VIDEO = "smooth_video"
    private const val KEY_LOW_LATENCY_DECODER = "low_latency_decoder"
    private const val KEY_MTK_DECODER_TUNING = "mtk_decoder_tuning"
    private const val KEY_FPS_COUNTER = "fps_counter"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"

    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
    private const val KEY_AUDIO_FOCUS_AUTO_YIELD = "audio_focus_auto_yield"
    private const val KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS = "disable_media_key_audio_focus"
    private const val KEY_MEDIA_KEY_FORWARDING = "media_key_forwarding"
    private const val KEY_MICROPHONE_ENABLED = "microphone_enabled"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_WIRED_MEDIA_BUFFER_MS = "wired_media_buffer_ms"
    private const val KEY_MAIN_BUFFERED_AUDIO = "main_buffered_audio"
    private const val KEY_DISABLE_AUDIO_OUTPUT = "car_bluetooth_audio"

    private const val KEY_APP_APPEARANCE = "app_appearance"
    private const val KEY_CARPLAY_NIGHT_MODE = "carplay_night_mode"
    private const val KEY_WAITING_SCREEN_COLOR_PRESET = "waiting_screen_color_preset"

    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "TVPlay"
    const val DEFAULT_MODEL = "TVPlay"
    const val DEFAULT_OEM_LABEL = "TVPlay"

    /** Applied by the CarPlay host so overlay position/size updates without reconnecting. */
    @Volatile
    var overlaySettingsListener: (() -> Unit)? = null

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun diplayPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(DIPLAY_PREFS, Context.MODE_PRIVATE)

    // --- Transport & Wireless ---

    fun loadTransportMode(context: Context): TvTransportMode =
        if (loadWirelessEnabled(context)) TvTransportMode.WIRELESS else TvTransportMode.WIRED

    fun saveTransportMode(context: Context, mode: TvTransportMode) {
        saveWirelessEnabled(context, mode == TvTransportMode.WIRELESS)
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIRELESS_ENABLED, enabled).apply()
    }

    fun loadWirelessMode(context: Context): TvWirelessMode {
        val stored = prefs(context).getString(KEY_WIRELESS_MODE, null)
        return when (stored) {
            TvWirelessMode.LAN_WIFI.name, "EXISTING_WIFI" -> TvWirelessMode.LAN_WIFI
            TvWirelessMode.WIFI_DIRECT.name, "WIFI_P2P" -> TvWirelessMode.WIFI_DIRECT
            else -> TvWirelessMode.WIFI_DIRECT
        }
    }

    fun saveWirelessMode(context: Context, mode: TvWirelessMode) {
        prefs(context).edit().putString(KEY_WIRELESS_MODE, mode.name).apply()
    }

    fun loadWirelessHotspotMode(context: Context): TvWirelessMode = loadWirelessMode(context)

    fun saveWirelessHotspotMode(context: Context, mode: TvWirelessMode) = saveWirelessMode(context, mode)

    fun loadLanSsid(context: Context): String =
        prefs(context).getString(KEY_LAN_WIFI_SSID, "").orEmpty()

    fun loadExistingWifiSsid(context: Context): String = loadLanSsid(context)

    fun loadLanPassphrase(context: Context): String =
        prefs(context).getString(KEY_LAN_WIFI_PASSPHRASE, "").orEmpty()

    fun loadExistingWifiPassphrase(context: Context): String = loadLanPassphrase(context)

    fun saveLanCredentials(context: Context, ssid: String, passphrase: String) {
        prefs(context).edit()
            .putString(KEY_LAN_WIFI_SSID, ssid)
            .putString(KEY_LAN_WIFI_PASSPHRASE, passphrase)
            .apply()
    }

    fun saveExistingWifiCredentials(context: Context, ssid: String, passphrase: String) =
        saveLanCredentials(context, ssid, passphrase)

    fun loadLanInterface(context: Context): String =
        prefs(context).getString(KEY_LAN_WIFI_INTERFACE, null).orEmpty()

    fun loadExistingWifiInterface(context: Context): String = loadLanInterface(context)

    fun saveLanInterface(context: Context, interfaceName: String) {
        prefs(context).edit().putString(KEY_LAN_WIFI_INTERFACE, interfaceName.trim()).apply()
    }

    fun saveExistingWifiInterface(context: Context, interfaceName: String) =
        saveLanInterface(context, interfaceName)

    fun loadWifiDirectChannel(context: Context): Int = runCatching {
        prefs(context)
            .getInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, WifiP2pChannels.AUTO)
            .takeIf(WifiP2pChannels::isValid) ?: WifiP2pChannels.AUTO
    }.getOrDefault(WifiP2pChannels.AUTO)

    fun loadWifiP2pPreferredChannel(context: Context): Int = loadWifiDirectChannel(context)

    fun saveWifiDirectChannel(context: Context, channel: Int) {
        require(WifiP2pChannels.isValid(channel))
        prefs(context).edit().putInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, channel).apply()
    }

    fun saveWifiP2pPreferredChannel(context: Context, channel: Int) =
        saveWifiDirectChannel(context, channel)

    fun loadWifiDirectScanPause(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_DIRECT_SCAN_PAUSE, true)

    fun saveWifiDirectScanPause(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_DIRECT_SCAN_PAUSE, enabled).apply()
    }

    // --- Paired iPhone & Automation ---

    fun loadPairedPhoneAddress(context: Context): String? =
        diplayPrefs(context).getString(KEY_PHONE_ADDRESS, null)
            ?: prefs(context).getString(KEY_PHONE_ADDRESS, null)

    fun phoneAddress(context: Context): String? = loadPairedPhoneAddress(context)

    fun loadPairedPhoneName(context: Context): String =
        diplayPrefs(context).getString(KEY_PHONE_NAME, null)
            ?: prefs(context).getString(KEY_PHONE_NAME, null)
            ?: "我的 iPhone"

    fun phoneName(context: Context): String = loadPairedPhoneName(context)

    fun savePairedPhone(context: Context, address: String, name: String = "我的 iPhone") {
        diplayPrefs(context).edit()
            .putString(KEY_PHONE_ADDRESS, address)
            .putString(KEY_PHONE_NAME, name)
            .apply()
        prefs(context).edit()
            .putString(KEY_PHONE_ADDRESS, address)
            .putString(KEY_PHONE_NAME, name)
            .apply()
    }

    fun clearPairedPhone(context: Context) {
        diplayPrefs(context).edit()
            .remove(KEY_PHONE_ADDRESS)
            .remove(KEY_PHONE_NAME)
            .apply()
        prefs(context).edit()
            .remove(KEY_PHONE_ADDRESS)
            .remove(KEY_PHONE_NAME)
            .apply()
    }

    fun savePhone(context: Context, address: String, name: String) =
        savePairedPhone(context, address, name)

    fun loadDefaultConnectionMode(context: Context): DefaultConnectionMode =
        DefaultConnectionMode.fromKey(
            diplayPrefs(context).getString(KEY_DEFAULT_CONNECTION_MODE, null)
                ?: prefs(context).getString(KEY_DEFAULT_CONNECTION_MODE, null),
        )

    fun defaultConnectionMode(context: Context): DefaultConnectionMode =
        loadDefaultConnectionMode(context)

    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) {
        diplayPrefs(context).edit().putString(KEY_DEFAULT_CONNECTION_MODE, mode.key).apply()
        prefs(context).edit().putString(KEY_DEFAULT_CONNECTION_MODE, mode.key).apply()
    }

    fun loadAutoConnectWireless(context: Context): Boolean =
        loadDefaultConnectionMode(context).wireless(loadWirelessEnabled(context))

    fun autoConnectWireless(context: Context): Boolean = loadAutoConnectWireless(context)

    fun loadAutoConnect(context: Context): Boolean =
        if (diplayPrefs(context).contains(KEY_AUTO_CONNECT)) {
            diplayPrefs(context).getBoolean(KEY_AUTO_CONNECT, false)
        } else {
            prefs(context).getBoolean(KEY_AUTO_CONNECT, false)
        }

    fun autoConnect(context: Context): Boolean = loadAutoConnect(context)

    fun saveAutoConnect(context: Context, enabled: Boolean) {
        diplayPrefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        prefs(context).edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    fun loadConnectOnPhoneBluetooth(context: Context): Boolean =
        if (diplayPrefs(context).contains(KEY_CONNECT_ON_PHONE_BLUETOOTH)) {
            diplayPrefs(context).getBoolean(KEY_CONNECT_ON_PHONE_BLUETOOTH, false)
        } else {
            prefs(context).getBoolean(KEY_CONNECT_ON_PHONE_BLUETOOTH, false)
        }

    fun connectOnPhoneBluetooth(context: Context): Boolean = loadConnectOnPhoneBluetooth(context)

    fun loadAutoConnectOnBluetooth(context: Context): Boolean = loadConnectOnPhoneBluetooth(context)

    fun saveConnectOnPhoneBluetooth(context: Context, enabled: Boolean) {
        diplayPrefs(context).edit().putBoolean(KEY_CONNECT_ON_PHONE_BLUETOOTH, enabled).apply()
        prefs(context).edit().putBoolean(KEY_CONNECT_ON_PHONE_BLUETOOTH, enabled).apply()
    }

    fun saveAutoConnectOnBluetooth(context: Context, enabled: Boolean) =
        saveConnectOnPhoneBluetooth(context, enabled)

    fun loadAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    fun loadExternalController(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EXTERNAL_CONTROLLER, false)

    fun saveExternalController(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXTERNAL_CONTROLLER, enabled).apply()
    }

    // --- Display & Video ---

    fun loadFps(context: Context): Int = DisplayMetricMath.sanitizeFps(
        prefs(context).getInt(KEY_FPS, DisplayMetricMath.DEFAULT_FPS),
    )

    fun saveFps(context: Context, fps: Int) {
        prefs(context).edit().putInt(KEY_FPS, DisplayMetricMath.sanitizeFps(fps)).apply()
    }

    fun loadDisplayScaleTenths(context: Context): Int =
        DisplayScaleMath.sanitize(
            prefs(context).getInt(KEY_DISPLAY_SCALE_TENTHS, DisplayScaleMath.DEFAULT_TENTHS),
        )

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        prefs(context).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, DisplayScaleMath.sanitize(tenths))
            .apply()
    }

    fun loadDisplayScalePercent(context: Context): Int =
        prefs(context)
            .getInt(KEY_DISPLAY_SCALE_PERCENT, loadDisplayScaleTenths(context) * 10)
            .coerceIn(DisplayScaleMath.MIN_PERCENT, DisplayScaleMath.MAX_PERCENT)

    fun saveDisplayScalePercent(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(
                KEY_DISPLAY_SCALE_PERCENT,
                percent.coerceIn(DisplayScaleMath.MIN_PERCENT, DisplayScaleMath.MAX_PERCENT),
            )
            .apply()
    }

    fun loadUiScalePercent(context: Context): Int = UiDensityScale.sanitize(
        prefs(context).getInt(KEY_UI_SCALE_PERCENT, UiDensityScale.DEFAULT),
    )

    fun loadUiDensityScalePercent(context: Context): Int = loadUiScalePercent(context)

    fun saveUiScalePercent(context: Context, percent: Int) {
        prefs(context).edit()
            .putInt(KEY_UI_SCALE_PERCENT, UiDensityScale.sanitize(percent))
            .apply()
    }

    fun saveUiDensityScalePercent(context: Context, percent: Int) =
        saveUiScalePercent(context, percent)

    fun loadWidthPhysicalMm(context: Context): Int =
        DisplayMetricMath.sanitizeWidthPhysicalMm(
            prefs(context).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                CarPlayPhysicalSizePreset.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        prefs(context).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                DisplayMetricMath.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): PhysicalSizeBasis {
        val stored = prefs(context).getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return PhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: DisplayMetricMath.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: PhysicalSizeBasis) {
        prefs(context).edit().putString(KEY_PHYSICAL_SIZE_BASIS, basis.name).apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val p = prefs(context)
        return p.getInt(KEY_MAX_DETECTED_WIDTH, 0) to p.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        prefs(context).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        prefs(context).edit().putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive).apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside).apply()
    }

    fun loadMainDisplayDisableSafeArea(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA, false)

    fun saveMainDisplayDisableSafeArea(context: Context, disable: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAIN_DISPLAY_DISABLE_SAFE_AREA, disable).apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): DisplaySafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaStringCodec.decode(
            prefs(context).getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: DisplaySafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = prefs(context).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaStringCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = prefs(context).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadVideoSurfaceType(context: Context): VideoSurfaceType {
        val stored = prefs(context).getString(KEY_VIDEO_SURFACE_TYPE, null)
        if (stored != null) return VideoSurfaceType.fromKey(stored)
        return if (loadDirectVideoOutput(context) || loadSmoothVideo(context)) {
            VideoSurfaceType.SURFACE_VIEW
        } else {
            VideoSurfaceType.TEXTURE_VIEW
        }
    }

    fun saveVideoSurfaceType(context: Context, surfaceType: VideoSurfaceType) {
        prefs(context).edit()
            .putString(KEY_VIDEO_SURFACE_TYPE, surfaceType.key)
            .putBoolean(KEY_DIRECT_VIDEO_OUTPUT, surfaceType == VideoSurfaceType.SURFACE_VIEW)
            .apply()
    }

    fun loadDirectVideoOutput(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIRECT_VIDEO_OUTPUT, false)

    fun saveDirectVideoOutput(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DIRECT_VIDEO_OUTPUT, enabled)
            .putString(
                KEY_VIDEO_SURFACE_TYPE,
                if (enabled) VideoSurfaceType.SURFACE_VIEW.key else VideoSurfaceType.TEXTURE_VIEW.key,
            )
            .apply()
    }

    fun loadVideoSyncEnabled(context: Context): Boolean = loadSmoothVideo(context)

    fun saveVideoSyncEnabled(context: Context, enabled: Boolean) = saveSmoothVideo(context, enabled)

    fun loadSmoothVideo(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMOOTH_VIDEO, false)

    fun saveSmoothVideo(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMOOTH_VIDEO, enabled).apply()
    }

    fun loadLowLatencyDecoder(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOW_LATENCY_DECODER, false)

    fun saveLowLatencyDecoder(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOW_LATENCY_DECODER, enabled).apply()
    }

    fun loadMtkDecoderTuning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MTK_DECODER_TUNING, false)

    fun saveMtkDecoderTuning(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MTK_DECODER_TUNING, enabled).apply()
    }

    fun loadFpsCounter(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FPS_COUNTER, false)

    fun saveFpsCounter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FPS_COUNTER, enabled).apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEVC_ENABLED, false)

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEVC_ENABLED, enabled).apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled).apply()
    }

    // --- Audio, Microphone & Media Keys ---

    fun loadAudioFocusEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_FOCUS_ENABLED, false)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled).apply()
    }

    fun loadAudioFocusAutoYield(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, true)

    fun saveAudioFocusAutoYield(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, enabled).apply()
    }

    fun loadDisableMediaKeyAudioFocus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS, false)

    fun saveDisableMediaKeyAudioFocus(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLE_MEDIA_KEY_AUDIO_FOCUS, disabled).apply()
    }

    fun loadMediaKeyForwardingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEDIA_KEY_FORWARDING, true)

    fun saveMediaKeyForwardingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEDIA_KEY_FORWARDING, enabled).apply()
    }

    fun loadMicrophoneEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MICROPHONE_ENABLED, true)

    fun saveMicrophoneEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MICROPHONE_ENABLED, enabled).apply()
    }

    fun loadMediaBufferMillis(context: Context, wireless: Boolean): Int {
        val p = prefs(context)
        val shared = p.getInt(KEY_MEDIA_BUFFER_MS, MediaAudioBuffer.DEFAULT_MILLIS)
        return if (wireless) {
            MediaAudioBuffer.sanitizeWireless(shared)
        } else {
            MediaAudioBuffer.sanitizeWired(p.getInt(KEY_WIRED_MEDIA_BUFFER_MS, shared))
        }
    }

    fun saveMediaBufferMillis(context: Context, wireless: Boolean, millis: Int) {
        val p = prefs(context)
        if (wireless) {
            val wired = loadMediaBufferMillis(context, wireless = false)
            p.edit()
                .putInt(KEY_WIRED_MEDIA_BUFFER_MS, wired)
                .putInt(KEY_MEDIA_BUFFER_MS, MediaAudioBuffer.sanitizeWireless(millis))
                .apply()
        } else {
            p.edit()
                .putInt(KEY_WIRED_MEDIA_BUFFER_MS, MediaAudioBuffer.sanitizeWired(millis))
                .apply()
        }
    }

    fun loadMainBufferedAudio(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAIN_BUFFERED_AUDIO, false)

    fun loadBufferedAudioEnabled(context: Context): Boolean = loadMainBufferedAudio(context)

    fun saveMainBufferedAudio(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAIN_BUFFERED_AUDIO, enabled).apply()
    }

    fun saveBufferedAudioEnabled(context: Context, enabled: Boolean) =
        saveMainBufferedAudio(context, enabled)

    fun loadDisableAudioOutput(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_AUDIO_OUTPUT, false)

    fun saveDisableAudioOutput(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLE_AUDIO_OUTPUT, disabled).apply()
    }

    fun loadCarBluetoothAudio(context: Context): Boolean = loadDisableAudioOutput(context)

    fun saveCarBluetoothAudio(context: Context, enabled: Boolean) =
        saveDisableAudioOutput(context, enabled)

    // --- Appearance, Dark Mode, Picture Adjustments & Waiting Screen ---

    fun loadDarkMode(context: Context): DarkMode =
        DarkMode.fromKey(prefs(context).getString(KEY_CARPLAY_NIGHT_MODE, null))

    fun saveDarkMode(context: Context, mode: DarkMode) {
        prefs(context).edit().putString(KEY_CARPLAY_NIGHT_MODE, mode.key).apply()
    }

    fun loadCarPlayNightMode(context: Context): CarPlayNightMode = CarPlayNightMode.fromKey(
        prefs(context).getString(KEY_CARPLAY_NIGHT_MODE, null),
    )

    fun saveCarPlayNightMode(context: Context, mode: CarPlayNightMode) {
        prefs(context).edit().putString(KEY_CARPLAY_NIGHT_MODE, mode.key).apply()
    }

    fun loadAppAppearance(context: Context): AppAppearance = AppAppearance.fromKey(
        prefs(context).getString(KEY_APP_APPEARANCE, null),
    )

    fun saveAppAppearance(context: Context, appearance: AppAppearance) {
        prefs(context).edit().putString(KEY_APP_APPEARANCE, appearance.key).apply()
    }

    fun loadPicturePreferences(context: Context): SharedPreferences =
        CarPlayPicture.preferences(context)

    fun resetPictureAdjustments(context: Context) {
        CarPlayPicture.reset(CarPlayPicture.preferences(context))
    }

    fun loadWaitingScreenColorPreset(context: Context): WaitingScreenColorPreset =
        WaitingScreenColorPreset.fromKey(
            prefs(context).getString(KEY_WAITING_SCREEN_COLOR_PRESET, null),
        )

    fun saveWaitingScreenColorPreset(context: Context, preset: WaitingScreenColorPreset) {
        prefs(context).edit().putString(KEY_WAITING_SCREEN_COLOR_PRESET, preset.key).apply()
    }

    fun loadWaitingScreenColors(context: Context, night: Boolean = true): WaitingScreenColors {
        val raw = prefs(context).getString(KEY_WAITING_SCREEN_COLOR_PRESET, null)
        return if (raw != null) {
            WaitingScreenColorPreset.fromKey(raw).toColors()
        } else {
            WaitingScreenColors.of(night)
        }
    }

    // --- Identity, PairingRegistry & LockdownPairRecord ---

    fun loadDebugLogsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled).apply()
    }

    fun loadManufacturer(context: Context): String =
        prefs(context).getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        prefs(context).edit().putString(KEY_MANUFACTURER, manufacturer).apply()
    }

    fun loadModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model).apply()
    }

    fun loadOemLabel(context: Context): String =
        prefs(context).getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            .orEmpty()
            .ifBlank { DEFAULT_OEM_LABEL }

    fun saveOemLabel(context: Context, oemLabel: String) {
        prefs(context).edit().putString(KEY_OEM_LABEL, oemLabel).apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AccessoryIdentity {
        val p = prefs(context)
        val privateKey = p.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = p.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = p.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AccessoryIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AccessoryIdentity.generate().also { identity ->
            p.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingRegistry {
        val p = prefs(context)
        val store = PairingRegistry(onSave)
        for (identifier in p.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            p.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val p = prefs(context)
        val identifiers = p.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        p.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val p = prefs(context)
        val hostId = p.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = p.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = p.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = p.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = p.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = p.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = p.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = p.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = p.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        prefs(context).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        prefs(context).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
