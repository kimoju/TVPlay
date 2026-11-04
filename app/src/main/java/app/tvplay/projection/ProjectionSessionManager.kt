package app.tvplay.projection

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import app.tvplay.AirPlayPersistence
import app.tvplay.CarPlaySessionDisplay
import app.tvplay.TvPlayBootstrap
import app.tvplay.TvPlayPreferences
import app.tvplay.airplay.AirPlayDisplayConfig
import app.tvplay.airplay.AirPlayIdentity
import app.tvplay.media.AndroidMediaSink
import app.tvplay.orchestration.CarPlayController
import app.tvplay.orchestration.CarPlayRuntimeConfig
import app.tvplay.orchestration.CarPlayStatus
import app.tvplay.orchestration.CarPlayTransport
import app.tvplay.orchestration.ManualHotspotBand
import app.tvplay.orchestration.ManualHotspotSecurity
import app.tvplay.orchestration.MfiTarget
import app.tvplay.orchestration.WirelessHotspotMode
import app.tvplay.transport.Iap2IdentificationConfig
import app.tvplay.transport.UsbDeviceId

internal data class ProjectionCanvasSupport(
    val supported: Boolean,
    val reason: String,
    val details: String,
)

/**
 * Session lifecycle and configuration coordinator for [app.tvplay.TvPlayProjectionActivity].
 */
internal object ProjectionSessionManager {
    private const val RECONNECT_DELAY_MILLIS = 2_000L
    private const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L

    fun createRuntimeConfig(
        context: Context,
        airPlayIdentity: AirPlayIdentity,
        mfiTarget: MfiTarget,
        mfiI2cPath: String,
        remoteMfiServer: String,
        remoteMfiToken: String,
        manufacturer: String,
        model: String,
        wirelessEnabled: Boolean,
        wirelessHotspotMode: WirelessHotspotMode,
        manualHotspotSsid: String,
        manualHotspotPassphrase: String,
        manualHotspotBand: ManualHotspotBand,
        manualHotspotChannel: Int,
        manualHotspotSecurity: ManualHotspotSecurity,
        manualHotspotInterface: String,
        existingWifiSsid: String,
        existingWifiPassphrase: String,
        existingWifiInterface: String,
    ): CarPlayRuntimeConfig {
        val deviceIdHex = TvPlayBootstrap.deviceId(airPlayIdentity)
        val compactId = deviceIdHex.replace(":", "")
        return CarPlayRuntimeConfig(
            mfiTarget = mfiTarget,
            ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
                listOf(UsbDeviceId(0x1a86, 0x5512))
            } else {
                emptyList()
            },
            ch341MfiResetGpio = null,
            linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
            remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
            remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
            identification = Iap2IdentificationConfig(
                name = "TVPlay",
                modelIdentifier = model.trim().ifBlank { "AppleTV" },
                manufacturer = manufacturer.trim().ifBlank { "Apple" },
                serialNumber = "TVPLAY-$compactId",
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = false,
                vehicleStatusEnabled = false,
                vehicleSpeedEnabled = false,
            ),
            label = "TVPlay",
            hostName = "tvplay-${compactId.lowercase()}",
            hostMac = deviceIdHex.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = TvPlayPreferences.phoneAddress(context),
            transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = wirelessHotspotMode,
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(context),
            manualHotspotSsid = manualHotspotSsid,
            manualHotspotPassphrase = manualHotspotPassphrase,
            manualHotspotBand = manualHotspotBand,
            manualHotspotChannel = manualHotspotChannel,
            manualHotspotSecurity = manualHotspotSecurity,
            manualHotspotInterface = manualHotspotInterface,
            existingWifiSsid = existingWifiSsid,
            existingWifiPassphrase = existingWifiPassphrase,
            existingWifiInterface = existingWifiInterface.trim().takeIf { it.isNotEmpty() },
            locationReportingEnabled = false,
            availableCurrentMilliAmps = AirPlayPersistence.loadUsbChargingCurrent(context).milliAmps,
        )
    }

    fun evaluateLargerCanvasSupport(
        display: AirPlayDisplayConfig,
        sessionHevc: Boolean,
        hevcSoftwareDecoderEnabled: Boolean,
    ): ProjectionCanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 ||
            minOf(display.widthPixels, display.heightPixels) > 2160
        ) {
            ProjectionCanvasSupport(
                false,
                "canvas_4k_limit",
                "Decoder capability check skipped: canvas exceeds enlargement limit",
            )
        } else {
            evaluateDecoderCanvasSupport(display, sessionHevc, hevcSoftwareDecoderEnabled)
        }

    fun evaluateDecoderCanvasSupport(
        display: AirPlayDisplayConfig,
        sessionHevc: Boolean,
        hevcSoftwareDecoderEnabled: Boolean,
    ): ProjectionCanvasSupport = try {
        val mime = if (sessionHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            ProjectionCanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) {
                decoder.isHardwareAccelerated
            } else {
                !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            }
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels,
                display.heightPixels,
                display.fps.toDouble(),
            ) == true
            val reason = when {
                !hardware -> "software_decoder"
                sessionHevc && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            ProjectionCanvasSupport(
                reason == "supported",
                reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported result=$reason",
            )
        }
    } catch (error: Exception) {
        ProjectionCanvasSupport(
            false,
            "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}",
        )
    }

    fun computeReconnectDelayMillis(
        startupDelay: Long?,
        reason: String,
        reconnectAttempts: Int,
    ): Long = when {
        startupDelay != null -> startupDelay
        reason.contains("AirPlay iAP tunnel", ignoreCase = true) -> IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        else -> (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
    }
}
