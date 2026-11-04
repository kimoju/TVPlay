package app.tvplay.config

import android.content.Context
import android.content.SharedPreferences
import app.tvplay.ui.theme.AppAppearance
import app.tvplay.engine.auth.AccessoryIdentity
import app.tvplay.engine.auth.LockdownPairRecord
import app.tvplay.engine.auth.PairingRegistry
import app.tvplay.engine.av.CarPlayPhysicalSizePreset
import app.tvplay.engine.av.DisplaySafeAreaRect
import app.tvplay.engine.av.DisplayScaleMath
import app.tvplay.engine.av.OpusEncoderSupport
import app.tvplay.engine.av.PhysicalSizeBasis
import app.tvplay.engine.session.TvTransportMode
import app.tvplay.engine.session.TvWirelessMode
import app.tvplay.remote.CarPlayNightMode
import java.io.File

typealias TvPlaySettingsRepository = TvSettingsRepository

/**
 * Structured, domain-oriented repository for TVPlay settings.
 *
 * Groups settings into [connection], [display], [audio], and [identity] facets backed by
 * [TvPreferencesStore].
 */
class TvSettingsRepository(private val context: Context) {
    val connection = ConnectionSettings(context)
    val display = DisplaySettings(context)
    val audio = AudioSettings(context)
    val identity = IdentitySettings(context)

    class ConnectionSettings(private val context: Context) {
        var transportMode: TvTransportMode
            get() = TvPreferencesStore.loadTransportMode(context)
            set(value) = TvPreferencesStore.saveTransportMode(context, value)

        var wirelessEnabled: Boolean
            get() = TvPreferencesStore.loadWirelessEnabled(context)
            set(value) = TvPreferencesStore.saveWirelessEnabled(context, value)

        var wirelessMode: TvWirelessMode
            get() = TvPreferencesStore.loadWirelessMode(context)
            set(value) = TvPreferencesStore.saveWirelessMode(context, value)

        var wirelessHotspotMode: TvWirelessMode
            get() = TvPreferencesStore.loadWirelessHotspotMode(context)
            set(value) = TvPreferencesStore.saveWirelessHotspotMode(context, value)

        var wifiDirectChannel: Int
            get() = TvPreferencesStore.loadWifiDirectChannel(context)
            set(value) = TvPreferencesStore.saveWifiDirectChannel(context, value)

        var wifiP2pPreferredChannel: Int
            get() = TvPreferencesStore.loadWifiP2pPreferredChannel(context)
            set(value) = TvPreferencesStore.saveWifiP2pPreferredChannel(context, value)

        var wifiDirectScanPause: Boolean
            get() = TvPreferencesStore.loadWifiDirectScanPause(context)
            set(value) = TvPreferencesStore.saveWifiDirectScanPause(context, value)

        var lanSsid: String
            get() = TvPreferencesStore.loadLanSsid(context)
            set(value) = TvPreferencesStore.saveLanCredentials(context, value, lanPassphrase)

        var existingWifiSsid: String
            get() = TvPreferencesStore.loadExistingWifiSsid(context)
            set(value) = TvPreferencesStore.saveExistingWifiCredentials(context, value, existingWifiPassphrase)

        var lanPassphrase: String
            get() = TvPreferencesStore.loadLanPassphrase(context)
            set(value) = TvPreferencesStore.saveLanCredentials(context, lanSsid, value)

        var existingWifiPassphrase: String
            get() = TvPreferencesStore.loadExistingWifiPassphrase(context)
            set(value) = TvPreferencesStore.saveExistingWifiCredentials(context, existingWifiSsid, value)

        fun saveLanCredentials(ssid: String, passphrase: String) {
            TvPreferencesStore.saveLanCredentials(context, ssid, passphrase)
        }

        fun saveExistingWifiCredentials(ssid: String, passphrase: String) {
            TvPreferencesStore.saveExistingWifiCredentials(context, ssid, passphrase)
        }

        var lanInterface: String
            get() = TvPreferencesStore.loadLanInterface(context)
            set(value) = TvPreferencesStore.saveLanInterface(context, value)

        var existingWifiInterface: String
            get() = TvPreferencesStore.loadExistingWifiInterface(context)
            set(value) = TvPreferencesStore.saveExistingWifiInterface(context, value)

        var defaultConnectionMode: DefaultConnectionMode
            get() = TvPreferencesStore.loadDefaultConnectionMode(context)
            set(value) = TvPreferencesStore.saveDefaultConnectionMode(context, value)

        var autoConnect: Boolean
            get() = TvPreferencesStore.loadAutoConnect(context)
            set(value) = TvPreferencesStore.saveAutoConnect(context, value)

        val autoConnectWireless: Boolean
            get() = TvPreferencesStore.loadAutoConnectWireless(context)

        var connectOnPhoneBluetooth: Boolean
            get() = TvPreferencesStore.loadConnectOnPhoneBluetooth(context)
            set(value) = TvPreferencesStore.saveConnectOnPhoneBluetooth(context, value)

        var autoConnectOnBluetooth: Boolean
            get() = TvPreferencesStore.loadAutoConnectOnBluetooth(context)
            set(value) = TvPreferencesStore.saveAutoConnectOnBluetooth(context, value)

        var autoStartOnBoot: Boolean
            get() = TvPreferencesStore.loadAutoStartOnBoot(context)
            set(value) = TvPreferencesStore.saveAutoStartOnBoot(context, value)

        var autoConnectOnBoot: Boolean
            get() = autoStartOnBoot
            set(value) {
                autoStartOnBoot = value
            }

        val pairedPhoneAddress: String?
            get() = TvPreferencesStore.loadPairedPhoneAddress(context)

        val pairedPhoneName: String
            get() = TvPreferencesStore.loadPairedPhoneName(context)

        fun savePairedPhone(address: String, name: String = "我的 iPhone") {
            TvPreferencesStore.savePairedPhone(context, address, name)
        }

        fun clearPairedPhone() {
            TvPreferencesStore.clearPairedPhone(context)
        }

        var externalController: Boolean
            get() = TvPreferencesStore.loadExternalController(context)
            set(value) = TvPreferencesStore.saveExternalController(context, value)
    }

    class DisplaySettings(private val context: Context) {
        var displayScaleTenths: Int
            get() = TvPreferencesStore.loadDisplayScaleTenths(context)
            set(value) = TvPreferencesStore.saveDisplayScaleTenths(context, value)

        var displayScalePercent: Int
            get() = TvPreferencesStore.loadDisplayScalePercent(context)
            set(value) {
                TvPreferencesStore.saveDisplayScalePercent(context, value)
                TvPreferencesStore.saveDisplayScaleTenths(
                    context,
                    DisplayScaleMath.sanitize((value + 5) / 10),
                )
            }

        var uiScalePercent: Int
            get() = TvPreferencesStore.loadUiScalePercent(context)
            set(value) = TvPreferencesStore.saveUiScalePercent(context, value)

        var uiDensityScalePercent: Int
            get() = TvPreferencesStore.loadUiDensityScalePercent(context)
            set(value) = TvPreferencesStore.saveUiDensityScalePercent(context, value)

        var uiDensityPercent: Int
            get() = uiScalePercent
            set(value) {
                uiScalePercent = value
            }

        var fps: Int
            get() = TvPreferencesStore.loadFps(context)
            set(value) = TvPreferencesStore.saveFps(context, value)

        var frameRate60Enabled: Boolean
            get() = fps >= 60
            set(value) {
                fps = if (value) 60 else 30
            }

        var hevcEnabled: Boolean
            get() = TvPreferencesStore.loadHevcEnabled(context)
            set(value) = TvPreferencesStore.saveHevcEnabled(context, value)

        var hevcSoftwareDecoderEnabled: Boolean
            get() = TvPreferencesStore.loadHevcSoftwareDecoderEnabled(context)
            set(value) = TvPreferencesStore.saveHevcSoftwareDecoderEnabled(context, value)

        var videoSurfaceType: VideoSurfaceType
            get() = TvPreferencesStore.loadVideoSurfaceType(context)
            set(value) = TvPreferencesStore.saveVideoSurfaceType(context, value)

        var videoSyncEnabled: Boolean
            get() = TvPreferencesStore.loadVideoSyncEnabled(context)
            set(value) = TvPreferencesStore.saveVideoSyncEnabled(context, value)

        var smoothVideo: Boolean
            get() = TvPreferencesStore.loadSmoothVideo(context)
            set(value) = TvPreferencesStore.saveSmoothVideo(context, value)

        var smoothVideoPlayback: Boolean
            get() = smoothVideo
            set(value) {
                smoothVideo = value
            }

        var directVideoOutput: Boolean
            get() = TvPreferencesStore.loadDirectVideoOutput(context)
            set(value) = TvPreferencesStore.saveDirectVideoOutput(context, value)

        var lowLatencyDecoder: Boolean
            get() = TvPreferencesStore.loadLowLatencyDecoder(context)
            set(value) = TvPreferencesStore.saveLowLatencyDecoder(context, value)

        var mtkDecoderTuning: Boolean
            get() = TvPreferencesStore.loadMtkDecoderTuning(context)
            set(value) = TvPreferencesStore.saveMtkDecoderTuning(context, value)

        var fpsCounterEnabled: Boolean
            get() = TvPreferencesStore.loadFpsCounter(context)
            set(value) = TvPreferencesStore.saveFpsCounter(context, value)

        var darkMode: DarkMode
            get() = TvPreferencesStore.loadDarkMode(context)
            set(value) = TvPreferencesStore.saveDarkMode(context, value)

        var carPlayNightMode: CarPlayNightMode
            get() = TvPreferencesStore.loadCarPlayNightMode(context)
            set(value) = TvPreferencesStore.saveCarPlayNightMode(context, value)

        var appAppearance: AppAppearance
            get() = TvPreferencesStore.loadAppAppearance(context)
            set(value) = TvPreferencesStore.saveAppAppearance(context, value)

        var waitingScreenColorPreset: WaitingScreenColorPreset
            get() = TvPreferencesStore.loadWaitingScreenColorPreset(context)
            set(value) = TvPreferencesStore.saveWaitingScreenColorPreset(context, value)

        fun loadWaitingScreenColors(night: Boolean = true): WaitingScreenColors =
            TvPreferencesStore.loadWaitingScreenColors(context, night)

        val picturePreferences: SharedPreferences
            get() = TvPreferencesStore.loadPicturePreferences(context)

        fun resetPictureAdjustments() {
            TvPreferencesStore.resetPictureAdjustments(context)
        }

        var rightHandDrive: Boolean
            get() = TvPreferencesStore.loadRightHandDrive(context)
            set(value) = TvPreferencesStore.saveRightHandDrive(context, value)

        var widthPhysicalMm: Int
            get() = TvPreferencesStore.loadWidthPhysicalMm(context)
            set(value) = TvPreferencesStore.saveWidthPhysicalMm(context, value)

        var physicalSizePreset: CarPlayPhysicalSizePreset
            get() = CarPlayPhysicalSizePreset.exact(widthPhysicalMm) ?: CarPlayPhysicalSizePreset.DEFAULT
            set(value) {
                widthPhysicalMm = value.widthMillimeters
            }

        var physicalSizeBasis: PhysicalSizeBasis
            get() = TvPreferencesStore.loadPhysicalSizeBasis(context)
            set(value) = TvPreferencesStore.savePhysicalSizeBasis(context, value)

        var maximumDetectedDisplay: Pair<Int, Int>
            get() = TvPreferencesStore.loadMaximumDetectedDisplay(context)
            set(value) = TvPreferencesStore.saveMaximumDetectedDisplay(context, value.first, value.second)

        var safeAreaDrawOutside: Boolean
            get() = TvPreferencesStore.loadSafeAreaDrawOutside(context)
            set(value) = TvPreferencesStore.saveSafeAreaDrawOutside(context, value)

        var mainDisplayDisableSafeArea: Boolean
            get() = TvPreferencesStore.loadMainDisplayDisableSafeArea(context)
            set(value) = TvPreferencesStore.saveMainDisplayDisableSafeArea(context, value)

        fun loadSafeAreaRect(widthPixels: Int, heightPixels: Int): DisplaySafeAreaRect? =
            TvPreferencesStore.loadSafeAreaRect(context, widthPixels, heightPixels)

        fun saveSafeAreaRect(
            activityWidthPixels: Int,
            activityHeightPixels: Int,
            rect: DisplaySafeAreaRect,
            commit: Boolean = false,
        ) {
            TvPreferencesStore.saveSafeAreaRect(
                context,
                activityWidthPixels,
                activityHeightPixels,
                rect,
                commit,
            )
        }

        fun clearSafeAreaRect(
            activityWidthPixels: Int,
            activityHeightPixels: Int,
            commit: Boolean = false,
        ) {
            TvPreferencesStore.clearSafeAreaRect(
                context,
                activityWidthPixels,
                activityHeightPixels,
                commit,
            )
        }
    }

    class AudioSettings(private val context: Context) {
        var audioFocusEnabled: Boolean
            get() = TvPreferencesStore.loadAudioFocusEnabled(context)
            set(value) = TvPreferencesStore.saveAudioFocusEnabled(context, value)

        var audioFocusAutoYield: Boolean
            get() = TvPreferencesStore.loadAudioFocusAutoYield(context)
            set(value) = TvPreferencesStore.saveAudioFocusAutoYield(context, value)

        var disableMediaKeyAudioFocus: Boolean
            get() = TvPreferencesStore.loadDisableMediaKeyAudioFocus(context)
            set(value) = TvPreferencesStore.saveDisableMediaKeyAudioFocus(context, value)

        var mediaKeyForwardingEnabled: Boolean
            get() = TvPreferencesStore.loadMediaKeyForwardingEnabled(context)
            set(value) = TvPreferencesStore.saveMediaKeyForwardingEnabled(context, value)

        var microphoneEnabled: Boolean
            get() = TvPreferencesStore.loadMicrophoneEnabled(context)
            set(value) = TvPreferencesStore.saveMicrophoneEnabled(context, value)

        fun loadMediaBufferMillis(wireless: Boolean): Int =
            TvPreferencesStore.loadMediaBufferMillis(context, wireless = wireless)

        fun saveMediaBufferMillis(wireless: Boolean, millis: Int) =
            TvPreferencesStore.saveMediaBufferMillis(context, wireless = wireless, millis = millis)

        var mediaBufferMillis: Int
            get() = loadMediaBufferMillis(TvPreferencesStore.loadWirelessEnabled(context))
            set(value) = saveMediaBufferMillis(TvPreferencesStore.loadWirelessEnabled(context), value)

        val opusMicrophoneAvailable: Boolean
            get() = OpusEncoderSupport.isAvailable()

        var mainBufferedAudio: Boolean
            get() = TvPreferencesStore.loadMainBufferedAudio(context)
            set(value) = TvPreferencesStore.saveMainBufferedAudio(context, value)

        var bufferedAudioEnabled: Boolean
            get() = TvPreferencesStore.loadBufferedAudioEnabled(context)
            set(value) = TvPreferencesStore.saveBufferedAudioEnabled(context, value)

        var disableAudioOutput: Boolean
            get() = TvPreferencesStore.loadDisableAudioOutput(context)
            set(value) = TvPreferencesStore.saveDisableAudioOutput(context, value)

        var carBluetoothAudio: Boolean
            get() = TvPreferencesStore.loadCarBluetoothAudio(context)
            set(value) = TvPreferencesStore.saveCarBluetoothAudio(context, value)
    }

    class IdentitySettings(private val context: Context) {
        var manufacturer: String
            get() = TvPreferencesStore.loadManufacturer(context)
            set(value) = TvPreferencesStore.saveManufacturer(context, value)

        var model: String
            get() = TvPreferencesStore.loadModel(context)
            set(value) = TvPreferencesStore.saveModel(context, value)

        var oemLabel: String
            get() = TvPreferencesStore.loadOemLabel(context)
            set(value) = TvPreferencesStore.saveOemLabel(context, value)

        var debugLogsEnabled: Boolean
            get() = TvPreferencesStore.loadDebugLogsEnabled(context)
            set(value) = TvPreferencesStore.saveDebugLogsEnabled(context, value)

        fun loadAccessoryIdentity(): AccessoryIdentity = TvPreferencesStore.loadIdentity(context)

        fun loadAirPlayIdentity(): AccessoryIdentity = TvPreferencesStore.loadIdentity(context)

        fun loadPairings(onSave: (String, ByteArray) -> Unit): PairingRegistry =
            TvPreferencesStore.loadPairings(context, onSave)

        fun savePairing(identifier: String, longTermPublicKey: ByteArray) =
            TvPreferencesStore.savePairing(context, identifier, longTermPublicKey)

        var lockdownRecord: LockdownPairRecord?
            get() = TvPreferencesStore.loadLockdownRecord(context)
            set(value) {
                if (value == null) TvPreferencesStore.clearLockdownRecord(context)
                else TvPreferencesStore.saveLockdownRecord(context, value)
            }

        val customAirPlayIconFile: File?
            get() = TvPreferencesStore.loadCustomAirPlayIconFile(context)

        fun saveCustomAirPlayIcon(encodedImage: ByteArray) =
            TvPreferencesStore.saveCustomAirPlayIcon(context, encodedImage)

        fun clearCustomAirPlayIcon() = TvPreferencesStore.clearCustomAirPlayIcon(context)
    }
}
