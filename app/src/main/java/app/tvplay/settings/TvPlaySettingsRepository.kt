package app.tvplay.settings

import android.content.Context
import app.tvplay.AirPlayPersistence
import app.tvplay.AppAppearance
import app.tvplay.CarPlayDock
import app.tvplay.CarPlayNightMode
import app.tvplay.CarPlayNightSchedule
import app.tvplay.DefaultConnectionMode
import app.tvplay.TvPlayPreferences
import app.tvplay.airplay.AirPlayIdentity
import app.tvplay.airplay.AirPlayPhysicalSizeBasis
import app.tvplay.airplay.CarPlayDisplayScale
import app.tvplay.airplay.PairingStore
import app.tvplay.airplay.SafeAreaRect
import app.tvplay.orchestration.ManualHotspotBand
import app.tvplay.orchestration.ManualHotspotSecurity
import app.tvplay.orchestration.MfiTarget
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.transport.LockdownPairRecord
import java.io.File

/**
 * Structured, domain-oriented repository for TVPlay settings.
 *
 * Groups settings into [connection], [display], [audio], and [identity] facets while
 * preserving storage compatibility with [AirPlayPersistence] and [TvPlayPreferences].
 */
internal class TvPlaySettingsRepository(private val context: Context) {
    val connection = ConnectionSettings(context)
    val display = DisplaySettings(context)
    val audio = AudioSettings(context)
    val identity = IdentitySettings(context)

    internal class ConnectionSettings(private val context: Context) {
        var wirelessEnabled: Boolean
            get() = AirPlayPersistence.loadWirelessEnabled(context)
            set(value) = AirPlayPersistence.saveWirelessEnabled(context, value)

        var wirelessHotspotMode: WirelessHotspotMode
            get() = AirPlayPersistence.loadWirelessHotspotMode(context)
            set(value) = AirPlayPersistence.saveWirelessHotspotMode(context, value)

        var wifiP2pPreferredChannel: Int
            get() = AirPlayPersistence.loadWifiP2pPreferredChannel(context)
            set(value) = AirPlayPersistence.saveWifiP2pPreferredChannel(context, value)

        var wifiDirectScanPause: Boolean
            get() = AirPlayPersistence.loadWifiDirectScanPause(context)
            set(value) = AirPlayPersistence.saveWifiDirectScanPause(context, value)

        val existingWifiSsid: String
            get() = AirPlayPersistence.loadExistingWifiSsid(context)

        val existingWifiPassphrase: String
            get() = AirPlayPersistence.loadExistingWifiPassphrase(context)

        fun saveExistingWifiCredentials(ssid: String, passphrase: String) {
            AirPlayPersistence.saveExistingWifiCredentials(context, ssid, passphrase)
        }

        var existingWifiInterface: String
            get() = AirPlayPersistence.loadExistingWifiInterface(context)
            set(value) = AirPlayPersistence.saveExistingWifiInterface(context, value)

        var manualHotspotSsid: String
            get() = AirPlayPersistence.loadManualHotspotSsid(context)
            set(value) = AirPlayPersistence.saveManualHotspotSsid(context, value)

        var manualHotspotPassphrase: String
            get() = AirPlayPersistence.loadManualHotspotPassphrase(context)
            set(value) = AirPlayPersistence.saveManualHotspotPassphrase(context, value)

        var manualHotspotBand: ManualHotspotBand
            get() = AirPlayPersistence.loadManualHotspotBand(context)
            set(value) = AirPlayPersistence.saveManualHotspotBand(context, value)

        var manualHotspotChannel: Int
            get() = AirPlayPersistence.loadManualHotspotChannel(context)
            set(value) = AirPlayPersistence.saveManualHotspotChannel(context, value)

        var manualHotspotSecurity: ManualHotspotSecurity
            get() = AirPlayPersistence.loadManualHotspotSecurity(context)
            set(value) = AirPlayPersistence.saveManualHotspotSecurity(context, value)

        var manualHotspotInterface: String
            get() = AirPlayPersistence.loadManualHotspotInterface(context)
            set(value) = AirPlayPersistence.saveManualHotspotInterface(context, value)

        var defaultConnectionMode: DefaultConnectionMode
            get() = TvPlayPreferences.defaultConnectionMode(context)
            set(value) = TvPlayPreferences.saveDefaultConnectionMode(context, value)

        var autoConnect: Boolean
            get() = TvPlayPreferences.autoConnect(context)
            set(value) = TvPlayPreferences.saveAutoConnect(context, value)

        val autoConnectWireless: Boolean
            get() = TvPlayPreferences.autoConnectWireless(context)

        var connectOnPhoneBluetooth: Boolean
            get() = TvPlayPreferences.connectOnPhoneBluetooth(context)
            set(value) = TvPlayPreferences.saveConnectOnPhoneBluetooth(context, value)

        var autoStartOnBoot: Boolean
            get() = AirPlayPersistence.loadAutoStartOnBoot(context)
            set(value) = AirPlayPersistence.saveAutoStartOnBoot(context, value)

        val pairedPhoneAddress: String?
            get() = TvPlayPreferences.phoneAddress(context)

        val pairedPhoneName: String
            get() = TvPlayPreferences.phoneName(context)

        fun savePairedPhone(address: String, name: String) {
            TvPlayPreferences.savePhone(context, address, name)
        }

        var externalController: Boolean
            get() = AirPlayPersistence.loadExternalController(context)
            set(value) = AirPlayPersistence.saveExternalController(context, value)
    }

    internal class DisplaySettings(private val context: Context) {
        var displayScalePercent: Int
            get() = AirPlayPersistence.loadDisplayScalePercent(context)
            set(value) {
                AirPlayPersistence.saveDisplayScalePercent(context, value)
                AirPlayPersistence.saveDisplayScaleTenths(
                    context,
                    CarPlayDisplayScale.sanitize((value + 5) / 10),
                )
            }

        var uiScalePercent: Int
            get() = AirPlayPersistence.loadUiScalePercent(context)
            set(value) = AirPlayPersistence.saveUiScalePercent(context, value)

        var fps: Int
            get() = AirPlayPersistence.loadFps(context)
            set(value) = AirPlayPersistence.saveFps(context, value)

        var hevcEnabled: Boolean
            get() = AirPlayPersistence.loadHevcEnabled(context)
            set(value) = AirPlayPersistence.saveHevcEnabled(context, value)

        var hevcSoftwareDecoderEnabled: Boolean
            get() = AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context)
            set(value) = AirPlayPersistence.saveHevcSoftwareDecoderEnabled(context, value)

        var smoothVideo: Boolean
            get() = AirPlayPersistence.loadSmoothVideo(context)
            set(value) = AirPlayPersistence.saveSmoothVideo(context, value)

        var directVideoOutput: Boolean
            get() = AirPlayPersistence.loadDirectVideoOutput(context)
            set(value) = AirPlayPersistence.saveDirectVideoOutput(context, value)

        var lowLatencyDecoder: Boolean
            get() = AirPlayPersistence.loadLowLatencyDecoder(context)
            set(value) = AirPlayPersistence.saveLowLatencyDecoder(context, value)

        var mtkDecoderTuning: Boolean
            get() = AirPlayPersistence.loadMtkDecoderTuning(context)
            set(value) = AirPlayPersistence.saveMtkDecoderTuning(context, value)

        var fpsCounterEnabled: Boolean
            get() = AirPlayPersistence.loadFpsCounter(context)
            set(value) = AirPlayPersistence.saveFpsCounter(context, value)

        var carPlayNightMode: CarPlayNightMode
            get() = AirPlayPersistence.loadCarPlayNightMode(context)
            set(value) = AirPlayPersistence.saveCarPlayNightMode(context, value)

        var carPlayNightSchedule: CarPlayNightSchedule
            get() = AirPlayPersistence.loadCarPlayNightSchedule(context)
            set(value) = AirPlayPersistence.saveCarPlayNightSchedule(context, value)

        var appAppearance: AppAppearance
            get() = AirPlayPersistence.loadAppAppearance(context)
            set(value) = AirPlayPersistence.saveAppAppearance(context, value)

        var carPlayDock: CarPlayDock
            get() = CarPlayDock.load(context)
            set(value) = CarPlayDock.save(context, value)

        var rightHandDrive: Boolean
            get() = AirPlayPersistence.loadRightHandDrive(context)
            set(value) = AirPlayPersistence.saveRightHandDrive(context, value)

        var widthPhysicalMm: Int
            get() = AirPlayPersistence.loadWidthPhysicalMm(context)
            set(value) = AirPlayPersistence.saveWidthPhysicalMm(context, value)

        var physicalSizeBasis: AirPlayPhysicalSizeBasis
            get() = AirPlayPersistence.loadPhysicalSizeBasis(context)
            set(value) = AirPlayPersistence.savePhysicalSizeBasis(context, value)

        var maximumDetectedDisplay: Pair<Int, Int>
            get() = AirPlayPersistence.loadMaximumDetectedDisplay(context)
            set(value) = AirPlayPersistence.saveMaximumDetectedDisplay(context, value.first, value.second)

        var adaptPipResolution: Boolean
            get() = AirPlayPersistence.loadAdaptPipResolution(context)
            set(value) = AirPlayPersistence.saveAdaptPipResolution(context, value)

        var hideTopBar: Boolean
            get() = AirPlayPersistence.loadHideTopBar(context)
            set(value) = AirPlayPersistence.saveHideTopBar(context, value)

        var hideBottomBar: Boolean
            get() = AirPlayPersistence.loadHideBottomBar(context)
            set(value) = AirPlayPersistence.saveHideBottomBar(context, value)

        var temporarySystemBars: Boolean
            get() = AirPlayPersistence.loadTemporarySystemBars(context)
            set(value) = AirPlayPersistence.saveTemporarySystemBars(context, value)

        var safeAreaDrawOutside: Boolean
            get() = AirPlayPersistence.loadSafeAreaDrawOutside(context)
            set(value) = AirPlayPersistence.saveSafeAreaDrawOutside(context, value)

        var mainDisplayDisableSafeArea: Boolean
            get() = AirPlayPersistence.loadMainDisplayDisableSafeArea(context)
            set(value) = AirPlayPersistence.saveMainDisplayDisableSafeArea(context, value)

        var blackCornerRadiusDp: Int
            get() = AirPlayPersistence.loadBlackCornerRadiusDp(context)
            set(value) = AirPlayPersistence.saveBlackCornerRadiusDp(context, value)

        fun loadSafeAreaRect(widthPixels: Int, heightPixels: Int): SafeAreaRect? =
            AirPlayPersistence.loadSafeAreaRect(context, widthPixels, heightPixels)
    }

    internal class AudioSettings(private val context: Context) {
        var audioFocusEnabled: Boolean
            get() = AirPlayPersistence.loadAudioFocusEnabled(context)
            set(value) = AirPlayPersistence.saveAudioFocusEnabled(context, value)

        var audioFocusAutoYield: Boolean
            get() = AirPlayPersistence.loadAudioFocusAutoYield(context)
            set(value) = AirPlayPersistence.saveAudioFocusAutoYield(context, value)

        var navigationPromptAudioFocus: Boolean
            get() = AirPlayPersistence.loadNavigationPromptAudioFocus(context)
            set(value) = AirPlayPersistence.saveNavigationPromptAudioFocus(context, value)

        var disableMediaKeyAudioFocus: Boolean
            get() = AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)
            set(value) = AirPlayPersistence.saveDisableMediaKeyAudioFocus(context, value)

        var advancedAudioChannelMapping: Boolean
            get() = AirPlayPersistence.loadAdvancedAudioChannelMapping(context)
            set(value) = AirPlayPersistence.saveAdvancedAudioChannelMapping(context, value)

        var mediaAudioChannel: Int
            get() = AirPlayPersistence.loadMediaAudioChannel(context)
            set(value) = AirPlayPersistence.saveMediaAudioChannel(context, value)

        var navigationAudioChannel: Int
            get() = AirPlayPersistence.loadNavigationAudioChannel(context)
            set(value) = AirPlayPersistence.saveNavigationAudioChannel(context, value)

        var navigationStreamType: Int
            get() = AirPlayPersistence.loadNavigationStreamType(context)
            set(value) = AirPlayPersistence.saveNavigationStreamType(context, value)

        var navigationAudioAttenuationDb: Int
            get() = AirPlayPersistence.loadNavigationAudioAttenuationDb(context)
            set(value) = AirPlayPersistence.saveNavigationAudioAttenuationDb(context, value)

        fun loadMediaBufferMillis(wireless: Boolean): Int =
            AirPlayPersistence.loadMediaBufferMillis(context, wireless = wireless)

        fun saveMediaBufferMillis(wireless: Boolean, millis: Int) =
            AirPlayPersistence.saveMediaBufferMillis(context, wireless = wireless, millis = millis)

        var mainBufferedAudio: Boolean
            get() = AirPlayPersistence.loadMainBufferedAudio(context)
            set(value) = AirPlayPersistence.saveMainBufferedAudio(context, value)

        var speechWithout8And16Khz: Boolean
            get() = AirPlayPersistence.loadSpeechWithout8And16Khz(context)
            set(value) = AirPlayPersistence.saveSpeechWithout8And16Khz(context, value)

        var callAtCallRate: Boolean
            get() = AirPlayPersistence.loadCallAtCallRate(context)
            set(value) = AirPlayPersistence.saveCallAtCallRate(context, value)

        var carBluetoothAudio: Boolean
            get() = AirPlayPersistence.loadCarBluetoothAudio(context)
            set(value) = AirPlayPersistence.saveCarBluetoothAudio(context, value)

        var callEchoCancellation: Boolean
            get() = AirPlayPersistence.loadCallEchoCancellation(context)
            set(value) = AirPlayPersistence.saveCallEchoCancellation(context, value)

        var callVoiceFilter: Boolean
            get() = AirPlayPersistence.loadCallVoiceFilter(context)
            set(value) = AirPlayPersistence.saveCallVoiceFilter(context, value)

        var callSpeakerphone: Boolean
            get() = AirPlayPersistence.loadCallSpeakerphone(context)
            set(value) = AirPlayPersistence.saveCallSpeakerphone(context, value)
    }

    internal class IdentitySettings(private val context: Context) {
        var mfiTarget: MfiTarget
            get() = AirPlayPersistence.loadMfiTarget(context)
            set(value) = AirPlayPersistence.saveMfiTarget(context, value)

        var mfiI2cPath: String
            get() = AirPlayPersistence.loadMfiI2cPath(context)
            set(value) = AirPlayPersistence.saveMfiI2cPath(context, value)

        var remoteMfiServer: String
            get() = AirPlayPersistence.loadRemoteMfiServer(context)
            set(value) = AirPlayPersistence.saveRemoteMfiServer(context, value)

        var remoteMfiToken: String
            get() = AirPlayPersistence.loadRemoteMfiToken(context)
            set(value) = AirPlayPersistence.saveRemoteMfiToken(context, value)

        var manufacturer: String
            get() = AirPlayPersistence.loadManufacturer(context)
            set(value) = AirPlayPersistence.saveManufacturer(context, value)

        var model: String
            get() = AirPlayPersistence.loadModel(context)
            set(value) = AirPlayPersistence.saveModel(context, value)

        var oemLabel: String
            get() = AirPlayPersistence.loadOemLabel(context)
            set(value) = AirPlayPersistence.saveOemLabel(context, value)

        var debugLogsEnabled: Boolean
            get() = AirPlayPersistence.loadDebugLogsEnabled(context)
            set(value) = AirPlayPersistence.saveDebugLogsEnabled(context, value)

        fun loadAirPlayIdentity(): AirPlayIdentity = AirPlayPersistence.loadIdentity(context)

        fun loadPairings(onSave: (String, ByteArray) -> Unit): PairingStore =
            AirPlayPersistence.loadPairings(context, onSave)

        fun savePairing(identifier: String, longTermPublicKey: ByteArray) =
            AirPlayPersistence.savePairing(context, identifier, longTermPublicKey)

        var lockdownRecord: LockdownPairRecord?
            get() = AirPlayPersistence.loadLockdownRecord(context)
            set(value) {
                if (value == null) AirPlayPersistence.clearLockdownRecord(context)
                else AirPlayPersistence.saveLockdownRecord(context, value)
            }

        val customAirPlayIconFile: File?
            get() = AirPlayPersistence.loadCustomAirPlayIconFile(context)

        fun clearCustomAirPlayIcon() = AirPlayPersistence.clearCustomAirPlayIcon(context)
    }
}
